package com.seekehr.netdiagnostics.core.report;

import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plain-language results that only claim what the measurements support. */
public final class Conclusions {
    public static final String EXITLAG_NOT_VERIFIED =
            "ExitLag routing not verified. Confirm this destination is being optimized in ExitLag's real-time monitor.";

    private Conclusions() {
    }

    public static String fmt(double ms) {
        if (Double.isNaN(ms)) {
            return "n/a";
        }
        return ms < 10 ? String.format(Locale.ROOT, "%.1f ms", ms) : String.format(Locale.ROOT, "%.0f ms", ms);
    }

    public static String stabilityWord(StatSummary s) {
        if (s.count < 3) {
            return "unknown (too few samples)";
        }
        if (s.jitter <= 2.5) {
            return "excellent";
        }
        if (s.jitter <= 6) {
            return "good";
        }
        if (s.jitter <= 15) {
            return "fair";
        }
        return "unstable";
    }

    public static String endpoint(TestRun run) {
        String host = run.resolvedAddress != null ? run.resolvedAddress : run.connectHost;
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + run.connectPort;
    }

    /** Headline + supporting lines for a finished (or running) run. */
    public static List<String> describe(TestRun run) {
        List<String> out = new ArrayList<String>();
        if (run.state == TestRun.State.FAILED && run.dns != null && !run.dns.resolved()) {
            out.add("DNS resolution failed for " + run.connectHost + ". Nothing was contacted, so no ping could be "
                    + "measured.");
            return out;
        }
        StatSummary s = run.summary();
        int measured = run.measuredAttempts();
        if (s.isEmpty()) {
            if (measured == 0) {
                out.add(run.finished() ? "No samples were collected." : "Waiting for results...");
                return out;
            }
            int noPong = run.count(ProbeOutcome.NO_PONG);
            if (noPong > 0 && noPong == measured) {
                out.add("The server at " + endpoint(run) + " answered status requests but never answered the ping, so "
                        + "a ping time cannot be measured. Its status responses took a median of "
                        + fmt(run.statusSummary().median) + ", which includes server processing time.");
            } else {
                out.add("No Minecraft ping to " + endpoint(run) + " completed (" + failureBreakdown(run) + "). "
                        + "A valid ping cannot be measured for this destination.");
                if (run.count(ProbeOutcome.TIMEOUT) > 0) {
                    out.add("Connect timeouts mean no reply arrived in time. That alone does not prove packet loss: "
                            + "the server may be down or filtering connections from your route.");
                }
                if (run.count(ProbeOutcome.PROTOCOL_ERROR) > 0) {
                    out.add("The port answered, but not with a Minecraft status response. Check the port number.");
                }
            }
        } else {
            out.add(String.format(Locale.ROOT,
                    "Your Minecraft ping to %s averaged %s (median %s). Stability was %s, with %s of variation "
                            + "between consecutive pings.",
                    endpoint(run), fmt(s.mean), fmt(s.median), stabilityWord(s), fmt(s.jitter)));
            out.add(String.format(Locale.ROOT, "Range %s to %s; 95%% of pings were within %s. Based on %d "
                    + "successful ping%s.", fmt(s.min), fmt(s.max), fmt(s.p95), s.count, s.count == 1 ? "" : "s"));
            int failed = run.failedAttempts();
            if (failed > 0) {
                out.add(failed + " of " + measured + " samples failed (" + failureBreakdown(run)
                        + ") and are excluded from the statistics.");
            }
        }
        out.add(interceptLine(run));
        McProtocol.StatusInfo si = run.serverInfo;
        if (si != null) {
            StringBuilder b = new StringBuilder("Server reports: ");
            b.append(si.versionName == null ? "unknown version" : si.versionName);
            if (si.onlinePlayers >= 0) {
                b.append(", ").append(si.onlinePlayers).append('/').append(si.maxPlayers).append(" players");
            }
            out.add(b.toString());
        }
        if (run.protocolNote != null) {
            out.add(run.protocolNote);
        }
        return out;
    }

    /** Explains what the TCP connect time says about local interception, without overclaiming. */
    public static String interceptLine(TestRun run) {
        StatSummary c = run.connectSummary();
        if (c.isEmpty()) {
            return "No connection was established, so local interception could not be checked.";
        }
        if (run.localInterceptDetected()) {
            return "Connections were accepted locally in about " + fmt(c.median) + ", faster than any remote server "
                    + "can answer. A local proxy (such as ExitLag) is handling this connection. The ping above is "
                    + "still measured end to end, but this does not show which route the proxy used.";
        }
        return "No local interception detected (TCP connect " + fmt(c.median) + "). If you expected ExitLag to "
                + "optimize this server, check ExitLag's real-time monitor.";
    }

    public static String failureBreakdown(TestRun run) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ProbeOutcome, Integer> e : run.outcomeCounts().entrySet()) {
            if (e.getKey() == ProbeOutcome.SUCCESS) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getValue()).append(' ').append(e.getKey().label.toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "no failures" : sb.toString();
    }

    /** Concise multi-line summary for the clipboard. */
    public static String clipboardSummary(TestRun run) {
        StringBuilder sb = new StringBuilder();
        StatSummary s = run.summary();
        sb.append("SeekerNetDiagnostics - Minecraft ping test\n");
        sb.append("Target: ").append(run.target.display());
        if (run.resolvedAddress != null) {
            sb.append(" -> ").append(endpoint(run));
        }
        sb.append('\n');
        if (run.profileLabel != null) {
            sb.append("Profile: ").append(run.profileLabel);
            if (run.exitLagDescription != null && !run.exitLagDescription.isEmpty()) {
                sb.append(" (").append(run.exitLagDescription).append(')');
            }
            sb.append('\n');
        }
        if (s.isEmpty()) {
            sb.append("Ping: no successful samples (").append(failureBreakdown(run)).append(")\n");
        } else {
            sb.append(String.format(Locale.ROOT,
                    "Ping: n=%d median=%.1f mean=%.1f min=%.1f max=%.1f p95=%.1f p99=%.1f sd=%.1f jitter=%.1f ms; "
                            + "failures: %s%n",
                    s.count, s.median, s.mean, s.min, s.max, s.p95, s.p99, s.stdDev, s.jitter, failureBreakdown(run)));
        }
        sb.append(interceptLine(run)).append('\n');
        sb.append("Origin: Minecraft client JVM. ").append(EXITLAG_NOT_VERIFIED);
        return sb.toString();
    }
}
