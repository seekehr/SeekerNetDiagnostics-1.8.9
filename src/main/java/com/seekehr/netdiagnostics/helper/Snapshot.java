package com.seekehr.netdiagnostics.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything the elevated helper reports, sent to the mod as tab-separated lines ending with {@code END}.
 * Immutable once decoded. No Minecraft classes, so the helper process can load it.
 */
public final class Snapshot {
    /** One remote endpoint of an ExitLag-owned socket. */
    public static final class Relay {
        public String protocol = "UDP";
        public String ip;
        public int port;
        public int pid;
        public String process;
        public double packetsOutPerSec;
        public double packetsInPerSec;
        public double bytesPerSec;
        /** ms since a packet was last captured on it; -1 = never captured (known only from the socket table). */
        public long lastSeenAgoMs = -1;
        /** Traffic from an ExitLag-owned socket to this endpoint was captured recently. */
        public boolean verified;
        /** Likely ExitLag's own web/API service (HTTPS port), not a game relay; not traced. */
        public boolean service;
        /**
         * Sustained traffic: ExitLag is tunnelling through it, not just measuring it. ExitLag constantly opens short
         * connections to candidate relays to measure them; those are verified but not in use.
         */
        public boolean inUse;
        /** Seconds since traffic on this endpoint was first captured (0 if never). */
        public long activeSeconds;
        /** Highest-traffic in-use relay. */
        public boolean primary;
        /** Key of this relay's path in {@link Snapshot#paths}, or null when not traced (yet). */
        public String pathKey;

        public String endpoint() {
            return ip + ":" + port;
        }
    }

    /** One TTL of a traced path. Loss and RTT are over the probes sent at this TTL in the latest trace. */
    public static final class Hop {
        public int ttl;
        /** Most common replying address, or null when nothing replied. */
        public String address;
        /** Other addresses that replied at this TTL (load-balanced paths). */
        public String others = "";
        public int sent;
        public int received;
        public double min = Double.NaN;
        public double avg = Double.NaN;
        public double max = Double.NaN;
        public boolean destination;

        public double lossPercent() {
            return sent == 0 ? Double.NaN : 100.0 * (sent - received) / sent;
        }
    }

    /** A hop-by-hop trace to one target (an ExitLag relay or the game server). */
    public static final class Path {
        public String key;
        /** "relay" or "server". */
        public String kind;
        /** Probe method actually used: "TCP", "UDP" or "ICMP". */
        public String protocol;
        public String target;
        public int port;
        /** "running" or "done". */
        public String state = "running";
        public boolean reached;
        public String note = "";
        public long updatedAt;
        public int traces;
        public List<Hop> hops = new ArrayList<Hop>();
    }

    public static final class Event {
        public long time;
        public String text;
    }

    public boolean captureOk;
    public String status = "";
    /** True when inbound ICMP errors are being received (the firewall lets them through). */
    public boolean icmpSeen;
    public boolean exitLagRunning;
    public List<Relay> relays = new ArrayList<Relay>();
    public List<Path> paths = new ArrayList<Path>();
    public List<Event> events = new ArrayList<Event>();

