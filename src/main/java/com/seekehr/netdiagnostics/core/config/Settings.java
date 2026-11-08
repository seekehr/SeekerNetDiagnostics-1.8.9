package com.seekehr.netdiagnostics.core.config;

/**
 * User-adjustable settings with conservative defaults. Every setter clamps to a safe range so that
 * no configuration can flood a server with connection attempts.
 */
public final class Settings {
    public enum Verbosity {
        QUIET, NORMAL, VERBOSE
    }

    /** Hard floor between attempts to the same destination, regardless of configuration. */
    public static final int MIN_INTERVAL_MS = 500;

    public int quickSamples = 10;
    public int detailedSamples = 50;
    public int warmupSamples = 1;
    public int intervalMs = 1000;
    public int tcpTimeoutMs = 3000;
    public int statusTimeoutMs = 5000;
    public int dashboardRefreshMs = 250;
    public int monitorIntervalMs = 1000;
    public int graphHistoryMinutes = 30;
    public boolean overlayVisible = false;
    public boolean packetInstrumentation = false;
    public boolean exportJson = true;
    public boolean exportCsv = true;
    public boolean exportIncludeLocalAddresses = false;
    public boolean preferIpv4 = true;
    public Verbosity verbosity = Verbosity.NORMAL;

    public Settings sanitize() {
        quickSamples = clamp(quickSamples, 3, 100);
        detailedSamples = clamp(detailedSamples, 10, 200);
        warmupSamples = clamp(warmupSamples, 0, 3);
        intervalMs = clamp(intervalMs, MIN_INTERVAL_MS, 10000);
        tcpTimeoutMs = clamp(tcpTimeoutMs, 500, 15000);
        statusTimeoutMs = clamp(statusTimeoutMs, 1000, 20000);
        dashboardRefreshMs = clamp(dashboardRefreshMs, 50, 2000);
        monitorIntervalMs = clamp(monitorIntervalMs, 250, 10000);
        graphHistoryMinutes = clamp(graphHistoryMinutes, 5, 120);
        if (verbosity == null) {
            verbosity = Verbosity.NORMAL;
        }
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
        s.tcpTimeoutMs = tcpTimeoutMs;
        s.statusTimeoutMs = statusTimeoutMs;
        s.dashboardRefreshMs = dashboardRefreshMs;
        s.monitorIntervalMs = monitorIntervalMs;
        s.graphHistoryMinutes = graphHistoryMinutes;
        s.overlayVisible = overlayVisible;
        s.packetInstrumentation = packetInstrumentation;
        s.exportJson = exportJson;
        s.exportCsv = exportCsv;
        s.exportIncludeLocalAddresses = exportIncludeLocalAddresses;
        s.preferIpv4 = preferIpv4;
        s.verbosity = verbosity;
        return s;
    }
}
