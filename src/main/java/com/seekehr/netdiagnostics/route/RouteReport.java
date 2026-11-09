package com.seekehr.netdiagnostics.route;

import com.seekehr.netdiagnostics.core.monitor.Traceroute;
import com.seekehr.netdiagnostics.helper.RelayRanking;
import com.seekehr.netdiagnostics.helper.Snapshot;

import java.util.Collections;
import java.util.List;

/**
 * The finished result of one route analysis round. Immutable once built: the Traceroute tab shows it as-is until the
 * next round finishes, so nothing on screen changes half-way through a measurement.
 */
public final class RouteReport {
    public final long startedAt;
    public final long finishedAt;
    public final String destination;
    public final int port;
    /** The finished TCP check and ICMP traceroute of this round. */
    public final Traceroute trace;
    /** True when this round's TCP check verified that a proxy (identified or not) carries the game connection. */
    public final boolean proxyCarriesGame;
    /** Name of that proxy ("ExitLag", a process or adapter name), or null. */
    public final String proxyName;
    /** Whether advanced tracing took part in this round. */
    public final boolean advancedUsed;
    /**
     * Helper data frozen at the end of the round, with relays limited to the ones owned by the proxy that carries
     * the game. Null when advanced tracing did not take part.
     */
    public final Snapshot snapshot;
    /** Relays ranked against the game ping (accepted first). Empty unless a proxy carries the game. */
    public final List<RelayRanking.Entry> ranked;
    public final double gamePing;
    /** Something the user should know about advanced tracing in this round, or null. */
    public final String advancedNote;
    /** The round hit a time limit before every relay trace finished. */
    public final boolean partial;

    RouteReport(long startedAt, long finishedAt, String destination, int port, Traceroute trace,
                boolean proxyCarriesGame, String proxyName, boolean advancedUsed, Snapshot snapshot,
                List<RelayRanking.Entry> ranked, double gamePing, String advancedNote, boolean partial) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.destination = destination;
        this.port = port;
        this.trace = trace;
        this.proxyCarriesGame = proxyCarriesGame;
        this.proxyName = proxyName;
        this.advancedUsed = advancedUsed;
        this.snapshot = snapshot;
        this.ranked = Collections.unmodifiableList(ranked);
        this.gamePing = gamePing;
        this.advancedNote = advancedNote;
        this.partial = partial;
    }
}
