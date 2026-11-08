package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.config.Settings;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Persists {@link Settings} in config/seekernetdiagnostics.cfg. */
public final class SettingsStore {
    private static final String TESTS = "tests";
    private static final String EXPORT = "export";

    private final Configuration cfg;

    public SettingsStore(File file) {
        cfg = new Configuration(file);
    }

    public Settings load() {
        cfg.load();
        Settings s = new Settings();
        s.quickSamples = cfg.getInt("quickSamples", TESTS, s.quickSamples, 3, 100, "Pings in a quick test.");
        s.detailedSamples = cfg.getInt("detailedSamples", TESTS, s.detailedSamples, 10, 200,
                "Pings in a detailed test and in each route-comparison run.");
        s.warmupSamples = cfg.getInt("warmupSamples", TESTS, s.warmupSamples, 0, 3,
                "Warm-up pings run first and excluded from statistics.");
        s.intervalMs = cfg.getInt("intervalMs", TESTS, s.intervalMs, Settings.MIN_INTERVAL_MS, 10000,
                "Delay between connections to the same server (never below 500 ms).");
        s.timeoutMs = cfg.getInt("timeoutMs", TESTS, s.timeoutMs, 1000, 20000, "Time limit for each ping.");
        s.preferIpv4 = cfg.getBoolean("preferIpv4", TESTS, s.preferIpv4,
                "Prefer IPv4 when a host has both IPv4 and IPv6 addresses.");
        s.exportJson = cfg.getBoolean("json", EXPORT, s.exportJson, "Write JSON reports.");
        s.exportCsv = cfg.getBoolean("csv", EXPORT, s.exportCsv, "Write CSV reports.");
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
        cfg.get(TESTS, "timeoutMs", 5000).set(s.timeoutMs);
        cfg.get(TESTS, "preferIpv4", true).set(s.preferIpv4);
        cfg.get(EXPORT, "json", true).set(s.exportJson);
        cfg.get(EXPORT, "csv", true).set(s.exportCsv);
        cfg.save();
    }
}
