package com.seekehr.netdiagnostics.core.monitor;

import com.sun.jna.IntegerType;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unprivileged TCP SYN probes with a chosen TTL on Windows, through Winsock via JNA (JNA 3.4.0 ships with
 * Minecraft 1.8.9). No raw sockets, so no administrator rights are needed.
 *
 * <p>A normal TCP socket gets {@code IP_TTL} set and connects; a completed handshake or a reset means the SYN
 * reached a TCP endpoint within that many hops. Routers' ICMP Time Exceeded replies are NOT seen: Windows does not
 * pass them to TCP sockets (and its firewall has no default inbound rule for ICMPv4 Time Exceeded), so this finds
 * the hop distance and RTT of the TCP path, not the routers on it. {@code TCP_FAIL_CONNECT_ON_ICMP_ERROR} is set
 * best-effort so a Destination Unreachable, where Windows does deliver it, ends a probe early.
 *
 * <p>Also reads the system TCP table ({@code GetExtendedTcpTable}) to see which local process, if any, accepted a
 * probe, and which remote endpoints an ExitLag process holds.
 */
public final class WinTcpTrace {
    public static final int AF_INET = 2;
    public static final int AF_INET6 = 23;
    private static final int SOCK_STREAM = 1;
    private static final int IPPROTO_IP = 0;
    private static final int IPPROTO_TCP = 6;
    private static final int IPPROTO_IPV6 = 41;
    private static final int IP_TTL = 4;
    private static final int IPV6_UNICAST_HOPS = 4;
    private static final int TCP_FAIL_CONNECT_ON_ICMP_ERROR = 18;
    private static final int TCP_ICMP_ERROR_INFO = 19;
    private static final int SOL_SOCKET = 0xFFFF;
    private static final int SO_ERROR = 0x1007;
    private static final int FIONBIO = 0x8004667E;
    private static final int WSAEWOULDBLOCK = 10035;
    private static final int WSAECONNREFUSED = 10061;
    private static final int TCP_TABLE_OWNER_PID_ALL = 5;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /** Winsock SOCKET: an unsigned pointer-sized handle; INVALID_SOCKET reads as -1 here. */
    public static final class SOCKET extends IntegerType {
        public SOCKET() {
            super(Pointer.SIZE);
        }
    }

    public interface Ws2 extends StdCallLibrary {
        int WSAStartup(short version, byte[] data);

        SOCKET socket(int af, int type, int protocol);

        int closesocket(SOCKET s);

        int setsockopt(SOCKET s, int level, int name, byte[] value, int len);

        int getsockopt(SOCKET s, int level, int name, byte[] value, IntByReference len);

        int ioctlsocket(SOCKET s, int cmd, IntByReference arg);

        int connect(SOCKET s, byte[] addr, int len);

        int getsockname(SOCKET s, byte[] addr, IntByReference len);

        /** timeval is two 32-bit longs on Windows. */
        int select(int nfds, Pointer read, Pointer write, Pointer except, int[] timeval);

        int WSAGetLastError();
    }

    interface IpHlp extends StdCallLibrary {
        int GetExtendedTcpTable(Pointer table, IntByReference size, boolean order, int af, int tableClass, int reserved);

