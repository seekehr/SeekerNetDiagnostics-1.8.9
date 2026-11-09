package com.seekehr.netdiagnostics.helper;

import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Finds the relays ExitLag is really using and keeps hop-by-hop traces of them (and of the game server).
 *
 * <p>A relay is <b>verified</b> only when packets on a socket owned by an ExitLag process (Windows socket tables)
 * to or from that endpoint were captured within the last {@link #VERIFIED_WINDOW_MS}. ExitLag TCP connections
 * without captured traffic are listed as unverified. A verified relay is <b>in use</b> when its traffic is
 * sustained ({@link #inUse}); ExitLag also opens many short connections to candidate relays just to measure them,
 * and those are not routes. Only in-use relays are traced and reported as route changes. Nothing past a relay is
 * traced or guessed: the route inside ExitLag's tunnel is not visible from this PC.
 */
final class RelayMonitor {
    static final long VERIFIED_WINDOW_MS = 10000;
    static final long FORGET_MS = 120000;
    static final long RELAY_RETRACE_MS = 60000;
    static final long SERVER_RETRACE_MS = 120000;
    static final int MAX_TRACED_RELAYS = 4;
    /** A relay stays "in use" until it has been silent this long, so multipath switching does not flap. */
    static final long IN_USE_HOLD_MS = 20000;
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final Capture cap;
    private final Map<String, long[]> prevCounts = new HashMap<String, long[]>();
    /** Per flow: first-capture time and packet counts of the last ticks (oldest first). */
    private final Map<String, Hist> history = new HashMap<String, Hist>();
    /** Last time each flow met the in-use test. */
    private final Map<String, Long> lastInUse = new HashMap<String, Long>();
    /** Probe protocols ("TCP"/"UDP") that were taken over on this PC; traced with ICMP echo instead. */
    private final Set<String> interceptedProtocols = new LinkedHashSet<String>();
    private long prevTick;
    private final Map<String, Snapshot.Path> paths = new LinkedHashMap<String, Snapshot.Path>();
    private final Map<String, Long> lastTraced = new HashMap<String, Long>();
    private final List<Snapshot.Event> events = new ArrayList<Snapshot.Event>();
    private List<Snapshot.Relay> relays = new ArrayList<Snapshot.Relay>();
    private Set<String> active = new LinkedHashSet<String>();
    private String primary;
    private boolean firstScan = true;
    private boolean exitLagRunning;
    private volatile boolean icmpSeen;
    private volatile String serverIp;
    private volatile int serverPort;
    private volatile boolean retraceRequested;
    private volatile String tracing;
    private final AtomicBoolean cancel = new AtomicBoolean();

    RelayMonitor(Capture cap) {
        this.cap = cap;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                tracerLoop();
            }
        }, "SND-tracer");
        t.setDaemon(true);
        t.start();
    }

    /** Sets the game server to trace; ignored unless it is an IPv4 literal and a valid port. */
    void setServer(String ip, int port) {
        if (ip == null || !IPV4.matcher(ip).matches() || port < 1 || port > 65535) {
            return;
        }
        if (!ip.equals(serverIp) || port != serverPort) {
            serverIp = ip;
            serverPort = port;
            synchronized (this) {
                lastTraced.remove(serverKey());
            }
        }
    }

    void retraceAll() {
        retraceRequested = true;
    }

    void stop() {
        cancel.set(true);
    }

    private String serverKey() {
        return "server:TCP:" + serverIp + ":" + serverPort;
    }

    static String relayKey(String protocol, String ip, int port) {
        return "relay:" + protocol + ":" + ip + ":" + port;
    }

    // ------------------------------------------------------------------ periodic scan

    /** Refreshes socket ownership and relay rates; detects relay changes. Call every ~2 s. */
    synchronized void tick() {
        long now = System.currentTimeMillis();
        cap.refreshLocals();
        Map<Integer, Integer> owned = new HashMap<Integer, Integer>();
        Set<Integer> pids = new LinkedHashSet<Integer>();
        Map<String, Snapshot.Relay> found = new LinkedHashMap<String, Snapshot.Relay>();
        boolean running = false;
        for (WinTcpTrace.TcpRow r : WinTcpTrace.tcpTable(WinTcpTrace.AF_INET)) {
            String name = WinTcpTrace.processName(r.pid);
            if (!isExitLag(name)) {
                continue;
            }
            running = true;
            pids.add(r.pid);
            owned.put(Capture.ownerKey(Packets.TCP, r.localPort), r.pid);
            if (r.state == 5 /* ESTABLISHED */ && r.remotePort != 0 && !WinTcpTrace.isLoopback(r.remote)
                    && !"0.0.0.0".equals(r.remote)) {
                Snapshot.Relay rel = relay("TCP", r.remote, r.remotePort, r.pid, name);
                found.put("TCP " + rel.endpoint(), rel);
            }
        }
        for (WinTcpTrace.UdpRow r : WinTcpTrace.udpTable()) {
            String name = WinTcpTrace.processName(r.pid);
            if (isExitLag(name)) {
                running = true;
                pids.add(r.pid);
                owned.put(Capture.ownerKey(Packets.UDP, r.localPort), r.pid);
            }
        }
        cap.setOwned(owned, pids);
        exitLagRunning = running;

        double dt = prevTick == 0 ? 0 : (now - prevTick) / 1000.0;
        prevTick = now;
        for (Capture.Flow f : cap.flows()) {
            long age = now - f.lastSeen;
            if (age > FORGET_MS) {
                cap.forget(f.key());
                prevCounts.remove(f.key());
                history.remove(f.key());
                lastInUse.remove(f.key());
                continue;
            }
            String proto = f.protocol == Packets.TCP ? "TCP" : "UDP";
            Snapshot.Relay rel = found.get(f.key());
            if (rel == null) {
                rel = relay(proto, f.remote, f.remotePort, f.pid, WinTcpTrace.processName(f.pid));
                found.put(f.key(), rel);
            }
            long out = f.packetsOut.get();
            long in = f.packetsIn.get();
            long bytes = f.bytes.get();
            long[] prev = prevCounts.get(f.key());
            Hist h = history.get(f.key());
            if (h == null) {
                h = new Hist(now);
                history.put(f.key(), h);
            }
            long delta = out + in - (prev == null ? 0 : prev[0] + prev[1]);
            h.add((int) Math.min(Integer.MAX_VALUE, delta));
            if (prev != null && dt > 0) {
                rel.packetsOutPerSec = (out - prev[0]) / dt;
                rel.packetsInPerSec = (in - prev[1]) / dt;
                rel.bytesPerSec = (bytes - prev[2]) / dt;
            }
            prevCounts.put(f.key(), new long[]{out, in, bytes});
            rel.lastSeenAgoMs = age;
            rel.verified = age <= VERIFIED_WINDOW_MS;
            rel.activeSeconds = (now - h.firstSeen) / 1000;
            boolean raw = !rel.service && inUse(h.deltas(), now - h.firstSeen);
            if (raw) {
                lastInUse.put(f.key(), now);
            }
            rel.inUse = !rel.service && held(raw, lastInUse.get(f.key()), now, age);
        }
        List<Snapshot.Relay> list = new ArrayList<Snapshot.Relay>(found.values());
        Collections.sort(list, new Comparator<Snapshot.Relay>() {
            @Override
            public int compare(Snapshot.Relay a, Snapshot.Relay b) {
                if (a.inUse != b.inUse) {
                    return a.inUse ? -1 : 1;
                }
                if (a.verified != b.verified) {
                    return a.verified ? -1 : 1;
                }
                if (a.service != b.service) {
                    return a.service ? 1 : -1;
                }
                return Double.compare(rate(b), rate(a));
            }
        });

        Set<String> nowActive = new LinkedHashSet<String>();
        Snapshot.Relay top = null;
        for (Snapshot.Relay r : list) {
            if (r.inUse) {
                nowActive.add(r.protocol + " " + r.endpoint());
                if (top == null || rate(r) > rate(top)) {
                    top = r;
                }
            }
        }
        String newPrimary = choosePrimary(primary, top == null ? null : top.protocol + " " + top.endpoint(), list);
        for (Snapshot.Relay r : list) {
            r.primary = newPrimary != null && newPrimary.equals(r.protocol + " " + r.endpoint());
            String key = relayKey(r.protocol, r.ip, r.port);
            if (paths.containsKey(key)) {
                r.pathKey = key;
            }
        }
        if (firstScan) {
            if (!nowActive.isEmpty()) {
                event("Found " + nowActive.size() + " ExitLag relay(s) in use: " + join(nowActive));
            } else if (running) {
                event("ExitLag is running; no relay is in use yet (it may only be measuring relays).");
            } else {
                event("No ExitLag process found.");
            }
            firstScan = false;
        } else {
            for (String c : diffActive(active, nowActive)) {
                event(c);
            }
            if (newPrimary != null && primary != null && !newPrimary.equals(primary)) {
                event("Primary ExitLag relay changed: " + primary + " -> " + newPrimary);
                lastTraced.remove(relayKeyOf(newPrimary));
            }
        }
        active = nowActive;
        primary = newPrimary;
        relays = list;
    }

    /** Keeps the current primary unless it went idle or another relay carries clearly more (1.5x) traffic. */
    static String choosePrimary(String current, String top, List<Snapshot.Relay> list) {
        if (top == null) {
            return null;
        }
        if (current == null || current.equals(top)) {
            return top;
        }
        Snapshot.Relay cur = null;
        Snapshot.Relay best = null;
        for (Snapshot.Relay r : list) {
            String k = r.protocol + " " + r.endpoint();
            if (k.equals(current)) {
                cur = r;
            }
            if (k.equals(top)) {
                best = r;
            }
        }
        if (cur == null || !cur.inUse || best != null && rate(best) > 1.5 * rate(cur) + 1) {
            return top;
        }
        return current;
    }

    /** Packet history of one flow, one entry per tick. */
    static final class Hist {
        final long firstSeen;
        private final int[] ring = new int[10];
        private int n;

        Hist(long firstSeen) {
            this.firstSeen = firstSeen;
        }

        void add(int v) {
            if (n < ring.length) {
                ring[n++] = v;
            } else {
                System.arraycopy(ring, 1, ring, 0, ring.length - 1);
                ring[ring.length - 1] = v;
            }
        }

        int[] deltas() {
            int[] out = new int[n];
            System.arraycopy(ring, 0, out, 0, n);
            return out;
        }
    }

    /**
     * Whether a flow is a tunnel in use rather than a measurement: traffic in at least 6 of the last 8 ticks
     * (~2 s each) after 15 s, or at least 20 packets in each of the last 3 ticks (busy, e.g. a game stream).
     *
     * @param deltas packets per tick, oldest first
     */
    static boolean inUse(int[] deltas, long ageMs) {
        int n = deltas.length;
        if (n >= 3 && deltas[n - 1] >= 20 && deltas[n - 2] >= 20 && deltas[n - 3] >= 20) {
            return true;
        }
        if (ageMs < 15000 || n < 8) {
            return false;
        }
        int busy = 0;
        for (int i = n - 8; i < n; i++) {
            if (deltas[i] > 0) {
                busy++;
            }
        }
        return busy >= 6;
    }

    /**
     * In-use with hysteresis: once a flow has met the in-use test, it stays in use until it has been silent for
     * {@link #IN_USE_HOLD_MS}.
     *
     * @param lastRawMs last time the flow met the in-use test, or null if it never did
     * @param ageMs     ms since its last captured packet
     */
    static boolean held(boolean raw, Long lastRawMs, long now, long ageMs) {
        return raw || lastRawMs != null && lastRawMs <= now && ageMs <= IN_USE_HOLD_MS;
    }

    /** Events for relays that started or stopped carrying ExitLag traffic. */
    static List<String> diffActive(Set<String> before, Set<String> after) {
        List<String> out = new ArrayList<String>();
        for (String a : after) {
            if (!before.contains(a)) {
                out.add("ExitLag started routing through relay " + a);
            }
        }
        for (String b : before) {
            if (!after.contains(b)) {
                out.add("ExitLag stopped routing through relay " + b);
            }
        }
        return out;
    }

    /**
     * Describes how a path changed between two traces, or null if the routers are the same. Only TTLs where both
     * traces got a reply are compared, so a lost probe is not reported as a change.
     */
    static String pathChange(List<Snapshot.Hop> before, List<Snapshot.Hop> after, String target) {
        if (before == null || before.isEmpty() || after == null || after.isEmpty()) {
            return null;
        }
        int n = Math.min(before.size(), after.size());
        for (int i = 0; i < n; i++) {
            String a = before.get(i).address;
            String b = after.get(i).address;
            if (a != null && b != null && !a.equals(b) && !after.get(i).others.contains(a)
                    && !before.get(i).others.contains(b)) {
                return "Path to " + target + " changed at hop " + (i + 1) + ": " + a + " -> " + b;
            }
        }
        int da = destHop(before);
        int db = destHop(after);
        if (da > 0 && db > 0 && da != db) {
            return "Path to " + target + " is now " + db + " hops (was " + da + ")";
        }
        return null;
    }

    private static int destHop(List<Snapshot.Hop> hops) {
        for (Snapshot.Hop h : hops) {
            if (h.destination) {
                return h.ttl;
            }
        }
        return -1;
    }

    private static Snapshot.Relay relay(String proto, String ip, int port, int pid, String name) {
        Snapshot.Relay r = new Snapshot.Relay();
        r.protocol = proto;
        r.ip = ip;
        r.port = port;
        r.pid = pid;
        r.process = name == null ? "pid " + pid : name;
        r.service = port == 443 || port == 80;
        return r;
    }

    private static double rate(Snapshot.Relay r) {
        return r.packetsOutPerSec + r.packetsInPerSec;
    }

    static boolean isExitLag(String processName) {
        return processName != null && processName.toLowerCase(Locale.ROOT).contains("exitlag");
    }

    private static String relayKeyOf(String protoEndpoint) {
        int sp = protoEndpoint.indexOf(' ');
        int colon = protoEndpoint.lastIndexOf(':');
        return relayKey(protoEndpoint.substring(0, sp), protoEndpoint.substring(sp + 1, colon),
                Integer.parseInt(protoEndpoint.substring(colon + 1)));
    }

    private void event(String text) {
        Snapshot.Event e = new Snapshot.Event();
        e.time = System.currentTimeMillis();
        e.text = text;
        events.add(e);
        while (events.size() > 30) {
            events.remove(0);
        }
    }

    private static String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            sb.append(sb.length() == 0 ? "" : ", ").append(x);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ tracing

    private void tracerLoop() {
        while (!cancel.get()) {
            Job job = nextJob();
            if (job == null) {
                sleep(1000);
                continue;
            }
            run(job);
        }
    }

    private static final class Job {
        String key;
        String kind;
        String protocol;
        String ip;
        int port;
    }

    private synchronized Job nextJob() {
        long now = System.currentTimeMillis();
        if (retraceRequested) {
            retraceRequested = false;
            lastTraced.clear();
        }
        int traced = 0;
        for (Snapshot.Relay r : relays) {
            if (!r.inUse || traced >= MAX_TRACED_RELAYS) {
                continue;
            }
            traced++;
            String key = relayKey(r.protocol, r.ip, r.port);
            Long last = lastTraced.get(key);
            if (last == null || now - last > RELAY_RETRACE_MS) {
                Job j = new Job();
                j.key = key;
                j.kind = "relay";
                j.protocol = r.protocol;
                j.ip = r.ip;
                j.port = r.port;
                lastTraced.put(key, now);
                return j;
            }
        }
        if (serverIp != null) {
            String key = serverKey();
            Long last = lastTraced.get(key);
            if (last == null || now - last > SERVER_RETRACE_MS) {
                Job j = new Job();
                j.key = key;
                j.kind = "server";
                j.protocol = "TCP";
                j.ip = serverIp;
                j.port = serverPort;
                lastTraced.put(key, now);
                return j;
            }
        }
        return null;
    }

    private void run(final Job job) {
        InetAddress dest;
        try {
            dest = InetAddress.getByName(job.ip); // literal, no DNS
        } catch (Exception e) {
            return;
        }
        if (!(dest instanceof Inet4Address)) {
            return; // capture is IPv4 only
        }
        final Snapshot.Path p;
        final List<Snapshot.Hop> previous;
        synchronized (this) {
            Snapshot.Path old = paths.get(job.key);
            previous = old == null ? null : old.hops;
            p = new Snapshot.Path();
            p.key = job.key;
            p.kind = job.kind;
            p.protocol = "relay".equals(job.kind) && interceptedProtocols.contains(job.protocol) ? "ICMP"
                    : job.protocol;
            p.target = job.ip;
            p.port = job.port;
            p.state = "running";
            p.traces = old == null ? 1 : old.traces + 1;
            p.hops = old == null ? new ArrayList<Snapshot.Hop>() : old.hops;
            p.note = old == null ? "" : old.note;
            p.updatedAt = System.currentTimeMillis();
            paths.put(job.key, p);
        }
        tracing = job.key;
        PathTracer.Progress progress = new PathTracer.Progress() {
            @Override
            public void update(List<Snapshot.Hop> hops) {
                synchronized (RelayMonitor.this) {
                    if (previous == null || hops.size() >= previous.size()) {
                        p.hops = hops;
                    }
                }
            }
        };
        String method = job.protocol;
        String prefix = "";
        String icmpBecause = job.protocol + " probes from this PC are taken over by ExitLag, so this path was measured "
                + "with ICMP echo to the relay (normally the same routers). ";
        boolean known;
        synchronized (this) {
            known = interceptedProtocols.contains(job.protocol);
        }
        PathTracer.Result res;
        if ("relay".equals(job.kind) && known) {
            res = PathTracer.traceIcmp((Inet4Address) dest, progress, cancel);
            method = "ICMP";
            prefix = icmpBecause;
        } else {
            res = PathTracer.trace(cap, dest, job.port, "TCP".equals(job.protocol), progress, cancel);
            boolean blind = !res.reached && !res.icmpSeen && countReplies(res.hops) == 0;
            if (res.intercepted) {
                synchronized (this) {
                    interceptedProtocols.add(job.protocol);
                }
            }
            if ("relay".equals(job.kind) && (res.intercepted || blind)) {
                String why = res.intercepted ? icmpBecause : "No replies to " + job.protocol + " probes were "
                        + "received (firewall?), so this path was measured with ICMP echo. ";
                res = PathTracer.traceIcmp((Inet4Address) dest, progress, cancel);
                method = "ICMP";
                prefix = why;
            } else if (res.intercepted) {
                prefix = "ExitLag takes TCP to the server over on this PC, so the server's real path is inside "
                        + "ExitLag's tunnel; see the relay paths. ";
            }
        }
        tracing = null;
        synchronized (this) {
            p.protocol = method;
            p.hops = res.hops;
            p.reached = res.reached;
            p.note = prefix + res.note;
            p.state = "done";
            p.updatedAt = System.currentTimeMillis();
            if (res.icmpSeen) {
                icmpSeen = true;
            }
            String change = pathChange(previous, res.hops, job.ip);
            if (change != null) {
                event(change);
            }
        }
    }

    private static int countReplies(List<Snapshot.Hop> hops) {
        int n = 0;
        for (Snapshot.Hop h : hops) {
            n += h.received;
        }
        return n;
    }

    // ------------------------------------------------------------------ snapshot

    synchronized Snapshot snapshot(boolean captureOk, String status) {
        Snapshot s = new Snapshot();
        s.captureOk = captureOk;
        s.status = status;
        s.icmpSeen = icmpSeen;
        s.exitLagRunning = exitLagRunning;
        s.relays = new ArrayList<Snapshot.Relay>(relays);
        for (Snapshot.Relay r : s.relays) {
            if (r.pathKey == null && paths.containsKey(relayKey(r.protocol, r.ip, r.port))) {
                r.pathKey = relayKey(r.protocol, r.ip, r.port);
            }
        }
        for (Snapshot.Path p : paths.values()) {
            Snapshot.Path c = new Snapshot.Path();
            c.key = p.key;
            c.kind = p.kind;
            c.protocol = p.protocol;
            c.target = p.target;
            c.port = p.port;
            c.state = p.state;
            c.reached = p.reached;
            c.note = p.note;
            c.updatedAt = p.updatedAt;
            c.traces = p.traces;
            c.hops = new ArrayList<Snapshot.Hop>(p.hops);
            s.paths.add(c);
        }
        s.events = new ArrayList<Snapshot.Event>(events);
        return s;
    }

    String tracing() {
        return tracing;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