    public Path path(String key) {
        if (key == null) {
            return null;
        }
        for (Path p : paths) {
            if (key.equals(p.key)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ wire format

    public List<String> encode() {
        List<String> out = new ArrayList<String>();
        out.add(Wire.line("STATUS", b(captureOk), status, b(icmpSeen), b(exitLagRunning)));
        for (Relay r : relays) {
            out.add(Wire.line("RELAY", r.protocol, r.ip, String.valueOf(r.port), String.valueOf(r.pid),
                    r.process, d(r.packetsOutPerSec), d(r.packetsInPerSec), d(r.bytesPerSec),
                    String.valueOf(r.lastSeenAgoMs), b(r.verified), b(r.service), b(r.primary), r.pathKey,
                    b(r.inUse), String.valueOf(r.activeSeconds)));
        }
        for (Path p : paths) {
            out.add(Wire.line("PATH", p.key, p.kind, p.protocol, p.target, String.valueOf(p.port), p.state,
                    b(p.reached), p.note, String.valueOf(p.updatedAt), String.valueOf(p.traces)));
            for (Hop h : p.hops) {
                out.add(Wire.line("HOP", p.key, String.valueOf(h.ttl), h.address, h.others, String.valueOf(h.sent),
                        String.valueOf(h.received), d(h.min), d(h.avg), d(h.max), b(h.destination)));
            }
        }
        for (Event e : events) {
            out.add(Wire.line("EVENT", String.valueOf(e.time), e.text));
        }
        out.add("END");
        return out;
    }

    /** Builds a snapshot from the lines before {@code END}. Unknown or malformed lines are skipped. */
    public static Snapshot decode(List<String> lines) {
        Snapshot s = new Snapshot();
        for (String line : lines) {
            String[] f = Wire.split(line);
            try {
                if ("STATUS".equals(f[0]) && f.length >= 5) {
                    s.captureOk = bool(f[1]);
                    s.status = f[2];
                    s.icmpSeen = bool(f[3]);
                    s.exitLagRunning = bool(f[4]);
                } else if ("RELAY".equals(f[0]) && f.length >= 14) {
                    Relay r = new Relay();
                    r.protocol = f[1];
                    r.ip = f[2];
                    r.port = Integer.parseInt(f[3]);
                    r.pid = Integer.parseInt(f[4]);
                    r.process = f[5];
                    r.packetsOutPerSec = dbl(f[6]);
                    r.packetsInPerSec = dbl(f[7]);
                    r.bytesPerSec = dbl(f[8]);
                    r.lastSeenAgoMs = Long.parseLong(f[9]);
                    r.verified = bool(f[10]);
                    r.service = bool(f[11]);
                    r.primary = bool(f[12]);
                    r.pathKey = f[13].isEmpty() ? null : f[13];
                    if (f.length >= 16) {
                        r.inUse = bool(f[14]);
                        r.activeSeconds = Long.parseLong(f[15]);
                    }
                    s.relays.add(r);
                } else if ("PATH".equals(f[0]) && f.length >= 11) {
                    Path p = new Path();
                    p.key = f[1];
                    p.kind = f[2];
                    p.protocol = f[3];
                    p.target = f[4];
                    p.port = Integer.parseInt(f[5]);
                    p.state = f[6];
                    p.reached = bool(f[7]);
                    p.note = f[8];
                    p.updatedAt = Long.parseLong(f[9]);
                    p.traces = Integer.parseInt(f[10]);
                    s.paths.add(p);
                } else if ("HOP".equals(f[0]) && f.length >= 11) {
                    Path p = s.path(f[1]);
                    if (p == null) {
                        continue;
                    }
                    Hop h = new Hop();
                    h.ttl = Integer.parseInt(f[2]);
                    h.address = f[3].isEmpty() ? null : f[3];
                    h.others = f[4];
                    h.sent = Integer.parseInt(f[5]);
                    h.received = Integer.parseInt(f[6]);
                    h.min = dbl(f[7]);
                    h.avg = dbl(f[8]);
                    h.max = dbl(f[9]);
                    h.destination = bool(f[10]);
                    p.hops.add(h);
                } else if ("EVENT".equals(f[0]) && f.length >= 3) {
                    Event e = new Event();
                    e.time = Long.parseLong(f[1]);
                    e.text = f[2];
                    s.events.add(e);
                }
            } catch (RuntimeException ignored) {
                // skip a malformed line
            }
        }
        s.relays = Collections.unmodifiableList(s.relays);
        s.paths = Collections.unmodifiableList(s.paths);
        s.events = Collections.unmodifiableList(s.events);
        return s;
    }

    private static String b(boolean v) {
        return v ? "1" : "0";
    }

    private static boolean bool(String s) {
        return "1".equals(s);
    }

    private static String d(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static double dbl(String s) {
        return "NaN".equals(s) || s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }
}
