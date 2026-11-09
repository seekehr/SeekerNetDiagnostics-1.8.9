package com.seekehr.netdiagnostics.helper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hop-by-hop trace with TTL-limited TCP SYN or UDP probes, answers matched from the {@link Capture}. Uses the
 * target's own protocol and port, so the probes follow the same routing as the traffic being diagnosed as far
 * as port-independent routing allows.
 */
final class PathTracer {
    static final int MAX_TTL = 30;
    static final int PROBES = 3;
    static final long WAIT_NANOS = 1000L * 1000 * 1000;
    static final int MAX_SILENT = 6;

    interface Progress {
        void update(List<Snapshot.Hop> hops);
    }

    static final class Result {
        final List<Snapshot.Hop> hops;
        final boolean reached;
        final String note;
        final boolean icmpSeen;
        /** A TTL 1 probe was answered on this PC: a local proxy (normally ExitLag) took the connection over. */
        final boolean intercepted;

        Result(List<Snapshot.Hop> hops, boolean reached, String note, boolean icmpSeen) {
            this(hops, reached, note, icmpSeen, false);
        }

        Result(List<Snapshot.Hop> hops, boolean reached, String note, boolean icmpSeen, boolean intercepted) {
            this.hops = hops;
            this.reached = reached;
            this.note = note;
            this.icmpSeen = icmpSeen;
            this.intercepted = intercepted;
        }
    }

    private PathTracer() {
    }

    static Result trace(Capture cap, InetAddress dest, int port, boolean tcp, Progress progress, AtomicBoolean cancel) {
        String target = dest.getHostAddress();
        int proto = tcp ? Packets.TCP : Packets.UDP;
        List<Snapshot.Hop> hops = new ArrayList<Snapshot.Hop>();
        boolean icmpSeen = false;
        int silent = 0;
        for (int ttl = 1; ttl <= MAX_TTL && !cancel.get(); ttl++) {
            List<RawSock.ProbeSocket> socks = new ArrayList<RawSock.ProbeSocket>();
            List<String> keys = new ArrayList<String>();
            long[] sent = new long[PROBES];
            long[] connectedAt = new long[PROBES];
            try {
                for (int i = 0; i < PROBES; i++) {
                    RawSock.ProbeSocket p = RawSock.open(tcp, ttl);
                    socks.add(p);
                    String key = Packets.probeKey(proto, target, p.localPort);
                    keys.add(key);
                    cap.register(key);
                    sent[i] = System.nanoTime();
                    RawSock.fire(p, dest, port);
                    sleep(15);
                }
                long last = System.nanoTime();
                while (System.nanoTime() - last < WAIT_NANOS && !cancel.get()) {
                    boolean all = true;
                    for (int i = 0; i < PROBES; i++) {
                        if (tcp && connectedAt[i] == 0 && RawSock.connected(socks.get(i))) {
                            connectedAt[i] = System.nanoTime();
                        }
                        if (cap.reply(keys.get(i)) == null && connectedAt[i] == 0) {
                            all = false;
                        }
                    }
                    if (all) {
                        break;
                    }
                    sleep(5);
                }
                Snapshot.Hop hop = new Snapshot.Hop();
                hop.ttl = ttl;
                hop.sent = PROBES;
                Map<String, Integer> counts = new HashMap<String, Integer>();
                List<Double> rtts = new ArrayList<Double>();
                boolean reached = false;
                boolean localAnswer = false;
                String unreachableFrom = null;
                for (int i = 0; i < PROBES; i++) {
                    Capture.Reply r = cap.reply(keys.get(i));
                    if (r != null) {
                        hop.received++;
                        rtts.add((r.nanos - sent[i]) / 1e6);
                        Integer c = counts.get(r.from);
                        counts.put(r.from, c == null ? 1 : c + 1);
                        if (r.kind == Capture.HOP) {
                            icmpSeen = true;
                        } else if (r.kind == Capture.REACHED) {
                            reached = true;
                        } else {
                            unreachableFrom = r.from;
                        }
                    } else if (connectedAt[i] != 0) {
                        // the handshake completed but no packet from the target was captured
                        hop.received++;
                        rtts.add((connectedAt[i] - sent[i]) / 1e6);
                        reached = true;
                        localAnswer = true;
                    }
                }
                fill(hop, counts, rtts);
                if (localAnswer && hop.address == null) {
                    hop.address = target;
                }
                hop.destination = reached;
                hops.add(hop);
                progress.update(copy(hops));
                if (localAnswer && ttl == 1 && counts.isEmpty()) {
                    return new Result(hops, false, "The probe was answered on this PC (a local proxy took the connection "
                            + "over), so this path is hidden from here.", icmpSeen, true);
                }
                if (reached) {
                    return new Result(hops, true, "Reached " + target + " at hop " + ttl + ".", icmpSeen);
                }
                if (unreachableFrom != null) {
                    return new Result(hops, false, unreachableFrom + " reported the target unreachable at hop " + ttl
                            + ".", icmpSeen);
                }
                silent = hop.received == 0 ? silent + 1 : 0;
                if (silent >= MAX_SILENT) {
                    break;
                }
            } catch (RawSock.SockException e) {
                return new Result(hops, false, "Probe failed: " + e.getMessage(), icmpSeen);
            } finally {
                for (RawSock.ProbeSocket p : socks) {
                    p.close();
                }
                for (String k : keys) {
                    cap.unregister(k);
                }
            }
        }
        int lastReply = trimSilentTail(hops);
        progress.update(copy(hops));
        String note;
        if (cancel.get()) {
            note = "Cancelled.";
        } else if (!icmpSeen && lastReply < 0) {
            note = "No replies at all. Inbound ICMP may be blocked by the firewall.";
        } else {
            note = String.format(Locale.ROOT, "%s did not answer %s probes; the last visible router is hop %d. "
                    + "Hops after that are hidden.", target, tcp ? "TCP" : "UDP", lastReply + 1);
        }
        return new Result(hops, false, note, icmpSeen);
    }

