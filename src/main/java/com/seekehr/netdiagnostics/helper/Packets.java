package com.seekehr.netdiagnostics.helper;

/**
 * Minimal IPv4 / TCP / UDP / ICMP parsing for captured packets. Pure functions over a byte array, so they can be
 * unit-tested without a capture.
 */
public final class Packets {
    public static final int ICMP = 1;
    public static final int TCP = 6;
    public static final int UDP = 17;

    public static final int TCP_SYN = 0x02;
    public static final int TCP_RST = 0x04;
    public static final int TCP_ACK = 0x10;

    private Packets() {
    }

    /** A parsed IPv4 packet. Ports are -1 when not applicable; inner fields are set only for ICMP errors. */
    public static final class Ip4 {
        public int protocol;
        public String src;
        public String dst;
        public int totalLength;
        public int ttl;
        public int srcPort = -1;
        public int dstPort = -1;
        public int tcpFlags;
        /** ICMP type/code, -1 when not ICMP. */
        public int icmpType = -1;
        public int icmpCode = -1;
        /** For ICMP Time Exceeded / Destination Unreachable: the quoted original packet. */
        public int innerProtocol = -1;
        public String innerSrc;
        public String innerDst;
        public int innerSrcPort = -1;
        public int innerDstPort = -1;

        public boolean icmpError() {
            return icmpType == 11 || icmpType == 3;
        }
    }

    /** Parses an IPv4 packet starting at offset 0 with {@code len} valid bytes; null if it is not usable IPv4. */
    public static Ip4 parse(byte[] b, int len) {
        if (len < 20 || (b[0] & 0xF0) != 0x40) {
            return null;
        }
        int ihl = (b[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl) {
            return null;
        }
        Ip4 p = new Ip4();
        p.totalLength = u16(b, 2);
        p.ttl = b[8] & 0xFF;
        p.protocol = b[9] & 0xFF;
        p.src = ip4(b, 12);
        p.dst = ip4(b, 16);
        int l4 = ihl;
        if ((p.protocol == TCP || p.protocol == UDP) && len >= l4 + 4) {
            p.srcPort = u16(b, l4);
            p.dstPort = u16(b, l4 + 2);
            if (p.protocol == TCP && len >= l4 + 14) {
                p.tcpFlags = b[l4 + 13] & 0xFF;
            }
        } else if (p.protocol == ICMP && len >= l4 + 2) {
            p.icmpType = b[l4] & 0xFF;
            p.icmpCode = b[l4 + 1] & 0xFF;
            int inner = l4 + 8;
            if (p.icmpError() && len >= inner + 20 && (b[inner] & 0xF0) == 0x40) {
                int innerIhl = (b[inner] & 0x0F) * 4;
                p.innerProtocol = b[inner + 9] & 0xFF;
                p.innerSrc = ip4(b, inner + 12);
                p.innerDst = ip4(b, inner + 16);
                int il4 = inner + innerIhl;
                if (len >= il4 + 4 && (p.innerProtocol == TCP || p.innerProtocol == UDP)) {
                    p.innerSrcPort = u16(b, il4);
                    p.innerDstPort = u16(b, il4 + 2);
                }
            }
        }
        return p;
    }

    /**
     * Key that ties a probe to its replies: protocol letter, remote IP and OUR local port. An ICMP error quoting
     * the probe and a direct TCP/UDP answer from the target produce the same key.
     */
    public static String probeKey(int protocol, String remoteIp, int localPort) {
        return (protocol == TCP ? "T|" : "U|") + remoteIp + "|" + localPort;
    }

    /** The probe key a received packet answers, or null. {@code inbound} = its destination is this PC. */
    public static String replyKey(Ip4 p) {
        if (p.protocol == ICMP) {
            if (!p.icmpError() || p.innerDst == null || p.innerSrcPort < 0) {
                return null;
            }
            return probeKey(p.innerProtocol, p.innerDst, p.innerSrcPort);
        }
        if ((p.protocol == TCP || p.protocol == UDP) && p.dstPort >= 0) {
            return probeKey(p.protocol, p.src, p.dstPort);
        }
        return null;
    }

    static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static String ip4(byte[] b, int o) {
        return (b[o] & 0xFF) + "." + (b[o + 1] & 0xFF) + "." + (b[o + 2] & 0xFF) + "." + (b[o + 3] & 0xFF);
    }
}
