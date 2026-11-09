package com.seekehr.netdiagnostics.route;

import com.seekehr.netdiagnostics.core.monitor.Proxies;
import com.seekehr.netdiagnostics.core.monitor.Traceroute;
import com.seekehr.netdiagnostics.helper.HelperClient;
import com.seekehr.netdiagnostics.helper.RelayRanking;
import com.seekehr.netdiagnostics.helper.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runs route analysis in rounds and keeps the last finished {@link RouteReport}.
 *
 * <p>A round is: the TCP check (does a proxy carry the game, and which one?), then, when it does and advanced
 * tracing is on, fresh traces of the relays owned by that proxy, and the ICMP traceroute to the server. Only when
 * all of it is done is the report replaced. Rounds repeat every {@link #intervalSec()} seconds after the previous
 * one finished (0 = only on request).
 *
 * <p>Driven by {@link #tick} from the client thread; the measurements themselves run on their own threads.
 */
public final class RouteAnalysis {
    /** Update intervals the user can cycle through, in seconds; 0 = off. */
    public static final int[] INTERVALS = {15, 30, 60, 120, 0};
    static final long HELPER_WAIT_MS = 90000;
    /** How long to wait for the proxy's relays to show traffic when none is known yet. */
    static final long DISCOVERY_MS = 20000;
    /** Upper bound for relay traces in one round; after it the report is published as partial. */
    static final long RELAY_TIMEOUT_MS = 75000;
    static final int MAX_RELAYS = 6;

    /** What the round needs from the elevated helper (implemented by the mod; faked in tests). */
    public interface Helper {
        /** Advanced tracing is switched on. */
        boolean wanted();

        HelperClient.State state();

        String message();

        Snapshot snapshot();

        /** Follow this process (it carries the game); 0 = none. */
        void focus(int pid);

        void retrace();

        void setServer(String ip, int port);

        /** Start the helper (may show the UAC prompt); only called for user-initiated rounds. */
        void ensureStarted();
    }

    /** Progress of the running round, for the loading view. Immutable. */
    public static final class Progress {
        public static final int PENDING = 0;
        public static final int ACTIVE = 1;
        public static final int DONE = 2;
        public static final int SKIPPED = 3;

        public final String[] labels;
        public final String[] details;
        public final int[] states;
        public final double fraction;

        Progress(String[] labels, String[] details, int[] states, double fraction) {
            this.labels = labels;
            this.details = details;
            this.states = states;
            this.fraction = fraction;
        }

        /** Label and detail of the first active step, for a one-line status. */
        public String current() {
            for (int i = 0; i < states.length; i++) {
                if (states[i] == ACTIVE) {
                    return labels[i] + (details[i] == null || details[i].isEmpty() ? "" : ": " + details[i]);
                }
            }
            return "Finishing";
        }
    }

    private static final class Round {
        final long startedAt;
        final Traceroute tr;
        long retraceAt;
        boolean focused;
        boolean partial;
        String note;
        /** Highest fraction shown so far, so the bar never moves backwards. */
        double shown;
        volatile Progress progress;

        Round(long startedAt, Traceroute tr) {
            this.startedAt = startedAt;
            this.tr = tr;
        }
    }

    private volatile RouteReport report;
    private volatile Round round;
    private volatile String destination;
    private volatile int port;
    private volatile int intervalSec = 60;

    public RouteReport report() {
        return report;
    }

    /** Progress of the running round, or null when none is running. */
    public Progress progress() {
        Round r = round;
        return r == null ? null : r.progress;
    }

    public boolean running() {
        return round != null;
    }

    public String destination() {
        return destination;
    }

    public int intervalSec() {
        return intervalSec;
    }

    public void setIntervalSec(int sec) {
        intervalSec = Math.max(0, sec);
    }

    /** When the next automatic round starts (ms), or -1 if none is scheduled. */
    public long nextUpdateAt() {
        RouteReport r = report;
        return round != null || r == null || intervalSec <= 0 ? -1 : r.finishedAt + intervalSec * 1000L;
    }

    /** The TCP check of the running round, else of the last report (for code that needs the latest verdict). */
    public Traceroute latestTrace() {
        Round r = round;
        if (r != null && r.tr.tcp() != null) {
            return r.tr;
        }
        RouteReport rep = report;
        return rep == null ? null : rep.trace;
    }

    /**
     * Starts a round now (cancelling a running one). A different destination clears the old report.
     *
     * @param userInitiated the user asked for it: the helper may be started (UAC prompt)
     */
    public synchronized void start(String dest, int destPort, Helper helper, boolean userInitiated, long now) {
        if (dest == null) {
            return;
        }
        Round old = round;
        if (old != null) {
            old.tr.cancel();
        }
        if (!dest.equals(destination) || destPort != port) {
            report = null;
        }
        destination = dest;
        port = destPort;
        helper.setServer(dest, destPort);
        if (userInitiated && helper.wanted()) {
            helper.ensureStarted();
        }
        Traceroute tr = new Traceroute(dest, destPort);
        Round r = new Round(now, tr);
        r.progress = progress(r, helper, null, now);
        round = r;
        tr.start();
    }

    /** Stops everything and forgets the target (leaving the server). */
    public synchronized void stop() {
        Round r = round;
        if (r != null) {
            r.tr.cancel();
        }
        round = null;
        destination = null;
    }

    /** Advances the running round, or starts the next automatic one when it is due. */
    public synchronized void tick(long now, double gamePing, Helper helper) {
        Round r = round;
        if (r == null) {
            long next = nextUpdateAt();
            if (destination != null && next >= 0 && now >= next) {
                start(destination, port, helper, false, now);
            }
            return;
        }
        Traceroute.TcpResult t = r.tr.tcp();
        if (t == null) {
            if (!r.tr.running()) {
                round = null; // cancelled
                return;
            }
            r.progress = progress(r, helper, null, now);
            return;
        }
        boolean proxy = carriesGame(t);
        if (!r.focused) {
            helper.focus(proxy ? Math.max(0, t.acceptedPid) : 0);
            r.focused = true;
        }
        boolean relaysDone = relaysDone(r, t, proxy, helper, now);
        r.progress = progress(r, helper, t, now);
        if (relaysDone && !r.tr.icmpRunning()) {
            finish(r, t, proxy, gamePing, helper, now);
        }
    }

    static boolean carriesGame(Traceroute.TcpResult t) {
        return t.verdict == Traceroute.Verdict.PROXY_VERIFIED || t.verdict == Traceroute.Verdict.LOCAL_PROXY;
    }

    private boolean relaysDone(Round r, Traceroute.TcpResult t, boolean proxy, Helper helper, long now) {
        if (!proxy || !helper.wanted()) {
            return true;
        }
        HelperClient.State st = helper.state();
        if (st == HelperClient.State.WAITING_FOR_UAC || st == HelperClient.State.STARTING) {
            if (now - r.startedAt < HELPER_WAIT_MS) {
                return false;
            }
            r.note = "The admin helper was not ready in time; relays are not included in this update.";
            return true;
        }
        if (st != HelperClient.State.CONNECTED) {
            r.note = helper.message();
            return true;
        }
        if (r.retraceAt == 0) {
            helper.retrace();
            r.retraceAt = now;
        }
        List<Snapshot.Relay> c = candidates(helper.snapshot(), t.acceptedPid, t.proxyName);
        if (c.isEmpty()) {
            return now - r.retraceAt >= DISCOVERY_MS;
        }
        if (traced(helper.snapshot(), c, r.retraceAt) >= c.size()) {
            return true;
        }
        if (now - r.retraceAt >= RELAY_TIMEOUT_MS) {
            r.partial = true;
            return true;
        }
        return false;
    }

    /**
     * Relays in use by the proxy that carries the game, busiest first, at most {@link #MAX_RELAYS}. With a known
     * accepting process: relays owned by it (or by another process of the same product, e.g. ExitLag's service).
     * Without one (tunnel adapter or unidentified proxy): relays of known optimizers/VPNs.
     */
    static List<Snapshot.Relay> candidates(Snapshot s, int acceptedPid, String proxyName) {
        List<Snapshot.Relay> out = new ArrayList<Snapshot.Relay>();
        if (s == null) {
            return out;
        }
        for (Snapshot.Relay r : s.relays) {
            if (r.inUse && !r.service && ownedBy(r, acceptedPid, proxyName)) {
                out.add(r);
                if (out.size() >= MAX_RELAYS) {
                    break;
                }
            }
        }
        return out;
    }

    static boolean ownedBy(Snapshot.Relay r, int acceptedPid, String proxyName) {
        if (acceptedPid > 0) {
            return r.pid == acceptedPid || proxyName != null && proxyName.equals(Proxies.label(r.process));
        }
        return Proxies.known(r.process) != null;
    }

    /** How many of {@code relays} have a finished trace that ended at or after {@code since}. */
    static int traced(Snapshot s, List<Snapshot.Relay> relays, long since) {
        int n = 0;
        for (Snapshot.Relay r : relays) {
            Snapshot.Path p = s == null ? null : s.path(r.pathKey);
            if (p != null && "done".equals(p.state) && p.updatedAt >= since) {
                n++;
            }
        }
        return n;
    }

    /** A copy of {@code s} whose relays are only those of the game's proxy (all of them, not just the top 6). */
    static Snapshot onlyGameProxy(Snapshot s, int acceptedPid, String proxyName) {
        Snapshot c = new Snapshot();
        c.captureOk = s.captureOk;
        c.status = s.status;
        c.icmpSeen = s.icmpSeen;
        c.proxyRunning = s.proxyRunning;
        c.proxyNames = s.proxyNames;
        c.paths = s.paths;
        c.events = s.events;
        List<Snapshot.Relay> rel = new ArrayList<Snapshot.Relay>();
        for (Snapshot.Relay r : s.relays) {
            if (ownedBy(r, acceptedPid, proxyName)) {
                rel.add(r);
            }
        }
        c.relays = Collections.unmodifiableList(rel);
        return c;
    }

    private void finish(Round r, Traceroute.TcpResult t, boolean proxy, double gamePing, Helper helper, long now) {
        boolean adv = helper.wanted();
        // relay data only when this round actually traced the proxy's relays
        Snapshot snap = adv && proxy && r.retraceAt > 0 ? helper.snapshot() : null;
        Snapshot game = snap == null ? null : onlyGameProxy(snap, t.acceptedPid, t.proxyName);
        List<RelayRanking.Entry> ranked = game != null
                ? RelayRanking.rank(game, gamePing, RelayRanking.DEFAULT_MAX)
                : Collections.<RelayRanking.Entry>emptyList();
        report = new RouteReport(r.startedAt, now, r.tr.destination, r.tr.port, r.tr, proxy,
                proxy ? (t.proxyName != null ? t.proxyName : "a local proxy") : null, adv, game, ranked, gamePing,
                r.note, r.partial);
        round = null;
    }

    // ------------------------------------------------------------------ loading view

    private Progress progress(Round r, Helper helper, Traceroute.TcpResult t, long now) {
        boolean wanted = helper.wanted();
        String[] labels = {"Check how the game connects", "Start the admin helper", "Find the relays carrying the game",
                "Trace the relays", "ICMP traceroute to the server"};
        String[] details = new String[5];
        int[] st = new int[5];
        // 1. TCP check
        if (t == null) {
            st[0] = Progress.ACTIVE;
            details[0] = r.tr.tcpStatus();
        } else {
            st[0] = Progress.DONE;
            details[0] = t.verdict == Traceroute.Verdict.PROXY_VERIFIED ? "via " + t.proxyName
                    : t.verdict == Traceroute.Verdict.LOCAL_PROXY ? "unidentified local proxy"
                    : t.verdict == Traceroute.Verdict.DIRECT ? "direct, no proxy" : "not verified";
        }
        boolean proxy = t != null && carriesGame(t);
        // 2-4. helper and relays
        if (!wanted) {
            st[1] = st[2] = st[3] = Progress.SKIPPED;
            details[1] = "advanced tracing is off";
        } else if (t != null && !proxy) {
            st[1] = st[2] = st[3] = Progress.SKIPPED;
            details[2] = "the game does not go through a proxy";
        } else {
            HelperClient.State hs = helper.state();
            if (hs == HelperClient.State.CONNECTED) {
                st[1] = Progress.DONE;
            } else if (hs == HelperClient.State.WAITING_FOR_UAC || hs == HelperClient.State.STARTING) {
                st[1] = Progress.ACTIVE;
                details[1] = helper.message();
            } else {
                st[1] = Progress.SKIPPED;
                details[1] = helper.message();
            }
            if (t == null || st[1] != Progress.DONE) {
                st[2] = st[3] = st[1] == Progress.SKIPPED ? Progress.SKIPPED : Progress.PENDING;
            } else {
                Snapshot s = helper.snapshot();
                List<Snapshot.Relay> c = candidates(s, t.acceptedPid, t.proxyName);
                int done = r.retraceAt == 0 ? 0 : traced(s, c, r.retraceAt);
                if (c.isEmpty()) {
                    st[2] = Progress.ACTIVE;
                    long left = r.retraceAt == 0 ? DISCOVERY_MS : Math.max(0, DISCOVERY_MS - (now - r.retraceAt));
                    details[2] = String.format(Locale.ROOT, "watching %s's traffic (%ds)", t.proxyName == null
                            ? "the proxy" : t.proxyName, (left + 999) / 1000);
                    st[3] = Progress.PENDING;
                } else {
                    st[2] = Progress.DONE;
                    details[2] = c.size() + " in use";
                    st[3] = done >= c.size() ? Progress.DONE : Progress.ACTIVE;
                    details[3] = done + " of " + c.size() + " traced";
                }
            }
        }
        // 5. ICMP
        int hops = r.tr.hops().size();
        st[4] = r.tr.icmpRunning() ? Progress.ACTIVE : Progress.DONE;
        details[4] = hops == 0 ? (r.tr.icmpRunning() ? "starting" : "") : "hop " + hops;
        double sum = 0;
        int counted = 0;
        for (int i = 0; i < 5; i++) {
            if (st[i] == Progress.SKIPPED) {
                continue;
            }
            counted++;
            sum += st[i] == Progress.DONE ? 1 : st[i] == Progress.ACTIVE ? 0.4 : 0;
        }
        if (st[4] == Progress.ACTIVE) {
            sum += Math.min(0.5, hops / 30.0); // ICMP advances hop by hop
        }
        double f = counted == 0 ? 1 : Math.min(0.99, sum / counted);
        r.shown = Math.max(r.shown, f);
        return new Progress(labels, details, st, r.shown);
    }
}
