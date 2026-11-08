package com.seekehr.netdiagnostics.mod.commands;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
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
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /snd} (aliases {@code /seekernetdiag}, {@code /seekernetdiagnostics}). Registered with Forge's
 * ClientCommandHandler, so it runs locally and is never sent to the server.
 */
public final class CommandSnd extends CommandBase {
    private static final String[] SUBS = {"test", "compare", "export", "settings", "cancel", "history", "help"};

    @Override
    public String getCommandName() {
        return "snd";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("seekernetdiag", "seekernetdiagnostics");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/snd [test|compare|export|settings|cancel|history|help]";
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
        if (args.length == 2 && "compare".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "status");
        }
        if ((args.length == 3 || args.length == 4) && "test".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "quick", "detailed");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        final NetDiag nd = NetDiag.get();
        if (args.length == 0) {
            ClientEvents.openNextTick(new GuiDashboard(null));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            if ("test".equals(sub)) {
                test(nd, args);
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
        Chat.info("SeekerNetDiagnostics (all commands run locally):");
        Chat.info("/snd - open the dashboard");
        Chat.info("/snd test <host> [port] [quick|detailed] - repeated Minecraft ping from this game process");
        Chat.info("/snd compare [status] - compare ExitLag routes");
        Chat.info("/snd export - write JSON/CSV reports to config/netscope/reports/");
        Chat.info("/snd settings | /snd cancel | /snd history");
    }

    private static void test(NetDiag nd, String[] args) {
        if (args.length < 2) {
            ClientEvents.openNextTick(new GuiTestTarget(null, "", "", false));
            return;
        }
        String port = null;
        boolean detailed = false;
        for (int i = 2; i < args.length; i++) {
            if ("detailed".equalsIgnoreCase(args[i])) {
                detailed = true;
            } else if (!"quick".equalsIgnoreCase(args[i])) {
                port = args[i];
            }
        }
        Target t = Target.parse(args[1], port);
        int samples = detailed ? nd.settings().detailedSamples : nd.settings().quickSamples;
        nd.startTest(t, samples, null, null, true);
        Chat.info("Pinging " + t.display() + " " + samples + " times, one every " + nd.settings().intervalMs
                + " ms, from this Minecraft process. Results will appear here; /snd cancel to stop.");
    }

    private static void compareStatus(NetDiag nd) {
        CompareSession c = nd.compareSession();
        if (c == null || c.profiles().isEmpty()) {
            Chat.info("No route comparison in progress. Use /snd compare to start one.");
            return;
        }
        List<CompareSession.Profile> ps = c.profiles();
        Chat.info("Route comparison for " + c.target().display() + ":");
        for (CompareSession.Profile p : ps) {
            StatSummary s = p.summary();
            Chat.info(" " + p.label + ": " + (s.isEmpty() ? "no successful pings"
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
            Chat.info(" " + r.target.display() + (r.profileLabel == null ? "" : " [" + r.profileLabel + "]") + ": "
                    + (s.isEmpty() ? "no successful pings (" + Conclusions.failureBreakdown(r) + ")"
                    : "median " + Conclusions.fmt(s.median) + ", p95 " + Conclusions.fmt(s.p95) + ", "
                    + s.count + "/" + r.measuredAttempts() + " ok"));
        }
    }
}
