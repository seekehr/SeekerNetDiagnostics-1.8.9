package com.seekehr.netdiagnostics.helper;

import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace.SOCKET;
import com.sun.jna.Memory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace;

/**
 * Packet capture on every active IPv4 interface with raw {@code SIO_RCVALL} sockets (administrator only, no
 * Npcap). It does two jobs:
 * <ul>
 * <li>matches ICMP Time Exceeded / Unreachable and direct TCP/UDP answers to registered probes, with timestamps
 * taken when the packet is read;</li>
 * <li>counts packets per remote endpoint for sockets owned by ExitLag, which identifies the relays ExitLag is
 * actually sending traffic to.</li>
 * </ul>
 * Loopback traffic is not captured, and IPv6 is not captured.
 */
final class Capture {
    static final int HOP = 1;
    static final int REACHED = 2;
    static final int UNREACHABLE = 3;

    /** One answer to a probe. */
    static final class Reply {
        final String from;
        final int kind;
        final long nanos;

        Reply(String from, int kind, long nanos) {
            this.from = from;
            this.kind = kind;
            this.nanos = nanos;
        }
    }

    /** Packet/byte counters of one remote endpoint of an ExitLag-owned socket. */
    static final class Flow {
        final int protocol;
        final String remote;
        final int remotePort;
        final AtomicLong packetsOut = new AtomicLong();
        final AtomicLong packetsIn = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        volatile long lastSeen;
        volatile int pid;

        Flow(int protocol, String remote, int remotePort) {
            this.protocol = protocol;
            this.remote = remote;
            this.remotePort = remotePort;
        }

        String key() {
            return (protocol == Packets.TCP ? "TCP " : "UDP ") + remote + ":" + remotePort;
        }
    }

    private final List<SOCKET> sockets = new ArrayList<SOCKET>();
    private final List<String> interfaces = new ArrayList<String>();
    private final Map<String, Long> pending = new ConcurrentHashMap<String, Long>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<String, Reply>();
    private final Map<String, Flow> flows = new ConcurrentHashMap<String, Flow>();
    /** (protocol << 16 | local port) of sockets owned by ExitLag, mapped to the owning pid. */
    private volatile Map<Integer, Integer> owned = Collections.emptyMap();
    private volatile Set<String> locals = Collections.emptySet();
    /** PIDs of ExitLag processes, for owner lookups of new connections. */
    private volatile Set<Integer> exitLagPids = Collections.emptySet();
    /**
     * TCP local ports found to belong to ExitLag when their SYN was seen: {pid, time}. Short connections open and
     * close between the periodic socket-table scans, so they are resolved here as they start.
     */
    private final Map<Integer, long[]> synOwned = new ConcurrentHashMap<Integer, long[]>();
    private final BlockingQueue<Integer> lookups = new LinkedBlockingQueue<Integer>(4096);
    private final AtomicLong packets = new AtomicLong();
    private volatile boolean closed;

    private Capture() {
    }

