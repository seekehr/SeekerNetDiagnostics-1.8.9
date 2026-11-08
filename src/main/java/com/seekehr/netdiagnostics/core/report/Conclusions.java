package com.seekehr.netdiagnostics.core.report;

import com.seekehr.netdiagnostics.core.mc.McStatusClient;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Generates plain-language conclusions that only claim what the measurements support. */
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
        double var = s.jitter;
        if (var <= 2.5) {
            return "excellent";
        }
        if (var <= 6) {
            return "good";
        }
        if (var <= 15) {
            return "fair";
        }
        return "unstable";
    }

    /** Headline + supporting lines for a finished (or running) run. */
    public static List<String> describe(TestRun run) {
        List<String> out = new ArrayList<String>();
        StatSummary s = run.summary();
        String endpoint = (run.resolvedAddress != null ? run.resolvedAddress : run.connectHost) + ":" + run.connectPort;
        if (run.state == TestRun.State.FAILED && run.dns != null && !run.dns.resolved()) {
            out.add("DNS resolution failed for " + run.connectHost + ". No connection was attempted, so no latency "
                    + "could be measured.");
            return out;
        }
        int measured = run.measuredAttempts();
        if (s.isEmpty()) {
            if (measured == 0) {
                out.add(run.finished() ? "No TCP handshake samples were collected." : "Waiting for results...");
            } else {
                out.add("No TCP handshake to " + endpoint + " completed (" + failureBreakdown(run) + "). "
                        + "A valid TCP handshake latency cannot be measured for this destination.");
                if (run.count(ProbeOutcome.TIMEOUT) > 0) {
                    out.add("Timeouts mean no reply arrived in time; they do not by themselves prove packet loss. "
                            + "The port may be filtered, or the server may only accept traffic from certain routes.");
                }
                if (run.count(ProbeOutcome.REFUSED) > 0) {
                    out.add("'Refused' means the host answered but no service is listening on port "
                            + run.connectPort + ".");
                }
            }
        } else {
            String stab = stabilityWord(s);
            out.add(String.format(Locale.ROOT,
                    "Your connection to the selected TCP endpoint (%s) averaged %s (median %s). Stability was %s, "
                            + "with %s of RTT variation (jitter) between consecutive handshakes.",
                    endpoint, fmt(s.mean), fmt(s.median), stab, fmt(s.jitter)));
            out.add(String.format(Locale.ROOT, "Range %s to %s; 95%% of handshakes completed within %s. Based on %d "
                    + "successful handshake%s.", fmt(s.min), fmt(s.max), fmt(s.p95), s.count, s.count == 1 ? "" : "s"));
            int failed = run.failedAttempts();
            if (failed > 0) {
                out.add(failed + " of " + measured + " attempts did not complete (" + failureBreakdown(run)
                        + "); failed attempts are excluded from the statistics.");
            }
            out.add("This is TCP connection-setup time, not Minecraft gameplay latency.");
        }
        McStatusClient.Result st = run.mcStatus;
        if (st != null) {
            out.addAll(describeStatus(st));
        }
        return out;
    }

    public static List<String> describeStatus(McStatusClient.Result st) {
        List<String> out = new ArrayList<String>();
        if (st.tcpOutcome != ProbeOutcome.SUCCESS) {
            out.add("Minecraft status ping: the TCP connection failed (" + st.tcpOutcome.label + "): " + st.error);
            return out;
        }
        if (!st.statusOk) {
            out.add("Minecraft status ping: TCP connection succeeded in " + fmt(st.tcpConnectMs)
                    + ", but the Minecraft status request failed: " + st.error + ".");
            return out;
        }
        StringBuilder sb = new StringBuilder("Minecraft status ping (application-level, includes server/proxy "
                + "processing): ");
        if (st.pingOk) {
            sb.append("ping/pong ").append(fmt(st.pingPongMs)).append(", ");
        }
        sb.append("status response ").append(fmt(st.statusResponseMs)).append(", TCP connect ")
                .append(fmt(st.tcpConnectMs)).append('.');
        out.add(sb.toString());
        if (!st.pingOk && st.error != null) {
            out.add("Ping/pong step failed: " + st.error + ".");
        }
        if (st.status != null) {
            StringBuilder s = new StringBuilder("Server reports: ");
            s.append(st.status.versionName == null ? "unknown version" : st.status.versionName);
            if (st.status.onlinePlayers >= 0) {
                s.append(", ").append(st.status.onlinePlayers).append('/').append(st.status.maxPlayers)
                        .append(" players");
            }
            out.add(s.toString());
        }
        if (st.protocolNote != null) {
            out.add(st.protocolNote);
        }
        return out;
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

    /** Concise multi-line summary suitable for the clipboard. */
    public static String clipboardSummary(TestRun run) {
        StringBuilder sb = new StringBuilder();
        StatSummary s = run.summary();
        sb.append("SeekerNetDiagnostics - ").append(run.kind.label).append(" test\n");
        sb.append("Target: ").append(run.target.display());
        if (run.resolvedAddress != null) {
            sb.append(" -> ").append(run.resolvedAddress).append(':').append(run.connectPort);
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
            sb.append("TCP handshake: no successful samples (").append(failureBreakdown(run)).append(")\n");
        } else {
            sb.append(String.format(Locale.ROOT,
                    "TCP handshake: n=%d median=%.1f mean=%.1f min=%.1f max=%.1f p95=%.1f p99=%.1f sd=%.1f "
                            + "jitter=%.1f ms; failures: %s%n",
                    s.count, s.median, s.mean, s.min, s.max, s.p95, s.p99, s.stdDev, s.jitter, failureBreakdown(run)));
        }
        McStatusClient.Result st = run.mcStatus;
        if (st != null) {
            for (String line : describeStatus(st)) {
                sb.append(line).append('\n');
            }
        }
        sb.append("Origin: Minecraft client JVM. ").append(EXITLAG_NOT_VERIFIED);
        return sb.toString();
    }
}