    /** Removes trailing silent hops, keeping one to show where replies stopped. Returns the last replying index. */
    static int trimSilentTail(List<Snapshot.Hop> hops) {
        int lastReply = -1;
        for (int i = 0; i < hops.size(); i++) {
            if (hops.get(i).received > 0) {
                lastReply = i;
            }
        }
        while (hops.size() > lastReply + 2) {
            hops.remove(hops.size() - 1);
        }
        return lastReply;
    }

    /**
     * Hop-by-hop trace with ICMP echo ({@link IcmpEcho}), three probes per TTL in parallel. Same output shape as
     * {@link #trace}. Used when TCP/UDP probes are taken over on this PC or their ICMP replies cannot be received.
     */
    static Result traceIcmp(final Inet4Address dest, Progress progress, AtomicBoolean cancel) {
        String target = dest.getHostAddress();
        List<Snapshot.Hop> hops = new ArrayList<Snapshot.Hop>();
        int silent = 0;
        for (int ttl = 1; ttl <= MAX_TTL && !cancel.get(); ttl++) {
            final IcmpEcho.Echo[] res = new IcmpEcho.Echo[PROBES];
            Thread[] threads = new Thread[PROBES];
            for (int i = 0; i < PROBES; i++) {
                final int idx = i;
                final int t = ttl;
                threads[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        res[idx] = IcmpEcho.send(dest, t, 1000);
                    }
                }, "SND-icmp");
                threads[i].setDaemon(true);
                threads[i].start();
                sleep(15);
            }
            for (Thread th : threads) {
                try {
                    th.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Snapshot.Hop hop = new Snapshot.Hop();
            hop.ttl = ttl;
            hop.sent = PROBES;
            Map<String, Integer> counts = new HashMap<String, Integer>();
            List<Double> rtts = new ArrayList<Double>();
            boolean reached = false;
            for (IcmpEcho.Echo e : res) {
                if (e != null && e.from != null) {
                    hop.received++;
                    rtts.add(e.rttMs);
                    Integer c = counts.get(e.from);
                    counts.put(e.from, c == null ? 1 : c + 1);
                    reached |= e.reached() && e.from.equals(target);
                }
            }
            fill(hop, counts, rtts);
            hop.destination = reached;
            hops.add(hop);
            progress.update(copy(hops));
            if (reached) {
                return new Result(hops, true, "Reached " + target + " at hop " + ttl + ".", false);
            }
            silent = hop.received == 0 ? silent + 1 : 0;
            if (silent >= MAX_SILENT) {
                break;
            }
        }
        int lastReply = trimSilentTail(hops);
        progress.update(copy(hops));
        return new Result(hops, false, cancel.get() ? "Cancelled." : target + " did not answer ICMP echo; the last "
                + "visible router is hop " + (lastReply + 1) + ". Hops after that are hidden.", false);
    }

    /** Sets address (most common replier), the other repliers, and min/avg/max RTT. */
    static void fill(Snapshot.Hop hop, Map<String, Integer> counts, List<Double> rtts) {
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN || e.getValue() == bestN && best != null && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        hop.address = best;
        List<String> others = new ArrayList<String>();
        for (String a : counts.keySet()) {
            if (!a.equals(best)) {
                others.add(a);
            }
        }
        Collections.sort(others);
        StringBuilder sb = new StringBuilder();
        for (String o : others) {
            sb.append(sb.length() == 0 ? "" : ", ").append(o);
        }
        hop.others = sb.toString();
        if (!rtts.isEmpty()) {
            double min = Double.MAX_VALUE;
            double max = 0;
            double sum = 0;
            for (double r : rtts) {
                min = Math.min(min, r);
                max = Math.max(max, r);
                sum += r;
            }
            hop.min = min;
            hop.max = max;
            hop.avg = sum / rtts.size();
        }
    }

    private static List<Snapshot.Hop> copy(List<Snapshot.Hop> hops) {
        return Collections.unmodifiableList(new ArrayList<Snapshot.Hop>(hops));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
