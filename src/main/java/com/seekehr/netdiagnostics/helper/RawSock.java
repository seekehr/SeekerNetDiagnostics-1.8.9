package com.seekehr.netdiagnostics.helper;

import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace;
import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace.SOCKET;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.net.InetAddress;

/**
 * Winsock calls the elevated helper needs beyond {@link WinTcpTrace}: raw sockets with {@code SIO_RCVALL}
 * (receive every IPv4 packet on an interface, which needs administrator rights), UDP probes with a TTL, and
 * TCP probes from a known local port.
 */
final class RawSock {
    static final int SOCK_STREAM = 1;
    static final int SOCK_DGRAM = 2;
    static final int SOCK_RAW = 3;
    static final int IPPROTO_IP = 0;
    static final int IPPROTO_TCP = 6;
    static final int IPPROTO_UDP = 17;
    static final int IP_TTL = 4;
    static final int SOL_SOCKET = 0xFFFF;
    static final int SO_RCVBUF = 0x1002;
    static final int SO_RCVTIMEO = 0x1006;
    static final int SIO_RCVALL = 0x98000001;
    static final int FIONBIO = 0x8004667E;
    static final int RCVALL_ON = 1;
    static final int WSAEACCES = 10013;

    interface RawWs2 extends StdCallLibrary {
        int bind(SOCKET s, byte[] addr, int len);

        int recv(SOCKET s, Pointer buf, int len, int flags);

        int sendto(SOCKET s, byte[] buf, int len, int flags, byte[] to, int tolen);

        int WSAIoctl(SOCKET s, int code, IntByReference in, int inLen, Pointer out, int outLen,
                     IntByReference bytes, Pointer overlapped, Pointer completion);
    }

    private static RawWs2 raw;

    private RawSock() {
    }

    static synchronized RawWs2 raw() {
        if (raw == null) {
            if (!WinTcpTrace.available()) {
                throw new IllegalStateException("Winsock is not available");
            }
            raw = (RawWs2) Native.loadLibrary("ws2_32", RawWs2.class);
        }
        return raw;
    }

    static WinTcpTrace.Ws2 ws2() {
        raw();
        return WinTcpTrace.ws2();
    }

    /** Thrown with the Winsock error code when a socket call fails. */
    static final class SockException extends Exception {
        final int code;

        SockException(String what, int code) {
            super(what + " failed (Winsock error " + code + (code == WSAEACCES ? ": administrator rights needed" : "")
                    + ")");
            this.code = code;
        }
    }

    /** Opens a raw IPv4 socket on {@code local} that receives every packet on that interface (SIO_RCVALL). */
    static SOCKET openCapture(InetAddress local) throws SockException {
        WinTcpTrace.Ws2 w = ws2();
        SOCKET s = w.socket(WinTcpTrace.AF_INET, SOCK_RAW, IPPROTO_IP);
        if (s.longValue() == -1L) {
            throw new SockException("raw socket", Native.getLastError());
        }
        byte[] sa = WinTcpTrace.sockaddr(local, 0);
        if (raw().bind(s, sa, sa.length) != 0) {
            int e = Native.getLastError();
            w.closesocket(s);
            throw new SockException("bind " + local.getHostAddress(), e);
        }
        w.setsockopt(s, SOL_SOCKET, SO_RCVBUF, WinTcpTrace.intBytes(8 << 20), 4);
        w.setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, WinTcpTrace.intBytes(500), 4);
        IntByReference on = new IntByReference(RCVALL_ON);
        IntByReference bytes = new IntByReference(0);
        if (raw().WSAIoctl(s, SIO_RCVALL, on, 4, null, 0, bytes, null, null) != 0) {
            int e = Native.getLastError();
            w.closesocket(s);
            throw new SockException("SIO_RCVALL on " + local.getHostAddress(), e);
        }
        return s;
    }

    /** Blocking receive with the socket's 500 ms timeout; returns bytes read, 0 on timeout, -1 on error. */
    static int recv(SOCKET s, Memory buf) {
        int n = raw().recv(s, buf, (int) buf.size(), 0);
        if (n < 0) {
            int e = Native.getLastError();
            return e == 10060 /* WSAETIMEDOUT */ ? 0 : -1;
        }
        return n;
    }

    /**
     * A probe socket bound to an ephemeral local port, with its TTL set. TCP sockets are non-blocking so the
     * caller can start many connects at once.
     */
    static final class ProbeSocket {
        final SOCKET s;
        final int localPort;
        final boolean tcp;

        ProbeSocket(SOCKET s, int localPort, boolean tcp) {
            this.s = s;
            this.localPort = localPort;
            this.tcp = tcp;
        }

        void close() {
            ws2().closesocket(s);
        }
    }

    static ProbeSocket open(boolean tcp, int ttl) throws SockException {
        WinTcpTrace.Ws2 w = ws2();
        SOCKET s = w.socket(WinTcpTrace.AF_INET, tcp ? SOCK_STREAM : SOCK_DGRAM, tcp ? IPPROTO_TCP : IPPROTO_UDP);
        if (s.longValue() == -1L) {
            throw new SockException("socket", Native.getLastError());
        }
        try {
            byte[] any = new byte[16];
            any[0] = (byte) WinTcpTrace.AF_INET;
            if (raw().bind(s, any, any.length) != 0) {
                throw new SockException("bind", Native.getLastError());
            }
            if (w.setsockopt(s, IPPROTO_IP, IP_TTL, WinTcpTrace.intBytes(ttl), 4) != 0) {
                throw new SockException("IP_TTL", Native.getLastError());
            }
            if (tcp) {
                w.ioctlsocket(s, FIONBIO, new IntByReference(1));
            }
            byte[] name = new byte[32];
            IntByReference len = new IntByReference(name.length);
            if (w.getsockname(s, name, len) != 0) {
                throw new SockException("getsockname", Native.getLastError());
            }
            int port = ((name[2] & 0xFF) << 8) | (name[3] & 0xFF);
            return new ProbeSocket(s, port, tcp);
        } catch (SockException e) {
            w.closesocket(s);
            throw e;
        }
    }

    /** Starts the probe: a TCP SYN (non-blocking connect) or one small UDP datagram. */
    static void fire(ProbeSocket p, InetAddress dest, int port) {
        byte[] sa = WinTcpTrace.sockaddr(dest, port);
        if (p.tcp) {
            ws2().connect(p.s, sa, sa.length); // WSAEWOULDBLOCK expected
        } else {
            byte[] payload = new byte[8];
            raw().sendto(p.s, payload, payload.length, 0, sa, sa.length);
        }
    }

    /** True when a TCP probe's connect has completed (handshake done), checked without waiting. */
    static boolean connected(ProbeSocket p) {
        Memory w = WinTcpTrace.fdSet(p.s);
        return ws2().select(0, null, w, null, new int[]{0, 0}) > 0 && w.getInt(0) > 0;
    }
}
