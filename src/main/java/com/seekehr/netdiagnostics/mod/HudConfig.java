package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Every setting of the mod, saved in config/seekernetdiagnostics.cfg as soon as it changes. HUD position is in
 * scaled GUI pixels.
 */
public final class HudConfig {
    public static final int DEFAULT_X = 2;
    public static final int DEFAULT_Y = 2;
    public static final int DEFAULT_PING_INTERVAL_MS = 1000;
    public static final int DEFAULT_TRACE_INTERVAL_S = 60;
    private static final String HUD = "hud";
    private static final String MONITOR = "monitor";
    private static final String TRACE = "trace";
    private static final String UI = "ui";

    /** Show the ping HUD. */
    public static boolean enabled = true;
    public static int x = DEFAULT_X;
    public static int y = DEFAULT_Y;
    /** Start monitoring automatically when joining a server, so the HUD shows without /snd. */
    public static boolean autoMonitor = true;
    /** Time between pings (one of {@link PingMonitor#INTERVALS}). */
    public static int pingIntervalMs = DEFAULT_PING_INTERVAL_MS;
    /** Advanced (administrator) tracing with the elevated helper; off by default. */
    public static boolean advancedTracing;
    /** Seconds between automatic route updates in the Traceroute tab; 0 = only when asked (T). */
    public static int traceIntervalSec = DEFAULT_TRACE_INTERVAL_S;
    /** The monitor screen opens on the Traceroute tab (else the Route tab). */
    public static boolean traceTab;
    private static Configuration cfg;

    private HudConfig() {
    }

    public static void load(File file) {
        cfg = new Configuration(file);
        cfg.load();
        enabled = cfg.getBoolean("enabled", HUD, true, "Show the ping HUD.");
        x = cfg.getInt("x", HUD, DEFAULT_X, 0, 10000, "HUD left edge, in GUI pixels.");
        y = cfg.getInt("y", HUD, DEFAULT_Y, 0, 10000, "HUD top edge, in GUI pixels.");
        autoMonitor = cfg.getBoolean("autoStart", MONITOR, true,
                "Start pinging automatically when you join a server, so the HUD shows without /snd.");
        pingIntervalMs = validPingInterval(cfg.getInt("pingIntervalMs", MONITOR, DEFAULT_PING_INTERVAL_MS, 500, 5000,
                "Time between pings in ms: 500, 1000, 2000 or 5000."));
        advancedTracing = cfg.getBoolean("advancedTracing", TRACE, false,
                "Advanced tracing: start an elevated helper (asks for administrator rights via UAC) for packet "
                        + "capture and proxy relay traces.");
        traceIntervalSec = validTraceInterval(cfg.getInt("updateIntervalSeconds", TRACE, DEFAULT_TRACE_INTERVAL_S, 0,
                3600, "Seconds between automatic route updates: 15, 30, 60 or 120; 0 = only when you press T."));
        traceTab = cfg.getBoolean("openTracerouteTab", UI, false,
                "Open the monitor screen on the Traceroute tab (false = Route tab).");
        save(); // writes defaults and corrected values back
    }

    /** Writes every setting to the config file. Called whenever one changes. */
    public static void save() {
        if (cfg == null) {
            return;
        }
        cfg.get(HUD, "enabled", true).set(enabled);
        cfg.get(HUD, "x", DEFAULT_X).set(x);
        cfg.get(HUD, "y", DEFAULT_Y).set(y);
        cfg.get(MONITOR, "autoStart", true).set(autoMonitor);
        cfg.get(MONITOR, "pingIntervalMs", DEFAULT_PING_INTERVAL_MS).set(pingIntervalMs);
        cfg.get(TRACE, "advancedTracing", false).set(advancedTracing);
        cfg.get(TRACE, "updateIntervalSeconds", DEFAULT_TRACE_INTERVAL_S).set(traceIntervalSec);
        cfg.get(UI, "openTracerouteTab", false).set(traceTab);
        cfg.save();
    }

    static int validPingInterval(int ms) {
        for (int v : PingMonitor.INTERVALS) {
            if (v == ms) {
                return ms;
            }
        }
        return DEFAULT_PING_INTERVAL_MS;
    }

    static int validTraceInterval(int s) {
        int[] ok = {15, 30, 60, 120, 0};
        for (int v : ok) {
            if (v == s) {
                return s;
            }
        }
        return DEFAULT_TRACE_INTERVAL_S;
    }
}
