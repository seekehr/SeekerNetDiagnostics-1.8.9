package com.seekehr.netdiagnostics.mod.commands;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.monitor.LatencyMonitor;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;
import com.seekehr.netdiagnostics.gui.GuiCompare;
import com.seekehr.netdiagnostics.gui.GuiDashboard;
import com.seekehr.netdiagnostics.gui.GuiSettings;
import com.seekehr.netdiagnostics.gui.GuiTestTarget;
import com.seekehr.netdiagnostics.mod.Chat;
import com.seekehr.netdiagnostics.mod.ClientEvents;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ns}: command-line access to every diagnostic. Registered with Forge's
 * ClientCommandHandler, so it is executed locally and never sent to the server.
 */
public final class CommandNs extends CommandBase {
    private static final String[] SUBS = {"test", "tcp", "monitor", "compare", "export", "settings", "cancel",
            "history", "help"};

    @Override
    public String getCommandName() {
        return "ns";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("netscope");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ns [test|tcp|monitor|compare|export|settings|cancel|history|help]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBS);
        }
        if (args.length == 2 && "monitor".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "on", "off", "status", "clear");
        }
        if (args.length == 2 && "compare".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "status");
        }
        if (args.length == 4 && "test".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "quick", "detailed");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        NetDiag nd = NetDiag.get();
        if (args.length == 0) {
            ClientEvents.openNextTick(new GuiDashboard(null));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            if ("test".equals(sub)) {
                test(nd, args, TestRun.Kind.FULL);
            } else if ("tcp".equals(sub)) {
                test(nd, args, TestRun.Kind.TCP);
            } else if ("monitor".equals(sub)) {
                monitor(nd, args);
            } else if ("compare".equals(sub)) {
                if (args.length > 1 && "status".equalsIgnoreCase(args[1])) {
                    compareStatus(nd);
                } else {
                    ClientEvents.openNextTick(new GuiCompare(null));
                }
            } else if ("export".equals(sub)) {
                Chat.info("Exporting reports...");
                nd.exportAll(new NetDiag.ExportCallback() {
                    @Override
                    public void done(List<File> files, String error) {
                        if (error != null) {
                            Chat.error("Export failed: " + error);
                        } else {
                            Chat.good("Exported " + files.size() + " file(s) to " + nd.reportsDir.getPath());
                            for (File f : files) {
                                Chat.info(" - " + f.getName());
                            }
                        }
                    }
                });
            } else if ("settings".equals(sub)) {
                ClientEvents.openNextTick(new GuiSettings(null));
            } else if ("cancel".equals(sub)) {
                int n = nd.engine.activeRuns().size();
                nd.engine.cancelAll();
                Chat.info(n == 0 ? "No test is running." : "Cancelling " + n + " test(s).");
            } else if ("history".equals(sub)) {
                history(nd);
            } else {
                help();
            }
        } catch (IllegalArgumentException e) {
            Chat.error(e.getMessage());
        } catch (IllegalStateException e) {
            Chat.error(e.getMessage());
        }
    }

    private static void help() {
        Chat.info("SeekerNetDiagnostics commands (all run locally):");
        Chat.info("/ns or /snm - open the dashboard");
        Chat.info("/ns test <host> [port] [quick|detailed] - TCP handshake samples + Minecraft status ping");
        Chat.info("/ns tcp <host> <port> - TCP handshake samples to one explicit port");
        Chat.info("/ns monitor [on|off|status|clear] - live connection monitor");
        Chat.info("/ns compare [status] - compare ExitLag routes");
        Chat.info("/ns export - write JSON/CSV reports to config/netscope/reports/");
        Chat.info("/ns settings | /ns cancel | /ns history");
    }

    private static void test(NetDiag nd, String[] args, TestRun.Kind kind) {
        if (args.length < 2) {
            if (kind == TestRun.Kind.FULL) {
                ClientEvents.openNextTick(new GuiTestTarget(null, "", "", false));
                return;
            }
            throw new IllegalArgumentException("Usage: /ns tcp <host> <port>");
        }
        String port = args.length >= 3 && !isMode(args[2]) ? args[2] : null;
        if (kind == TestRun.Kind.TCP && port == null && args[1].indexOf(':') < 0) {
            throw new IllegalArgumentException("Usage: /ns tcp <host> <port> (the port must be given explicitly)");
        }
        boolean detailed = false;
        for (int i = 2; i < args.length; i++) {
            if ("detailed".equalsIgnoreCase(args[i])) {
                detailed = true;
            }
        }
        Target t = Target.parse(args[1], port);
        int samples = detailed ? nd.settings().detailedSamples : nd.settings().quickSamples;
        TestRun run = nd.startTest(kind, t, samples, null, null, true);
        Chat.info("Testing " + t.display() + " (" + kind.label + ", " + samples + " samples, one every "
                + nd.settings().intervalMs + " ms) from this Minecraft process. Results will appear here; "
                + "/ns cancel to stop.");
        if (run == null) {
            Chat.error("Could not start the test.");
        }
    }

    private static boolean isMode(String s) {
        return "quick".equalsIgnoreCase(s) || "detailed".equalsIgnoreCase(s);
    }

    private static void monitor(NetDiag nd, String[] args) {
        LatencyMonitor m = nd.monitor;
        String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("off".equals(a)) {
            m.stop();
            Chat.info("Live monitor stopped.");
            return;
        }
        if ("clear".equals(a)) {
            m.clearHistory();
            Chat.info("Live monitor history cleared.");
            return;
        }
        if ("on".equals(a) || "".equals(a)) {
            m.start(System.currentTimeMillis());
        }
        if ("".equals(a)) {
            ClientEvents.openNextTick(new GuiDashboard(null));
        }
        StatSummary s = m.summary(0);
        Chat.info("Live monitor: " + (m.active() ? "active" : "stopped") + "; server: "
                + (m.serverName() == null ? "not connected" : m.serverName())
                + (m.remoteAddress() == null ? "" : " (" + m.remoteAddress() + ")"));
        int live = ClientEvents.ownReportedLatency(Minecraft.getMinecraft());
        Chat.info("Minecraft-reported latency now: " + (live < 0 ? "n/a" : live + " ms")
                + " (server-computed, smoothed; not raw network RTT)");
        if (!s.isEmpty()) {
            Chat.info(String.format(Locale.ROOT, "Session: n=%d min %.0f / avg %.0f / max %.0f ms; p50 %.0f, p95 %.0f, "
                    + "p99 %.0f ms; jitter %.1f ms; disconnects %d", s.count, s.min, s.mean, s.max, s.median, s.p95,
                    s.p99, s.jitter, m.disconnects()));
        }
    }

    private static void compareStatus(NetDiag nd) {
        CompareSession c = nd.compareSession();
        if (c == null || c.profiles().isEmpty()) {
            Chat.info("No route comparison in progress. Use /ns compare to start one.");
            return;
        }
        List<CompareSession.Profile> ps = c.profiles();
        Chat.info("Route comparison for " + c.target().display() + ":");
        for (CompareSession.Profile p : ps) {
            StatSummary s = p.summary();
            Chat.info(" " + p.label + ": " + (s.isEmpty() ? "no successful samples"
                    : String.format(Locale.ROOT, "median %.1f ms, p95 %.1f ms, n=%d, failures %d", s.median, s.p95,
                    s.count, p.failures())));
        }
        for (int i = 1; i < ps.size(); i++) {
            Chat.info(c.compare(ps.get(0), ps.get(i)).recommendation(ps.get(0).label, ps.get(i).label));
        }
    }

    private static void history(NetDiag nd) {
        List<TestRun> runs = nd.results.all();
        if (runs.isEmpty()) {
            Chat.info("No saved test results yet.");
            return;
        }
        Chat.info("Most recent results:");
        for (TestRun r : runs.subList(0, Math.min(8, runs.size()))) {
            StatSummary s = r.summary();
            Chat.info(" " + r.target.display() + " [" + r.kind.label + "] "
                    + (s.isEmpty() ? "no successful handshakes (" + Conclusions.failureBreakdown(r) + ")"
                    : "median " + Conclusions.fmt(s.median) + ", p95 " + Conclusions.fmt(s.p95) + ", "
                    + s.count + "/" + r.measuredAttempts() + " ok"));
        }
    }

    static List<String> subs() {
        return Arrays.asList(SUBS);
    }
}
