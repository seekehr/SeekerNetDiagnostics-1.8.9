package com.seekehr.netdiagnostics.core.dns;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal DNS wire-format codec (RFC 1035) for A, AAAA, CNAME and SRV (RFC 2782) records.
 * Used only to obtain record TTLs and SRV targets; it is strict about bounds and compression loops.
 */
public final class DnsMessage {
    public static final int TYPE_A = 1;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;
    public static final int CLASS_IN = 1;

    private DnsMessage() {
    }

    public static final class Record {
        public final String name;
        public final int type;
        public final long ttl;
        /** IP literal (A/AAAA), canonical name (CNAME) or target host (SRV). */
        public final String data;
        public final int priority;
        public final int weight;
        public final int port;

        Record(String name, int type, long ttl, String data, int priority, int weight, int port) {
            this.name = name;
            this.type = type;
            this.ttl = ttl;
            this.data = data;
            this.priority = priority;
            this.weight = weight;
            this.port = port;
        }

        @Override
        public String toString() {
            if (type == TYPE_SRV) {
                return name + " SRV " + priority + " " + weight + " " + port + " " + data + " (TTL " + ttl + "s)";
            }
            return name + " " + typeName(type) + " " + data + " (TTL " + ttl + "s)";
        }
    }

    public static final class Response {
        public final int id;
        public final int rcode;
        public final boolean truncated;
        public final List<Record> answers;

        Response(int id, int rcode, boolean truncated, List<Record> answers) {
            this.id = id;
            this.rcode = rcode;
            this.truncated = truncated;
            this.answers = Collections.unmodifiableList(answers);
        }

        public List<Record> ofType(int type) {
            List<Record> out = new ArrayList<Record>();
            for (Record r : answers) {
                if (r.type == type) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    public static String typeName(int type) {
        switch (type) {
            case TYPE_A:
                return "A";
            case TYPE_AAAA:
                return "AAAA";
            case TYPE_CNAME:
                return "CNAME";
            case TYPE_SRV:
                return "SRV";
            default:
                return "TYPE" + type;
        }
    }

    /** Builds a standard recursive query with a single question. */
    public static byte[] buildQuery(int id, String name, int qtype) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeShort(out, id);
        writeShort(out, 0x0100); // RD
        writeShort(out, 1); // QDCOUNT
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        encodeName(out, name);
        writeShort(out, qtype);
        writeShort(out, CLASS_IN);
        return out.toByteArray();
    }

    static void encodeName(ByteArrayOutputStream out, String name) {
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (n.isEmpty()) {
            out.write(0);
            return;
        }
        int total = 0;
        for (String label : n.split("\\.", -1)) {
            byte[] b;
            try {
                b = label.getBytes("US-ASCII");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
            if (b.length == 0 || b.length > 63) {
                throw new IllegalArgumentException("invalid DNS label in '" + name + "'");
            }
            total += b.length + 1;
            if (total > 254) {
                throw new IllegalArgumentException("DNS name too long");
            }
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static Response parse(byte[] buf, int len) {
        if (len < 12 || len > buf.length) {
            throw new IllegalArgumentException("DNS message too short");
        }
        Reader r = new Reader(buf, len);
        int id = r.u16();
        int flags = r.u16();
        if ((flags & 0x8000) == 0) {
            throw new IllegalArgumentException("not a DNS response");
        }
        int qd = r.u16();
        int an = r.u16();
        r.u16(); // NS
        r.u16(); // AR
        for (int i = 0; i < qd; i++) {
            r.name();
            r.u16();
            r.u16();
        }
        List<Record> answers = new ArrayList<Record>();
        for (int i = 0; i < an; i++) {
            String name = r.name();
            int type = r.u16();
            int cls = r.u16();
            long ttl = r.u32();
            int rdlen = r.u16();
            int rdStart = r.pos;
            r.require(rdlen);
            if (cls == CLASS_IN) {
                if (type == TYPE_A && rdlen == 4) {
                    answers.add(new Record(name, type, ttl, ip(buf, rdStart, 4), 0, 0, 0));
                } else if (type == TYPE_AAAA && rdlen == 16) {
                    answers.add(new Record(name, type, ttl, ip(buf, rdStart, 16), 0, 0, 0));
                } else if (type == TYPE_CNAME) {
                    Reader sub = new Reader(buf, len);
                    sub.pos = rdStart;
                    answers.add(new Record(name, type, ttl, sub.name(), 0, 0, 0));
                } else if (type == TYPE_SRV && rdlen >= 7) {
                    Reader sub = new Reader(buf, len);
                    sub.pos = rdStart;
                    int prio = sub.u16();
                    int weight = sub.u16();
                    int port = sub.u16();
                    String target = sub.name();
                    answers.add(new Record(name, type, ttl, target, prio, weight, port));
                }
            }
            r.pos = rdStart + rdlen;
        }
        return new Response(id, flags & 0x000F, (flags & 0x0200) != 0, answers);
    }

    private static String ip(byte[] buf, int off, int n) {
        byte[] b = new byte[n];
        System.arraycopy(buf, off, b, 0, n);
        try {
            return InetAddress.getByAddress(b).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final class Reader {
        final byte[] buf;
        final int len;
        int pos;

        Reader(byte[] buf, int len) {
            this.buf = buf;
            this.len = len;
        }

        void require(int n) {
            if (n < 0 || pos + n > len) {
                throw new IllegalArgumentException("truncated DNS message");
            }
        }

        int u8() {
            require(1);
            return buf[pos++] & 0xFF;
        }

        int u16() {
            require(2);
            int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u32() {
            require(4);
            long v = ((long) (buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                    | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        String name() {
            StringBuilder sb = new StringBuilder();
            int p = pos;
            boolean jumped = false;
            int jumps = 0;
            while (true) {
                if (p >= len) {
                    throw new IllegalArgumentException("truncated DNS name");
                }
                int l = buf[p] & 0xFF;
                if ((l & 0xC0) == 0xC0) {
                    if (p + 1 >= len) {
                        throw new IllegalArgumentException("truncated compression pointer");
                    }
                    int target = ((l & 0x3F) << 8) | (buf[p + 1] & 0xFF);
                    if (!jumped) {
                        pos = p + 2;
                    }
                    jumped = true;
                    if (++jumps > 32 || target >= len) {
                        throw new IllegalArgumentException("DNS compression loop");
                    }
                    p = target;
                    continue;
                }
                if ((l & 0xC0) != 0) {
                    throw new IllegalArgumentException("unsupported DNS label type");
                }
                if (l == 0) {
                    if (!jumped) {
                        pos = p + 1;
                    }
                    break;
                }
                if (p + 1 + l > len) {
                    throw new IllegalArgumentException("truncated DNS label");
                }
                if (sb.length() > 0) {
                    sb.append('.');
                }
                for (int i = 0; i < l; i++) {
                    sb.append((char) (buf[p + 1 + i] & 0xFF));
                }
                if (sb.length() > 255) {
                    throw new IllegalArgumentException("DNS name too long");
                }
                p += 1 + l;
            }
            return sb.toString();
        }
    }
}