    /** Opens capture on every up, non-loopback IPv4 address. Throws if none could be opened. */
    static Capture start() throws RawSock.SockException {
        Capture c = new Capture();
        RawSock.SockException first = null;
        for (InetAddress a : localIpv4()) {
            try {
                c.sockets.add(RawSock.openCapture(a));
                c.interfaces.add(a.getHostAddress());
            } catch (RawSock.SockException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (c.sockets.isEmpty()) {
            throw first != null ? first : new RawSock.SockException("capture (no IPv4 interface)", 0);
        }
        c.refreshLocals();
        Thread resolver = new Thread(new Runnable() {
            @Override
            public void run() {
                c.resolveLoop();
            }
        }, "SND-owner-resolver");
        resolver.setDaemon(true);
        resolver.start();
        for (int i = 0; i < c.sockets.size(); i++) {
            final SOCKET s = c.sockets.get(i);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    c.loop(s);
                }
            }, "SND-capture-" + c.interfaces.get(i));
            t.setDaemon(true);
            t.start();
        }
        return c;
    }

    List<String> interfaces() {
        return Collections.unmodifiableList(interfaces);
    }

    long packetCount() {
        return packets.get();
    }

    void close() {
        closed = true;
        for (SOCKET s : sockets) {
            RawSock.ws2().closesocket(s);
        }
    }

    static List<InetAddress> localIpv4() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        out.add(a);
                    }
                }
            }
        } catch (Exception ignored) {
            // none
        }
        return out;
    }

    void refreshLocals() {
        Set<String> s = new HashSet<String>();
        for (InetAddress a : localIpv4()) {
            s.add(a.getHostAddress());
        }
        locals = s;
    }

    /** Socket ownership from the periodic table scan, plus the current ExitLag PIDs. */
    void setOwned(Map<Integer, Integer> map, Set<Integer> pids) {
        owned = new HashMap<Integer, Integer>(map);
        exitLagPids = new HashSet<Integer>(pids);
        long cutoff = System.currentTimeMillis() - 300000;
        for (Map.Entry<Integer, long[]> e : synOwned.entrySet()) {
            if (e.getValue()[1] < cutoff) {
                synOwned.remove(e.getKey());
            }
        }
    }

    private Integer ownerOf(int protocol, int localPort) {
        int k = ownerKey(protocol, localPort);
        Integer pid = owned.get(k);
        if (pid == null) {
            long[] s = synOwned.get(k);
            if (s != null) {
                s[1] = System.currentTimeMillis();
                pid = (int) s[0];
            }
        }
        return pid;
    }

    /** Resolves owners of new outbound TCP connections in batches (one table query per batch). */
    private void resolveLoop() {
        List<Integer> batch = new ArrayList<Integer>();
        while (!closed) {
            try {
                Integer first = lookups.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                lookups.drainTo(batch);
                Set<Integer> pids = exitLagPids;
                if (pids.isEmpty()) {
                    continue;
                }
                Set<Integer> want = new HashSet<Integer>(batch);
                for (WinTcpTrace.TcpRow r : WinTcpTrace.tcpTable(WinTcpTrace.AF_INET)) {
                    if (want.contains(r.localPort) && pids.contains(r.pid)) {
                        synOwned.put(ownerKey(Packets.TCP, r.localPort), new long[]{r.pid, System.currentTimeMillis()});
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // keep resolving
            }
        }
    }

    static int ownerKey(int protocol, int localPort) {
        return protocol << 16 | localPort;
    }

    // ------------------------------------------------------------------ probes

    void register(String key) {
        replies.remove(key);
        pending.put(key, System.nanoTime());
    }

    Reply reply(String key) {
        return replies.get(key);
    }

    void unregister(String key) {
        pending.remove(key);
        replies.remove(key);
    }

    // ------------------------------------------------------------------ flows

    List<Flow> flows() {
        return new ArrayList<Flow>(flows.values());
    }

    void forget(String flowKey) {
        flows.remove(flowKey);
    }

    // ------------------------------------------------------------------ capture loop

    private void loop(SOCKET s) {
        Memory buf = new Memory(65536);
        byte[] arr = new byte[65536];
        while (!closed) {
            int n = RawSock.recv(s, buf);
            if (n < 0) {
                if (closed) {
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            if (n == 0) {
                continue;
            }
            long now = System.nanoTime();
            packets.incrementAndGet();
            buf.read(0, arr, 0, n);
            Packets.Ip4 p = Packets.parse(arr, n);
            if (p != null) {
                handle(p, now);
            }
        }
    }

    void handle(Packets.Ip4 p, long now) {
        if (!pending.isEmpty()) {
            String key = Packets.replyKey(p);
            if (key != null && pending.containsKey(key) && !replies.containsKey(key)) {
                int kind;
                if (p.protocol == Packets.ICMP) {
                    kind = p.icmpType == 11 ? HOP : p.src.equals(p.innerDst) ? REACHED : UNREACHABLE;
                } else {
                    kind = REACHED;
                }
                replies.put(key, new Reply(p.src, kind, now));
            }
        }
        if (p.protocol != Packets.TCP && p.protocol != Packets.UDP || exitLagPids.isEmpty()) {
            return;
        }
        boolean out = locals.contains(p.src);
        boolean in = !out && locals.contains(p.dst);
        if (!out && !in) {
            return;
        }
        int localPort = out ? p.srcPort : p.dstPort;
        if (out && p.protocol == Packets.TCP && (p.tcpFlags & (Packets.TCP_SYN | Packets.TCP_ACK)) == Packets.TCP_SYN
                && !owned.containsKey(ownerKey(Packets.TCP, localPort))) {
            lookups.offer(localPort); // new outbound connection: find out whose it is while it exists
        }
        Integer pid = ownerOf(p.protocol, localPort);
        if (pid == null) {
            return;
        }
        String remote = out ? p.dst : p.src;
        int remotePort = out ? p.dstPort : p.srcPort;
        String key = (p.protocol == Packets.TCP ? "TCP " : "UDP ") + remote + ":" + remotePort;
        Flow f = flows.get(key);
        if (f == null) {
            f = new Flow(p.protocol, remote, remotePort);
            Flow prev = ((ConcurrentHashMap<String, Flow>) flows).putIfAbsent(key, f);
            if (prev != null) {
                f = prev;
            }
        }
        f.pid = pid;
        (out ? f.packetsOut : f.packetsIn).incrementAndGet();
        f.bytes.addAndGet(p.totalLength);
        f.lastSeen = System.currentTimeMillis();
    }
}
