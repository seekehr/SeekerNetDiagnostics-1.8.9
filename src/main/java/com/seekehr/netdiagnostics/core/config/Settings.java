package com.seekehr.netdiagnostics.core.config;

/**
 * User-adjustable settings with conservative defaults. Every value is clamped so no configuration
 * can flood a server with connections.
 */
public final class Settings {
    /** Hard floor between connections to the same destination, regardless of configuration. */
    public static final int MIN_INTERVAL_MS = 500;

    public int quickSamples = 10;
    public int detailedSamples = 50;
    public int warmupSamples = 1;
    public int intervalMs = 1000;
    public int timeoutMs = 5000;
    public boolean preferIpv4 = true;
    public boolean exportJson = true;
    public boolean exportCsv = true;

    public Settings sanitize() {
        quickSamples = clamp(quickSamples, 3, 100);
        detailedSamples = clamp(detailedSamples, 10, 200);
        warmupSamples = clamp(warmupSamples, 0, 3);
        intervalMs = clamp(intervalMs, MIN_INTERVAL_MS, 10000);
        timeoutMs = clamp(timeoutMs, 1000, 20000);
        return this;
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public Settings copy() {
        Settings s = new Settings();
        s.quickSamples = quickSamples;
        s.detailedSamples = detailedSamples;
        s.warmupSamples = warmupSamples;
        s.intervalMs = intervalMs;
        s.timeoutMs = timeoutMs;
        s.preferIpv4 = preferIpv4;
        s.exportJson = exportJson;
        s.exportCsv = exportCsv;
        return s;
    }
}
