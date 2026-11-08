package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.config.Settings;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Persists {@link Settings} in a Forge configuration file (config/seekernetdiagnostics.cfg). */
public final class SettingsStore {
    private static final String TESTS = "tests";
    private static final String DISPLAY = "display";
    private static final String MONITOR = "monitor";
    private static final String EXPORT = "export";

    private final Configuration cfg;
    public boolean monitorOnStartup = true;

    public SettingsStore(File file) {
        cfg = new Configuration(file);
    }

    public Settings load() {
        cfg.load();
        Settings s = new Settings();
        s.quickSamples = cfg.getInt("quickSamples", TESTS, s.quickSamples, 3, 100,
                "TCP handshake samples for a quick test.");
        s.detailedSamples = cfg.getInt("detailedSamples", TESTS, s.detailedSamples, 10, 200,
                "TCP handshake samples for a detailed test and route comparison runs.");
        s.warmupSamples = cfg.getInt("warmupSamples", TESTS, s.warmupSamples, 0, 3,
                "Warm-up connections performed (and excluded from statistics) before measuring.");
        s.intervalMs = cfg.getInt("intervalMs", TESTS, s.intervalMs, Settings.MIN_INTERVAL_MS, 10000,
                "Delay between connection attempts to the same destination (never below 500 ms).");
        s.tcpTimeoutMs = cfg.getInt("tcpTimeoutMs", TESTS, s.tcpTimeoutMs, 500, 15000, "TCP connect timeout.");
        s.statusTimeoutMs = cfg.getInt("statusTimeoutMs", TESTS, s.statusTimeoutMs, 1000, 20000,
                "Minecraft status ping timeout.");
        s.preferIpv4 = cfg.getBoolean("preferIpv4", TESTS, s.preferIpv4,
                "Prefer IPv4 addresses when a host resolves to both IPv4 and IPv6.");
        s.dashboardRefreshMs = cfg.getInt("dashboardRefreshMs", DISPLAY, s.dashboardRefreshMs, 50, 2000,
                "How often dashboard numbers are recomputed.");
        s.overlayVisible = cfg.getBoolean("overlayVisible", DISPLAY, s.overlayVisible,
                "Show the small latency overlay in-game.");
        s.graphHistoryMinutes = cfg.getInt("graphHistoryMinutes", MONITOR, s.graphHistoryMinutes, 5, 120,
                "Minutes of live-monitor history kept in memory.");
        s.monitorIntervalMs = cfg.getInt("monitorIntervalMs", MONITOR, s.monitorIntervalMs, 250, 10000,
                "How often the live monitor reads Minecraft's reported latency.");
        monitorOnStartup = cfg.getBoolean("monitorOnStartup", MONITOR, monitorOnStartup,
                "Start the passive live monitor automatically (reads existing values only; sends no traffic).");
        s.packetInstrumentation = cfg.getBoolean("packetInstrumentation", MONITOR, s.packetInstrumentation,
                "Passively observe keepalive and latency-update packets (read-only Netty handler).");
        String v = cfg.getString("verbosity", MONITOR, s.verbosity.name(), "QUIET, NORMAL or VERBOSE",
                new String[]{"QUIET", "NORMAL", "VERBOSE"});
        try {
            s.verbosity = Settings.Verbosity.valueOf(v);
        } catch (IllegalArgumentException e) {
            s.verbosity = Settings.Verbosity.NORMAL;
        }
        s.exportJson = cfg.getBoolean("json", EXPORT, s.exportJson, "Write JSON reports.");
        s.exportCsv = cfg.getBoolean("csv", EXPORT, s.exportCsv, "Write CSV reports.");
        s.exportIncludeLocalAddresses = cfg.getBoolean("includeLocalAddresses", EXPORT,
                s.exportIncludeLocalAddresses, "Include local interface IP/port in exports (off for privacy).");
        if (cfg.hasChanged()) {
            cfg.save();
        }
        return s.sanitize();
    }

    public void save(Settings s) {
        s.sanitize();
        cfg.get(TESTS, "quickSamples", 10).set(s.quickSamples);
        cfg.get(TESTS, "detailedSamples", 50).set(s.detailedSamples);
        cfg.get(TESTS, "warmupSamples", 1).set(s.warmupSamples);
        cfg.get(TESTS, "intervalMs", 1000).set(s.intervalMs);
        cfg.get(TESTS, "tcpTimeoutMs", 3000).set(s.tcpTimeoutMs);
        cfg.get(TESTS, "statusTimeoutMs", 5000).set(s.statusTimeoutMs);
        cfg.get(TESTS, "preferIpv4", true).set(s.preferIpv4);
        cfg.get(DISPLAY, "dashboardRefreshMs", 250).set(s.dashboardRefreshMs);
        cfg.get(DISPLAY, "overlayVisible", false).set(s.overlayVisible);
        cfg.get(MONITOR, "graphHistoryMinutes", 30).set(s.graphHistoryMinutes);
        cfg.get(MONITOR, "monitorIntervalMs", 1000).set(s.monitorIntervalMs);
        cfg.get(MONITOR, "monitorOnStartup", true).set(monitorOnStartup);
        cfg.get(MONITOR, "packetInstrumentation", false).set(s.packetInstrumentation);
        cfg.get(MONITOR, "verbosity", "NORMAL").set(s.verbosity.name());
        cfg.get(EXPORT, "json", true).set(s.exportJson);
        cfg.get(EXPORT, "csv", true).set(s.exportCsv);
        cfg.get(EXPORT, "includeLocalAddresses", false).set(s.exportIncludeLocalAddresses);
        cfg.save();
    }
}
