package com.seekehr.netdiagnostics.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks the (at most) few relays that most likely carry the game, out of everything the proxy is using.
 *
 * <p>Two facts decide it:
 * <ol>
 * <li><b>Latency bound.</b> Game traffic goes you -&gt; relay -&gt; ... -&gt; server, so your ping to a relay on that
 * path cannot be higher than the game ping. A relay whose traced RTT is above the game ping (plus a small
 * tolerance) is rejected.</li>
 * <li><b>The game connection.</b> The game is one long-lived connection with steady traffic. The proxy also
 * carries this mod's own pings (a new short connection every second, spread across relays) and measures candidate
 * relays. So a relay with a connection open for {@link #LONG_LIVED_S}+ seconds counts most, then share of
 * traffic.</li>
 * </ol>
 * The remaining ping after the relay ({@code game - relay RTT}) is the part inside the proxy's tunnel.
 */
public final class RelayRanking {
    public static final int DEFAULT_MAX = 3;
    static final long LONG_LIVED_S = 30;
    /** Relay RTT may exceed the game ping by this fraction plus {@link #TOLERANCE_MS} (measurement noise). */
    static final double TOLERANCE_FRACTION = 0.10;
    static final double TOLERANCE_MS = 5;
    /** A candidate needs at least this fraction of the best candidate's score to be shown. */
    static final double MIN_RELATIVE_SCORE = 0.25;

    public static final class Entry {
        public final Snapshot.Relay relay;
        public final Snapshot.Path path;
        /** Average RTT to the relay itself (its hop in the trace), NaN when not traced/reached yet. */
        public final double rttMs;
        /** Game ping minus relay RTT: the part inside the tunnel. NaN when unknown. */
        public final double beyondMs;
        public final boolean accepted;
        /** Why it was picked or left out, one sentence. */
        public final String reason;
        final double score;

        Entry(Snapshot.Relay relay, Snapshot.Path path, double rttMs, double beyondMs, boolean accepted, String reason,
              double score) {
            this.relay = relay;
            this.path = path;
            this.rttMs = rttMs;
            this.beyondMs = beyondMs;
            this.accepted = accepted;
            this.reason = reason;
            this.score = score;
        }
    }

    private RelayRanking() {
    }

    /** RTT to the relay from its trace: the destination hop's average, NaN if not reached. */
    public static double relayRtt(Snapshot.Path p) {
        if (p == null) {
            return Double.NaN;
        }
        for (Snapshot.Hop h : p.hops) {
            if (h.destination && h.received > 0) {
                return h.avg;
            }
        }
        return Double.NaN;
    }

    /**
     * Ranks the in-use relays of {@code s}. Accepted entries (at most {@code max}) come first, best first, followed by
     * the rejected ones with their reasons.
     *
     * @param gamePingMs the player's real ping (tab list, or the measured median); NaN if unknown
     */
    public static List<Entry> rank(Snapshot s, double gamePingMs, int max) {
        List<Snapshot.Relay> cands = new ArrayList<Snapshot.Relay>();
        double totalRate = 0;
        for (Snapshot.Relay r : s.relays) {
            if (r.inUse && !r.service) {
                cands.add(r);
                totalRate += rate(r);
            }
        }
        List<Entry> ok = new ArrayList<Entry>();
        List<Entry> out = new ArrayList<Entry>();
        for (Snapshot.Relay r : cands) {
            Snapshot.Path p = s.path(r.pathKey);
            double rtt = relayRtt(p);
            boolean longLived = r.connSeconds >= LONG_LIVED_S;
            double share = totalRate > 0 ? rate(r) / totalRate : 0;
            double beyond = Double.isNaN(rtt) || Double.isNaN(gamePingMs) ? Double.NaN : Math.max(0, gamePingMs - rtt);
            if (!Double.isNaN(rtt) && !Double.isNaN(gamePingMs)
                    && rtt > gamePingMs * (1 + TOLERANCE_FRACTION) + TOLERANCE_MS) {
                out.add(new Entry(r, p, rtt, Double.NaN, false, String.format(Locale.ROOT,
                        "Ruled out: the relay is %.0f ms away but your game ping is %.0f ms, so the game cannot go "
                                + "through it.", rtt, gamePingMs), 0));
                continue;
            }
            if (rate(r) <= 0 && !longLived) {
                out.add(new Entry(r, p, rtt, beyond, false, "Left out: no packets in the last couple of seconds.", 0));
                continue;
            }
            double score = share + (longLived ? 1.0 : 0);
            ok.add(new Entry(r, p, rtt, beyond, true, null, score));
        }
        Collections.sort(ok, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Double.compare(b.score, a.score);
            }
        });
        List<Entry> result = new ArrayList<Entry>();
        double best = ok.isEmpty() ? 0 : ok.get(0).score;
        for (int i = 0; i < ok.size(); i++) {
            Entry e = ok.get(i);
            if (i < max && e.score >= best * MIN_RELATIVE_SCORE) {
                result.add(new Entry(e.relay, e.path, e.rttMs, e.beyondMs, true, acceptReason(e, gamePingMs), e.score));
            } else {
                out.add(new Entry(e.relay, e.path, e.rttMs, e.beyondMs, false, String.format(Locale.ROOT,
                        "Left out: carries less traffic (%.0f%% of the proxy's relay traffic)%s.",
                        100 * shareOf(e.relay, cands), e.relay.connSeconds >= LONG_LIVED_S ? ""
                                : " and has no long-lived connection"), e.score));
            }
        }
        result.addAll(out);
        return result;
    }

    private static String acceptReason(Entry e, double game) {
        StringBuilder sb = new StringBuilder();
        if (e.relay.connSeconds >= LONG_LIVED_S) {
            sb.append("Holds a connection open for ").append(e.relay.connSeconds).append(" s (like the game's)");
        } else {
            sb.append("Carries steady proxy traffic");
        }
        if (!Double.isNaN(e.rttMs) && !Double.isNaN(game)) {
            sb.append(String.format(Locale.ROOT, "; %.0f ms to the relay fits within your %.0f ms game ping, leaving "
                    + "about %.0f ms inside the tunnel", e.rttMs, game, e.beyondMs));
        } else if (Double.isNaN(e.rttMs)) {
            sb.append("; not traced yet, so its latency is not checked");
        }
        return sb.append('.').toString();
    }

    private static double shareOf(Snapshot.Relay r, List<Snapshot.Relay> cands) {
        double total = 0;
        for (Snapshot.Relay c : cands) {
            total += rate(c);
        }
        return total > 0 ? rate(r) / total : 0;
    }

    private static double rate(Snapshot.Relay r) {
        return Math.max(0, r.packetsOutPerSec) + Math.max(0, r.packetsInPerSec);
    }
}