        int GetExtendedUdpTable(Pointer table, IntByReference size, boolean order, int af, int tableClass, int reserved);
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int access, boolean inherit, int pid);

        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] name, IntByReference size);

        boolean CloseHandle(Pointer handle);

        int GetCurrentProcessId();
    }

    /** One probe's outcome. */
    static final class Probe {
        static final int TIMEOUT = 0;
        /** A router answered with ICMP Time Exceeded (rare: Windows usually does not deliver these to TCP). */
        static final int HOP = 1;
        /** The SYN reached a TCP endpoint: handshake completed or reset. */
        static final int REACHED = 2;
        /** A router answered with ICMP Destination Unreachable. */
        static final int UNREACHABLE = 3;
        static final int FAILED = 4;

        int kind = TIMEOUT;
        String from;
        double rttMs = Double.NaN;
        /** Probe's own local address (no port), when known. */
        String localAddress;
        /** For a completed handshake: the local process that accepted it instead of the server, or null. */
        String acceptedBy;
        String error;
    }

    private static volatile Boolean loaded;
    private static Ws2 ws2;

    /** The loaded Winsock binding; only valid after {@link #available()} returned true. */
    public static Ws2 ws2() {
        return ws2;
    }
    private static IpHlp iphlp;
    private static Kernel32 kernel32;

    private WinTcpTrace() {
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** True when JNA and the Winsock/IP Helper libraries load. Does not prove the ICMP options are supported. */
    public static synchronized boolean available() {
        if (loaded == null) {
            boolean ok = false;
            if (windows()) {
                try {
                    ws2 = (Ws2) Native.loadLibrary("ws2_32", Ws2.class);
                    iphlp = (IpHlp) Native.loadLibrary("iphlpapi", IpHlp.class);
                    kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
                    ws2.WSAStartup((short) 0x0202, new byte[512]);
                    ok = true;
                } catch (Throwable t) {
                    ok = false;
                }
            }
            loaded = ok;
        }
        return loaded;
    }

    /** Sends one SYN to {@code dest:port} with the given TTL and waits up to {@code timeoutMs} for any answer. */
    static Probe probe(InetAddress dest, int port, int ttl, int timeoutMs) {
        boolean v6 = dest instanceof Inet6Address;
        Probe p = new Probe();
        SOCKET s = ws2.socket(v6 ? AF_INET6 : AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (s.longValue() == -1L) {
            p.kind = Probe.FAILED;
            p.error = "socket() failed: " + Native.getLastError();
            return p;
        }
        try {
            // best-effort (Windows 10 1703+): end the probe early on an ICMP error Windows does deliver
            ws2.setsockopt(s, IPPROTO_TCP, TCP_FAIL_CONNECT_ON_ICMP_ERROR, intBytes(1), 4);
            if (ws2.setsockopt(s, v6 ? IPPROTO_IPV6 : IPPROTO_IP, v6 ? IPV6_UNICAST_HOPS : IP_TTL, intBytes(ttl), 4)
                    != 0) {
                p.kind = Probe.FAILED;
                p.error = "could not set TTL: " + Native.getLastError();
                return p;
            }
            ws2.ioctlsocket(s, FIONBIO, new IntByReference(1));
            byte[] sa = sockaddr(dest, port);
            long t0 = System.nanoTime();
            if (ws2.connect(s, sa, sa.length) != 0) {
                int e = Native.getLastError();
                if (e != WSAEWOULDBLOCK) {
                    p.kind = Probe.FAILED;
                    p.error = "connect failed: " + e;
                    return p;
                }
            }
            p.localAddress = localAddress(s);
            Memory w = fdSet(s);
            Memory x = fdSet(s);
            int n = ws2.select(0, null, w, x, new int[]{timeoutMs / 1000, (timeoutMs % 1000) * 1000});
            double rtt = (System.nanoTime() - t0) / 1e6;
            if (n == 0) {
                return p; // timeout
            }
            if (n < 0) {
                p.kind = Probe.FAILED;
                p.error = "select failed: " + Native.getLastError();
                return p;
            }
            p.rttMs = rtt;
            p.localAddress = localAddress(s);
            if (w.getInt(0) > 0) {
                p.kind = Probe.REACHED;
                p.from = dest.getHostAddress();
                p.acceptedBy = localAcceptor(s, v6);
                return p;
            }
            int err = getInt(s, SOL_SOCKET, SO_ERROR);
            if (err == WSAECONNREFUSED) {
                p.kind = Probe.REACHED; // the destination host answered with a reset
                p.from = dest.getHostAddress();
                return p;
            }
            IcmpError ie = icmpError(s);
            if (ie == null || ie.from == null) {
                p.kind = Probe.FAILED;
                p.error = "connect error " + err;
                return p;
            }
            p.from = ie.from;
            p.kind = ie.timeExceeded() ? Probe.HOP : Probe.UNREACHABLE;
            return p;
        } finally {
            ws2.closesocket(s);
        }
    }

    // ------------------------------------------------------------------ ICMP error info

    /** Parsed ICMP_ERROR_INFO: source of the ICMP message, its protocol (1 = ICMPv4, 58 = ICMPv6), type and code. */
    static final class IcmpError {
        final String from;
        final int protocol;
        final int type;
        final int code;

        IcmpError(String from, int protocol, int type, int code) {
            this.from = from;
            this.protocol = protocol;
            this.type = type;
            this.code = code;
        }

        boolean timeExceeded() {
            return protocol == 58 ? type == 3 : type == 11;
        }
    }

    private static IcmpError icmpError(SOCKET s) {
        byte[] buf = new byte[64];
        IntByReference len = new IntByReference(buf.length);
        if (ws2.getsockopt(s, IPPROTO_TCP, TCP_ICMP_ERROR_INFO, buf, len) != 0) {
            return null;
        }
        return parseIcmpError(buf);
    }

    /** ICMP_ERROR_INFO: SOCKADDR_INET (28 bytes), IPPROTO (int), UINT8 type, UINT8 code. */
    static IcmpError parseIcmpError(byte[] b) {
        String from = sockaddrHost(b, 0);
        int protocol = le32(b, 28);
        return new IcmpError(from, protocol, b[32] & 0xFF, b[33] & 0xFF);
    }

    // ------------------------------------------------------------------ who accepted the connection

    /**
     * After a completed handshake: the image name of another local process whose TCP connection's remote end is
     * this socket. That process accepted our SYN on this PC (a local proxy) rather than the server. Null if none.
     */
    private static String localAcceptor(SOCKET s, boolean v6) {
        byte[] sa = new byte[64];
        IntByReference len = new IntByReference(sa.length);
        if (ws2.getsockname(s, sa, len) != 0) {
            return null;
        }
        String host = sockaddrHost(sa, 0);
        int port = ((sa[2] & 0xFF) << 8) | (sa[3] & 0xFF);
        int self = kernel32.GetCurrentProcessId();
        for (TcpRow r : tcpTable(v6 ? AF_INET6 : AF_INET)) {
            if (r.pid != self && r.remotePort == port && (r.remote.equals(host) || isLoopback(r.remote))) {
                String name = processName(r.pid);
                return (name == null ? "unknown process" : name) + " (pid " + r.pid + ")";
            }
        }
        return null;
    }

    public static final class TcpRow {
        public final String local;
        public final int localPort;
        public final String remote;
        public final int remotePort;
        public final int state;
        public final int pid;

        TcpRow(String local, int localPort, String remote, int remotePort, int state, int pid) {
            this.local = local;
            this.localPort = localPort;
            this.remote = remote;
            this.remotePort = remotePort;
            this.state = state;
            this.pid = pid;
        }
    }

    /** All TCP connections of one family with their owning process (MIB_TCPTABLE_OWNER_PID / MIB_TCP6TABLE_...). */
    public static List<TcpRow> tcpTable(int af) {
        List<TcpRow> out = new ArrayList<TcpRow>();
        try {
            IntByReference size = new IntByReference(0);
            Memory m = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                int rc = iphlp.GetExtendedTcpTable(m, size, false, af, TCP_TABLE_OWNER_PID_ALL, 0);
                if (rc == 0 && m != null) {
                    break;
                }
                if (rc != ERROR_INSUFFICIENT_BUFFER && rc != 0) {
                    return out;
                }
                m = new Memory(size.getValue() + 1024);
                size.setValue((int) m.size());
            }
            if (m == null) {
                return out;
            }
            int n = m.getInt(0);
            byte[] raw = m.getByteArray(0, (int) Math.min(m.size(), 4 + (long) n * (af == AF_INET6 ? 56 : 24)));
            if (af == AF_INET6) {
                for (int i = 0; i < n; i++) {
                    int o = 4 + i * 56;
                    out.add(new TcpRow(ip6(raw, o), port(raw, o + 20), ip6(raw, o + 24), port(raw, o + 44),
                            le32(raw, o + 48), le32(raw, o + 52)));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    int o = 4 + i * 24;
                    out.add(new TcpRow(ip4(raw, o + 4), port(raw, o + 8), ip4(raw, o + 12), port(raw, o + 16),
                            le32(raw, o), le32(raw, o + 20)));
                }
            }
        } catch (Throwable t) {
            // diagnostics only
        }
        return out;
    }

    /** A UDP socket's local endpoint and owning process (MIB_UDPTABLE_OWNER_PID, IPv4). */
    public static final class UdpRow {
        public final String local;
        public final int localPort;
        public final int pid;

        UdpRow(String local, int localPort, int pid) {
            this.local = local;
            this.localPort = localPort;
            this.pid = pid;
        }
    }

    /** All IPv4 UDP sockets with their owning process. UDP has no remote address in this table. */
    public static List<UdpRow> udpTable() {
        List<UdpRow> out = new ArrayList<UdpRow>();
        try {
            IntByReference size = new IntByReference(0);
            Memory m = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                int rc = iphlp.GetExtendedUdpTable(m, size, false, AF_INET, 1 /* UDP_TABLE_OWNER_PID */, 0);
                if (rc == 0 && m != null) {
                    break;
                }
                if (rc != ERROR_INSUFFICIENT_BUFFER && rc != 0) {
                    return out;
                }
                m = new Memory(size.getValue() + 1024);
                size.setValue((int) m.size());
            }
            if (m == null) {
                return out;
            }
            int n = m.getInt(0);
            byte[] raw = m.getByteArray(0, (int) Math.min(m.size(), 4 + (long) n * 12));
            for (int i = 0; i < n; i++) {
                int o = 4 + i * 12;
                out.add(new UdpRow(ip4(raw, o), port(raw, o + 4), le32(raw, o + 8)));
            }
        } catch (Throwable t) {
            // diagnostics only
        }
        return out;
    }

    private static final Map<Integer, String> NAMES = new HashMap<Integer, String>();

    /** Forgets cached process names (PIDs get reused); for long-running callers. */
    public static synchronized void clearProcessNames() {
        NAMES.clear();
    }

    /** File name of a process's executable, e.g. "ExitLag.exe", or null when it cannot be read. */
    public static synchronized String processName(int pid) {
        if (NAMES.containsKey(pid)) {
            return NAMES.get(pid);
        }
        String name = null;
        Pointer h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h != null) {
            try {
                char[] buf = new char[1024];
                IntByReference len = new IntByReference(buf.length);
                if (kernel32.QueryFullProcessImageNameW(h, 0, buf, len)) {
                    String path = new String(buf, 0, len.getValue());
                    name = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
                }
            } finally {
                kernel32.CloseHandle(h);
            }
        }
        NAMES.put(pid, name);
        return name;
    }

    /**
     * Remote endpoints ("ip:port") held by processes whose executable name contains "exitlag": candidates for
     * ExitLag's outer tunnel/relay. Only TCP is visible this way.
     */
    static List<String> exitLagEndpoints() {
        List<String> out = new ArrayList<String>();
        for (int af : new int[]{AF_INET, AF_INET6}) {
            for (TcpRow r : tcpTable(af)) {
                if (r.remotePort == 0 || isLoopback(r.remote) || "0.0.0.0".equals(r.remote) || "::".equals(r.remote)) {
                    continue;
                }
                String name = processName(r.pid);
                if (name != null && name.toLowerCase(Locale.ROOT).contains("exitlag")) {
                    String ep = (af == AF_INET6 ? "[" + r.remote + "]" : r.remote) + ":" + r.remotePort;
                    if (!out.contains(ep)) {
                        out.add(ep);
                    }
                }
            }
        }
        return out;
    }

    /** True when any running process that holds a TCP connection or listener has "exitlag" in its name. */
    static boolean exitLagRunning() {
        for (int af : new int[]{AF_INET, AF_INET6}) {
            for (TcpRow r : tcpTable(af)) {
                String name = processName(r.pid);
                if (name != null && name.toLowerCase(Locale.ROOT).contains("exitlag")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ byte helpers

    private static String localAddress(SOCKET s) {
        byte[] sa = new byte[64];
        IntByReference len = new IntByReference(sa.length);
        return ws2.getsockname(s, sa, len) == 0 ? sockaddrHost(sa, 0) : null;
    }

    private static int getInt(SOCKET s, int level, int name) {
        byte[] b = new byte[4];
        IntByReference len = new IntByReference(4);
        return ws2.getsockopt(s, level, name, b, len) == 0 ? le32(b, 0) : -1;
    }

    /** Windows fd_set holding one socket: u_int fd_count, then SOCKET fd_array[] aligned to pointer size. */
    public static Memory fdSet(SOCKET s) {
        Memory m = new Memory(Pointer.SIZE * 2);
        m.clear();
        m.setInt(0, 1);
        if (Pointer.SIZE == 8) {
            m.setLong(8, s.longValue());
        } else {
            m.setInt(4, s.intValue());
        }
        return m;
    }

    public static byte[] sockaddr(InetAddress a, int port) {
        byte[] ip = a.getAddress();
        byte[] sa = new byte[ip.length == 16 ? 28 : 16];
        int af = ip.length == 16 ? AF_INET6 : AF_INET;
        sa[0] = (byte) af;
        sa[1] = (byte) (af >> 8);
        sa[2] = (byte) (port >> 8);
        sa[3] = (byte) port;
        if (ip.length == 16) {
            System.arraycopy(ip, 0, sa, 8, 16);
            int scope = ((Inet6Address) a).getScopeId();
            sa[24] = (byte) scope;
            sa[25] = (byte) (scope >> 8);
            sa[26] = (byte) (scope >> 16);
            sa[27] = (byte) (scope >> 24);
        } else {
            System.arraycopy(ip, 0, sa, 4, 4);
        }
        return sa;
    }

    /** Address in a sockaddr_in / sockaddr_in6 / SOCKADDR_INET at {@code o}, or null for another family. */
    public static String sockaddrHost(byte[] b, int o) {
        int family = (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
        if (family == AF_INET) {
            return ip4(b, o + 4);
        }
        if (family == AF_INET6) {
            return ip6(b, o + 8);
        }
        return null;
    }

    private static String ip4(byte[] b, int o) {
        return (b[o] & 0xFF) + "." + (b[o + 1] & 0xFF) + "." + (b[o + 2] & 0xFF) + "." + (b[o + 3] & 0xFF);
    }

    private static String ip6(byte[] b, int o) {
        try {
            byte[] a = new byte[16];
            System.arraycopy(b, o, a, 0, 16);
            String s = InetAddress.getByAddress(a).getHostAddress();
            int pct = s.indexOf('%');
            return pct >= 0 ? s.substring(0, pct) : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** A port stored in network byte order in the low 16 bits of a DWORD. */
    private static int port(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    public static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    public static byte[] intBytes(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    public static boolean isLoopback(String ip) {
        return ip != null && (ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip));
    }
}
