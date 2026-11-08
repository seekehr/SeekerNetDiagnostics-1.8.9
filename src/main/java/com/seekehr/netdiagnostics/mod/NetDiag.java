package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.SeekerNetDiagnostics;
import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.config.Settings;
import com.seekehr.netdiagnostics.core.monitor.LatencyMonitor;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.quality.QualityEngine;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.report.ReportWriter;
import com.seekehr.netdiagnostics.core.test.ResultStore;
import com.seekehr.netdiagnostics.core.test.TestEngine;
import com.seekehr.netdiagnostics.core.test.TestRun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraftforge.common.ForgeVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

    public final File dataDir;
    public final File reportsDir;
    public final SettingsStore settingsStore;
    private volatile Settings settings;
    public final TestEngine engine = new TestEngine();
    public final ResultStore results;
    public final QualityEngine quality = new QualityEngine();
    public final LatencyMonitor monitor;
    private final ExecutorService io;
    private final Set<Long> chatReported = ConcurrentHashMap.newKeySet();
    private volatile CompareSession compare;

    private NetDiag(File configDir) {
        dataDir = new File(configDir, "netscope");
        reportsDir = new File(dataDir, "reports");
        settingsStore = new SettingsStore(new File(configDir, SeekerNetDiagnostics.MODID + ".cfg"));
        settings = settingsStore.load();
        results = new ResultStore(new File(dataDir, "history.json"));
        monitor = new LatencyMonitor(quality, settings.graphHistoryMinutes, settings.monitorIntervalMs);
        io = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(16),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "SeekerNetDiagnostics-io");
                        t.setDaemon(true);
                        return t;
                    }
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
        ((ThreadPoolExecutor) io).allowCoreThreadTimeOut(true);
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
                int n = results.load();
                LOG.info("Loaded {} saved test results", n);
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
        Settings old = settings;
        settings = s.sanitize();
        settingsStore.save(settings);
        monitor.resize(settings.graphHistoryMinutes, settings.monitorIntervalMs);
        if (old.packetInstrumentation != settings.packetInstrumentation) {
            ClientEvents.refreshInstrumentation();
        }
    }

    public CompareSession compareSession() {
        return compare;
    }

    public void setCompareSession(CompareSession c) {
        compare = c;
    }

    // ------------------------------------------------------------------ tests

    public TestRun startTest(TestRun.Kind kind, Target target, int samples, String profile, String exitLagDesc,
                             boolean reportToChat) {
        Settings s = settings;
        TestRun run = new TestRun(kind, target, samples, s.warmupSamples, s.intervalMs, s.tcpTimeoutMs,
                s.statusTimeoutMs, profile, exitLagDesc);
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
        if (run.state != TestRun.State.CANCELLED || run.measuredAttempts() > 0) {
            results.add(run);
            quality.onTcpRun(run, results.previousFor(run));
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
        quality.clear();
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

    public static String currentRemoteAddress() {
        NetHandlerPlayClient h = Minecraft.getMinecraft().getNetHandler();
        if (h == null || h.getNetworkManager() == null || h.getNetworkManager().isLocalChannel()) {
            return null;
        }
        java.net.SocketAddress a = h.getNetworkManager().getRemoteAddress();
        if (a instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress ia = (java.net.InetSocketAddress) a;
            return (ia.getAddress() != null ? ia.getAddress().getHostAddress() : ia.getHostString()) + ":" + ia.getPort();
        }
        return a == null ? null : a.toString();
    }

    // ------------------------------------------------------------------ export

    public static Map<String, Object> environment() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mod", SeekerNetDiagnostics.NAME + " " + SeekerNetDiagnostics.VERSION);
        m.put("minecraftVersion", ForgeVersion.mcVersion);
        m.put("forgeVersion", ForgeVersion.getVersion());
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("javaVendor", System.getProperty("java.vendor"));
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                + System.getProperty("os.arch") + ")");
        m.put("measurementOrigin", "In-process sockets opened by the running Minecraft JVM");
        return m;
    }

    public Map<String, Object> settingsMap() {
        Settings s = settings;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("quickSamples", s.quickSamples);
        m.put("detailedSamples", s.detailedSamples);
        m.put("warmupSamples", s.warmupSamples);
        m.put("intervalMs", s.intervalMs);
        m.put("tcpTimeoutMs", s.tcpTimeoutMs);
        m.put("statusTimeoutMs", s.statusTimeoutMs);
        m.put("monitorIntervalMs", s.monitorIntervalMs);
        m.put("packetInstrumentation", s.packetInstrumentation);
        m.put("preferIpv4", s.preferIpv4);
        return m;
    }

    public interface ExportCallback {
        void done(List<File> files, String error);
    }

    /** Collects data on the client thread, writes files on the IO thread, calls back on the client thread. */
    public void exportAll(final ExportCallback cb) {
        final Settings s = settings;
        final ReportWriter.Input in = new ReportWriter.Input();
        in.environment = environment();
        in.settings = settingsMap();
        in.runs = new ArrayList<TestRun>(results.all());
        java.util.Collections.reverse(in.runs); // chronological
        CompareSession c = compare;
        in.comparison = c == null ? null : c.toMap();
        in.monitor = monitor.toMap(System.currentTimeMillis());
        in.alerts = quality.alerts();
        in.includeLocalAddresses = s.exportIncludeLocalAddresses;
        if (!s.exportJson && !s.exportCsv) {
            cb.done(new ArrayList<File>(), "Both JSON and CSV export are disabled in settings.");
            return;
        }
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

    /** Writes a small text file with the target for use in external tools; returns via callback. */
    public void exportTarget(final String host, final String ip, final int port, final ExportCallback cb) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<File> files = new ArrayList<File>();
                String err = null;
                try {
                    if (!reportsDir.isDirectory() && !reportsDir.mkdirs()) {
                        throw new IOException("cannot create " + reportsDir);
                    }
                    String safe = host.replaceAll("[^A-Za-z0-9._-]", "_");
                    File f = new File(reportsDir, "target-" + safe + "-" + port + ".txt");
                    String target = ip != null ? ip : host;
                    String text = "SeekerNetDiagnostics target export\r\n"
                            + "Host: " + host + "\r\nResolved IP: " + (ip == null ? "(not resolved yet)" : ip)
                            + "\r\nPort: " + port + "\r\n\r\n"
                            + "Run these yourself in a terminal if you want path information. Note that ICMP-based "
                            + "tools usually bypass ExitLag and may not follow the optimized game route:\r\n"
                            + "  tracert " + target + "\r\n  pathping " + target + "\r\n\r\n"
                            + Conclusions.EXITLAG_NOT_VERIFIED + "\r\n";
                    ReportWriter.writeFile(f, text);
                    files.add(f);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final List<File> fl = files;
                final String e = err;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fl, e);
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
