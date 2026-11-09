package com.seekehr.netdiagnostics.core.monitor;

import com.seekehr.netdiagnostics.core.dns.DnsResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two measurements of the route to a server, run side by side:
 *
 * <ul>
 * <li><b>TCP check</b> (Windows): TCP SYNs to the game port with limited TTL, from this process like the game
 * connection. Finds how many hops away the server's TCP endpoint answers, its RTT, and whether a local proxy or
 * tunnel took the connection over. A proxy is named only when that is verified: the process that accepted the
 * connection was identified (ExitLag, NoPing, WTFast, any other), or the probe left through a VPN/tunnel adapter.</li>
 * <li><b>ICMP hops</b>: the operating system's traceroute ({@code tracert} on Windows, {@code traceroute}
 * elsewhere), which lists the routers. This is the system's ICMP/UDP path: proxies such as ExitLag do not carry
 * it, so it is never the proxy's route. (Windows does not let an unprivileged program see routers' replies to TCP probes.)</li>
 * </ul>
 */
public final class Traceroute {
    private static final Pattern HOP = Pattern.compile("^\\s*(\\d+)\\s+(.*)$");
    private static final Pattern MS = Pattern.compile("(<?\\s*\\d+(?:[.,]\\d+)?)\\s*ms");
    private static final Pattern IP = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-fA-F]*:[0-9a-fA-F:]+)");

    static final int MAX_TTL = 64;
    static final int TCP_TIMEOUT_MS = 2000;

    public static final class Hop {
        public final int number;
        public final String address;
        /** Up to three round-trip times in ms; NaN for a timed-out probe. */
        public final double[] rtts;

        Hop(int number, String address, double[] rtts) {
            this.number = number;
            this.address = address;
            this.rtts = rtts;
        }

        public double best() {
            return Traceroute.best(rtts);
        }
    }

    /** What the TCP check could establish about a proxy, optimizer or VPN. */
    public enum Verdict {
        /** The probe verifiably went through a proxy: its process accepted it, or it left via a tunnel adapter. */
        PROXY_VERIFIED,
        /** Something on this PC took the connection over, but the process could not be identified. */
        LOCAL_PROXY,
        /** The SYNs reached the server hop by hop with no local proxy or tunnel. */
        DIRECT,
        /** Nothing could be verified (no TCP reply, or TCP probes not available here). */
        UNVERIFIED
    }

    /** Result of the TCP check. */
    public static final class TcpResult {
        public final Verdict verdict;
        /** One or two sentences explaining the verdict. */
        public final String detail;
        /** TTL at which the server's TCP endpoint first answered, or -1 if unknown (intercepted, no reply). */
        public final int hopDistance;
        /** Handshake times at {@link #hopDistance} (or at the interception), NaN for no reply. */
        public final double[] rtts;
        /** Local process that accepted the probe instead of the server, e.g. "ExitLag.exe (pid 1234)", or null. */
        public final String acceptedBy;
        /** PID of {@link #acceptedBy}, or -1. */
        public final int acceptedPid;
        /** Short name of the verified proxy ("ExitLag", "NoPing", a process or adapter name), or null. */
        public final String proxyName;
        public final String probeAdapter;
        /** Remote TCP endpoints held by the proxy process: candidates for its relays or tunnel server. */
        public final List<String> proxyEndpoints;

        TcpResult(Verdict verdict, String detail, int hopDistance, double[] rtts, String acceptedBy,
                  String probeAdapter, List<String> proxyEndpoints) {
            this(verdict, detail, hopDistance, rtts, acceptedBy, null, probeAdapter, proxyEndpoints);
        }

        TcpResult(Verdict verdict, String detail, int hopDistance, double[] rtts, String acceptedBy,
                  String proxyName, String probeAdapter, List<String> proxyEndpoints) {
            this.verdict = verdict;
            this.detail = detail;
            this.hopDistance = hopDistance;
            this.rtts = rtts;
            this.acceptedBy = acceptedBy;
            this.acceptedPid = pidOf(acceptedBy);
            this.proxyName = proxyName;
            this.probeAdapter = probeAdapter;
            this.proxyEndpoints = Collections.unmodifiableList(new ArrayList<String>(proxyEndpoints));
        }

        public double best() {
            return Traceroute.best(rtts);
        }
    }

    public final String destination;
    public final int port;
    private final List<Hop> hops = new ArrayList<Hop>();
    private volatile boolean icmpRunning;
    private volatile boolean tcpRunning;
    private volatile boolean cancelled;
    private volatile String error;
    private volatile Process process;
    private volatile TcpResult tcp;
    private volatile String tcpStatus = "waiting";

    public Traceroute(String destination, int port) {
        this.destination = destination;
        this.port = port;
    }

    public boolean running() {
        return icmpRunning || tcpRunning;
    }

    public boolean icmpRunning() {
        return icmpRunning;
    }

    /** Error from the ICMP traceroute, or null. */
    public String error() {
        return error;
    }

    /** TCP check result, or null while it is still running. */
    public TcpResult tcp() {
        return tcp;
    }

    /** Progress text of the TCP check while {@link #tcp()} is null. */
    public String tcpStatus() {
        return tcpStatus;
    }

    /** ICMP hops (the system traceroute). Never a proxy's route. */
    public List<Hop> hops() {
        synchronized (hops) {
            return Collections.unmodifiableList(new ArrayList<Hop>(hops));
        }
    }

    public void start() {
        icmpRunning = true;
        tcpRunning = true;
        daemon(new Runnable() {
            @Override
            public void run() {
                try {
                    runIcmp();
                } finally {
                    icmpRunning = false;
                }
            }
        }, "SeekerNetDiagnostics-traceroute");
        daemon(new Runnable() {
            @Override
            public void run() {
                try {
                    tcp = runTcp();
                } catch (Throwable t) {
                    tcp = new TcpResult(Verdict.UNVERIFIED, "TCP check failed (" + t
                            + "); proxy routing could not be verified.", -1, new double[0], null, null,
                            Collections.<String>emptyList());
                } finally {
                    tcpRunning = false;
                }
            }
        }, "SeekerNetDiagnostics-tcptrace");
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }

    /**
     * Compares the TCP hop distance with the ICMP trace's length, once both are done; null when there is nothing
     * to say. Differing lengths mean the two kinds of traffic do not take the same path.
     */
    public String pathComparison() {
        TcpResult t = tcp;
        if (t == null || t.hopDistance < 1 || icmpRunning) {
            return null;
        }
        List<Hop> h = hops();
        if (h.isEmpty()) {
            return null;
        }
        Hop last = h.get(h.size() - 1);
        if (last.address == null || !last.address.equals(destination)) {
            return "ICMP trace did not reach the server; TCP reached it at hop " + t.hopDistance + ".";
        }
        if (last.number == t.hopDistance) {
            return "TCP and ICMP both reach the server at hop " + t.hopDistance + ".";
        }
        return "TCP reaches the server at hop " + t.hopDistance + " but ICMP at hop " + last.number
                + ": the two take different paths.";
    }

    // ------------------------------------------------------------------ TCP check

    private TcpResult runTcp() throws Exception {
        if (!WinTcpTrace.available()) {
            return assess(Probes.UNAVAILABLE, false, null, null, "", false, -1, new double[0],
                    Collections.<String>emptyList(), port);
        }
        InetAddress dest = InetAddress.getByName(destination); // literal, no lookup
        boolean destPrivate = DnsResolver.isLocalOrPrivate(destination);

        tcpStatus = "probing TTL 1";
        WinTcpTrace.Probe first = reach(dest, 1);
        if (cancelled) {
            return null;
        }
        String adapter = adapterOf(first.localAddress);
        if (first.kind == WinTcpTrace.Probe.REACHED && !destPrivate) {
            // a SYN with TTL 1 cannot reach a remote host: something on this PC answered it
            double[] rtts = rtts(dest, 1, first);
            return assess(Probes.REACHED, true, first.acceptedBy, adapter, WinTcpTrace.runningProxies(), destPrivate,
                    -1, rtts, WinTcpTrace.proxyEndpoints(pidOf(first.acceptedBy)), port);
        }
        int lo = 1;
        int hi;
        WinTcpTrace.Probe top;
        if (first.kind == WinTcpTrace.Probe.REACHED) {
            hi = 1;
            top = first;
        } else {
            tcpStatus = "probing TTL " + MAX_TTL;
            top = reach(dest, MAX_TTL);
            hi = MAX_TTL;
        }
        if (cancelled) {
            return null;
        }
        if (adapter == null) {
            adapter = adapterOf(top.localAddress);
        }
        String running = WinTcpTrace.runningProxies();
        if (top.kind != WinTcpTrace.Probe.REACHED) {
            return assess(Probes.NO_REPLY, false, null, adapter, running, destPrivate, -1, new double[0],
                    Collections.<String>emptyList(), port);
        }
        String acceptedBy = top.acceptedBy;
        // binary search: the server answers at every TTL >= its hop distance
        while (hi - lo > 1 && !cancelled) {
            int mid = (lo + hi) >>> 1;
            tcpStatus = "probing TTL " + mid;
            WinTcpTrace.Probe p = reach(dest, mid);
            if (p.kind == WinTcpTrace.Probe.REACHED) {
                hi = mid;
                if (acceptedBy == null) {
                    acceptedBy = p.acceptedBy;
                }
            } else {
                lo = mid;
            }
        }
        if (cancelled) {
            return null;
        }
        tcpStatus = "timing hop " + hi;
        double[] rtts = rtts(dest, hi, null);
        List<String> eps = acceptedBy != null || Proxies.tunnelAdapter(adapter)
                ? WinTcpTrace.proxyEndpoints(pidOf(acceptedBy)) : Collections.<String>emptyList();
        return assess(Probes.REACHED, false, acceptedBy, adapter, running, destPrivate, hi, rtts, eps, port);
    }

    /** One probe at {@code ttl}; a non-answer is retried once so a lost SYN does not skew the search. */
    private WinTcpTrace.Probe reach(InetAddress dest, int ttl) {
        WinTcpTrace.Probe p = WinTcpTrace.probe(dest, port, ttl, TCP_TIMEOUT_MS);
        if (p.kind != WinTcpTrace.Probe.REACHED && !cancelled) {
            p = WinTcpTrace.probe(dest, port, ttl, TCP_TIMEOUT_MS);
        }
        return p;
    }

    /** Three handshake times at {@code ttl}, reusing {@code first} as one of them when given. */
    private double[] rtts(InetAddress dest, int ttl, WinTcpTrace.Probe first) {
        double[] out = new double[3];
        for (int i = 0; i < out.length; i++) {
            WinTcpTrace.Probe p = i == 0 && first != null ? first : WinTcpTrace.probe(dest, port, ttl, TCP_TIMEOUT_MS);
            out[i] = p.kind == WinTcpTrace.Probe.REACHED ? p.rttMs : Double.NaN;
        }
        return out;
    }

    private static String adapterOf(String localAddress) {
        if (localAddress == null || localAddress.startsWith("0.") || "0:0:0:0:0:0:0:0".equals(localAddress)) {
            return null;
        }
        try {
            return PingMonitor.interfaceName(InetAddress.getByName(localAddress));
        } catch (Exception e) {
            return null;
        }
    }

    /** How the TCP probing went, independent of interception. */
    enum Probes {
        UNAVAILABLE, NO_REPLY, REACHED
    }

    /** PID from "Name.exe (pid 1234)", or -1. */
    static int pidOf(String acceptedBy) {
        if (acceptedBy == null) {
            return -1;
        }
        java.util.regex.Matcher m = Pattern.compile("\\(pid (\\d+)\\)").matcher(acceptedBy);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Turns TCP probe facts into a verdict. A proxy is reported only on positive evidence: the process that accepted
     * the probe was identified, or the probe's adapter is a VPN/tunnel adapter. Merely having an optimizer running,
     * or using the game port, proves nothing.
     *
     * @param answeredAtTtl1 a TTL 1 SYN to a non-private address got a handshake, so it was answered on this PC
     * @param runningProxies known optimizers/VPNs currently running ("" if none)
     */
    static TcpResult assess(Probes probes, boolean answeredAtTtl1, String acceptedBy, String adapter,
                            String runningProxies, boolean destPrivate, int hopDistance, double[] rtts,
                            List<String> proxyEndpoints, int port) {
        String p = ":" + port;
        if (probes == Probes.UNAVAILABLE) {
            return new TcpResult(Verdict.UNVERIFIED, "TCP probes are only available on Windows here, so proxy "
                    + "routing could not be verified. The hops below are the ICMP path.", -1, rtts, null, adapter,
                    proxyEndpoints);
        }
        if (probes == Probes.NO_REPLY) {
            return new TcpResult(Verdict.UNVERIFIED, "No TCP reply on " + p + " within " + MAX_TTL
                    + " hops; proxy routing could not be verified.", -1, rtts, null, adapter, proxyEndpoints);
        }
        boolean tunnel = Proxies.tunnelAdapter(adapter);
        boolean intercepted = acceptedBy != null || answeredAtTtl1;
        String eps = proxyEndpoints.isEmpty() ? ""
                : " Its open TCP connections (relay candidates, may include its own servers): "
                + join(proxyEndpoints, 4) + ".";
        if (intercepted) {
            if (acceptedBy != null) {
                String name = Proxies.label(acceptedBy);
                return new TcpResult(Verdict.PROXY_VERIFIED, "Verified: TCP" + p + " is taken over on this PC by "
                        + acceptedBy + ". Its real route continues inside " + name + "'s tunnel and is not in the "
                        + "ICMP hop list." + eps, -1, rtts, acceptedBy, name, adapter, proxyEndpoints);
            }
            if (tunnel) {
                return new TcpResult(Verdict.PROXY_VERIFIED, "Verified: TCP" + p + " is answered through the tunnel "
                        + "adapter " + adapter + ", so the route beyond it is hidden." + eps, -1, rtts, null, adapter,
                        adapter, proxyEndpoints);
            }
            return new TcpResult(Verdict.LOCAL_PROXY, "TCP" + p + " is answered on this PC by an unidentified local "
                    + "proxy or tunnel, so the route beyond it is hidden. Which proxy could not be verified.", -1, rtts,
                    null, adapter, proxyEndpoints);
        }
        if (tunnel) {
            return new TcpResult(Verdict.PROXY_VERIFIED, "Verified: TCP" + p + " leaves through the tunnel adapter "
                    + adapter + " and reaches the server " + hopDistance + " hops away (hops inside the tunnel are "
                    + "hidden)." + eps, hopDistance, rtts, null, adapter, adapter, proxyEndpoints);
        }
        String note = runningProxies == null || runningProxies.isEmpty() ? ""
                : " " + runningProxies + (runningProxies.contains(",") ? " are" : " is")
                + " running but did not take this connection.";
        String where = destPrivate ? " (a local-network server)" : "";
        return new TcpResult(Verdict.DIRECT, "TCP" + p + " reaches the server" + where + " " + hopDistance
                + " hops away with no local proxy or tunnel." + note, hopDistance, rtts, null, adapter,
                proxyEndpoints);
    }

    private static String join(List<String> items, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < max; i++) {
            sb.append(i == 0 ? "" : ", ").append(items.get(i));
        }
        if (items.size() > max) {
            sb.append(" (+").append(items.size() - max).append(" more)");
        }
        return sb.toString();
    }

    static double best(double[] rtts) {
        double b = Double.NaN;
        for (double v : rtts) {
            if (!Double.isNaN(v) && (Double.isNaN(b) || v < b)) {
                b = v;
            }
        }
        return b;
    }

    // ------------------------------------------------------------------ ICMP hops (system traceroute)

    private void runIcmp() {
        List<String> cmd = new ArrayList<String>();
        if (WinTcpTrace.windows()) {
            Collections.addAll(cmd, "tracert", "-d", "-h", "30", "-w", "1000", destination);
        } else {
            Collections.addAll(cmd, "traceroute", "-n", "-q", "3", "-w", "1", "-m", "30", destination);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            if (cancelled) {
                process.destroy();
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    Charset.defaultCharset()));
            String line;
            while ((line = in.readLine()) != null) {
                Hop h = parse(line);
                if (h != null) {
                    synchronized (hops) {
                        hops.add(h);
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            error = "Could not run " + cmd.get(0) + ": " + e.getMessage();
        }
    }

    /** Parses one output line of tracert/traceroute, or returns null if it is not a hop line. */
    static Hop parse(String line) {
        Matcher m = HOP.matcher(line);
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        String rest = m.group(2);
        if (n < 1 || n > 64 || !(rest.contains("ms") || rest.contains("*"))) {
            return null; // e.g. the "traceroute to x (y), 30 hops max" header
        }
        List<Double> times = new ArrayList<Double>();
        Matcher t = MS.matcher(rest);
        String withoutTimes = MS.matcher(rest).replaceAll(" ");
        while (t.find()) {
            String v = t.group(1).replace("<", "").replace(',', '.').trim();
            times.add(Double.parseDouble(v));
        }
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '*') {
                times.add(Double.NaN);
            }
        }
        String addr = null;
        Matcher ip = IP.matcher(withoutTimes);
        while (ip.find()) {
            addr = ip.group(1);
        }
        double[] arr = new double[Math.min(3, times.size())];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = times.get(i);
        }
        return new Hop(n, addr, arr);
    }
}
