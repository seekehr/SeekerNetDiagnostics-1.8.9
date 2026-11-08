package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.SeekerNetDiagnostics;
import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.config.Settings;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.report.ReportWriter;
import com.seekehr.netdiagnostics.core.test.ResultStore;
import com.seekehr.netdiagnostics.core.test.TestEngine;
import com.seekehr.netdiagnostics.core.test.TestRun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.common.ForgeVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Central service holder for the client mod. Created once during mod initialisation. */
public final class NetDiag {
    public static final Logger LOG = LogManager.getLogger(SeekerNetDiagnostics.NAME);
    private static NetDiag instance;

    public final File reportsDir;
    public final SettingsStore settingsStore;
    private volatile Settings settings;
    public final TestEngine engine = new TestEngine();
    public final ResultStore results;
    private final ExecutorService io;
    private final Set<Long> chatReported = ConcurrentHashMap.newKeySet();
    private volatile CompareSession compare;

    private NetDiag(File configDir) {
        File dataDir = new File(configDir, "netscope");
        reportsDir = new File(dataDir, "reports");
        settingsStore = new SettingsStore(new File(configDir, SeekerNetDiagnostics.MODID + ".cfg"));
        settings = settingsStore.load();
        results = new ResultStore(new File(dataDir, "history.json"));
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(16), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "SeekerNetDiagnostics-io");
                        t.setDaemon(true);
                        return t;
                    }
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
        ex.allowCoreThreadTimeOut(true);
        io = ex;
        engine.addListener(new TestEngine.Listener() {
            @Override
            public void onUpdate(TestRun run) {
                if (run.finished()) {
                    onRunFinished(run);
                }
            }
        });
        io.execute(new Runnable() {
            @Override
            public void run() {
                LOG.info("Loaded {} saved test results", results.load());
            }
        });
    }

    public static NetDiag init(File configDir) {
        if (instance == null) {
            instance = new NetDiag(configDir);
        }
        return instance;
    }

    public static NetDiag get() {
        return instance;
    }

    public Settings settings() {
        return settings;
    }

    public void applySettings(Settings s) {
        settings = s.sanitize();
        settingsStore.save(settings);
    }

    public CompareSession compareSession() {
        return compare;
    }

    public void setCompareSession(CompareSession c) {
        compare = c;
    }

    // ------------------------------------------------------------------ tests

    public TestRun startTest(Target target, int samples, String profile, String exitLagDesc, boolean reportToChat) {
        Settings s = settings;
        TestRun run = new TestRun(target, samples, s.warmupSamples, s.intervalMs, s.timeoutMs, profile, exitLagDesc);
        if (reportToChat) {
            chatReported.add(run.id);
        }
        try {
            return engine.submit(run, s.copy());
        } catch (IllegalStateException e) {
            chatReported.remove(run.id);
            throw e;
        }
    }

    private void onRunFinished(final TestRun run) {
        if (run.measuredAttempts() > 0) {
            results.add(run);
            saveHistoryAsync();
        }
        if (chatReported.remove(run.id)) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    Chat.runResult(run);
                }
            });
        }
    }

    public void saveHistoryAsync() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    results.save();
                } catch (IOException e) {
                    LOG.warn("Could not save test history: {}", e.getMessage());
                }
            }
        });
    }

    public void clearHistory() {
        results.clear();
        saveHistoryAsync();
    }

    // ------------------------------------------------------------------ current server

    /** The multiplayer server currently connected to, or null (singleplayer / menu). */
    public static ServerData currentServer() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isIntegratedServerRunning() || mc.getNetHandler() == null) {
            return null;
        }
        return mc.getCurrentServerData();
    }

    public static Target currentServerTarget() {
        ServerData d = currentServer();
        if (d == null || d.serverIP == null) {
            return null;
        }
        try {
            return Target.parse(d.serverIP, null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The ping Minecraft shows in the tab list for the local player, or -1 (read-only). */
    public static int tabListPing() {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h == null || mc.thePlayer == null || currentServer() == null) {
            return -1;
        }
        NetworkPlayerInfo info = h.getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? -1 : info.getResponseTime();
    }

    // ------------------------------------------------------------------ export

    public static Map<String, Object> environment() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mod", SeekerNetDiagnostics.NAME + " " + SeekerNetDiagnostics.VERSION);
        m.put("minecraftVersion", ForgeVersion.mcVersion);
        m.put("forgeVersion", ForgeVersion.getVersion());
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        return m;
    }

    public Map<String, Object> settingsMap() {
        Settings s = settings;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("quickSamples", s.quickSamples);
        m.put("detailedSamples", s.detailedSamples);
        m.put("warmupSamples", s.warmupSamples);
        m.put("intervalMs", s.intervalMs);
        m.put("timeoutMs", s.timeoutMs);
        m.put("preferIpv4", s.preferIpv4);
        return m;
    }

    public interface ExportCallback {
        void done(List<File> files, String error);
    }

    /** Collects data on the client thread, writes files on the IO thread, calls back on the client thread. */
    public void exportAll(final ExportCallback cb) {
        final Settings s = settings;
        if (!s.exportJson && !s.exportCsv) {
            cb.done(new ArrayList<File>(), "Both JSON and CSV export are disabled in settings.");
            return;
        }
        final ReportWriter.Input in = new ReportWriter.Input();
        in.environment = environment();
        in.settings = settingsMap();
        in.runs = new ArrayList<TestRun>(results.all());
        Collections.reverse(in.runs); // chronological
        CompareSession c = compare;
        in.comparison = c == null ? null : c.toMap();
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<File> files = null;
                String err = null;
                try {
                    files = ReportWriter.write(reportsDir, in, s.exportJson, s.exportCsv);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final List<File> f = files;
                final String e = err;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(f, e);
                    }
                });
            }
        });
    }

    public void shutdown() {
        engine.shutdown();
        try {
            results.save();
        } catch (IOException e) {
            LOG.warn("Could not save test history on shutdown: {}", e.getMessage());
        }
        io.shutdown();
    }
}
