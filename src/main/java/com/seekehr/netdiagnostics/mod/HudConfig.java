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
    public static final int DEFAULT_BG_OPACITY = 55;
    private static final String HUD = "hud";
    private static final String MONITOR = "monitor";
    private static final String TRACE = "trace";
    private static final String UI = "ui";
    private static final String STYLE = "hud.style";
    private static final String STATS = "hud.stats";

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

    // HUD look (indexes into PingHud's option lists).
    public static int style = PingHud.STYLE_BOX;
    public static int textColor;
    public static boolean latencyColors;
    public static boolean shadow;
    public static int bgOpacity = DEFAULT_BG_OPACITY;
    public static int scalePercent = 100;
    /** One stat per line instead of one line. */
    public static boolean stacked;

    // Extra HUD stats (the current ping is always shown).
    public static boolean showAvg;
    public static boolean showMin;
    public static boolean showMax;
    public static boolean showJitter;
    public static boolean showLoss;
    public static boolean showConnect;
    public static boolean showTabPing;
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
        style = index(cfg.getInt("style", STYLE, PingHud.STYLE_BOX, 0, 100,
                "0 = box, 1 = rounded box, 2 = box with a latency-coloured bar, 3 = text only."), PingHud.STYLES.length,
                PingHud.STYLE_BOX);
        textColor = index(cfg.getInt("textColor", STYLE, 0, 0, 100,
                "0 gray, 1 light gray, 2 white, 3 aqua, 4 green, 5 yellow, 6 gold, 7 pink."), PingHud.COLORS.length, 0);
        latencyColors = cfg.getBoolean("latencyColors", STYLE, false,
                "Colour each value green/yellow/red by how good it is.");
        shadow = cfg.getBoolean("textShadow", STYLE, false, "Draw the HUD text with a shadow.");
        bgOpacity = oneOf(cfg.getInt("backgroundOpacity", STYLE, DEFAULT_BG_OPACITY, 0, 100,
                "Background opacity in percent: 25, 40, 55, 70, 85 or 100."), PingHud.OPACITIES, DEFAULT_BG_OPACITY);
        scalePercent = oneOf(cfg.getInt("scalePercent", STYLE, 100, 0, 1000,
                "HUD size in percent: 50, 75, 100, 125, 150 or 200."), PingHud.SCALES, 100);
        stacked = cfg.getBoolean("stacked", STYLE, false, "One stat per line instead of all on one line.");
        showAvg = cfg.getBoolean("average", STATS, false, "Show the average ping.");
        showMin = cfg.getBoolean("min", STATS, false, "Show the lowest ping.");
        showMax = cfg.getBoolean("max", STATS, false, "Show the highest ping.");
        showJitter = cfg.getBoolean("jitter", STATS, false, "Show jitter (how much the ping varies).");
        showLoss = cfg.getBoolean("loss", STATS, false, "Show the share of failed pings.");
        showConnect = cfg.getBoolean("connect", STATS, false, "Show the TCP connect latency of the last ping.");
        showTabPing = cfg.getBoolean("tabPing", STATS, false, "Show the ping the server reports in the tab list.");
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
        cfg.get(STYLE, "style", PingHud.STYLE_BOX).set(style);
        cfg.get(STYLE, "textColor", 0).set(textColor);
        cfg.get(STYLE, "latencyColors", false).set(latencyColors);
        cfg.get(STYLE, "textShadow", false).set(shadow);
        cfg.get(STYLE, "backgroundOpacity", DEFAULT_BG_OPACITY).set(bgOpacity);
        cfg.get(STYLE, "scalePercent", 100).set(scalePercent);
        cfg.get(STYLE, "stacked", false).set(stacked);
        cfg.get(STATS, "average", false).set(showAvg);
        cfg.get(STATS, "min", false).set(showMin);
        cfg.get(STATS, "max", false).set(showMax);
        cfg.get(STATS, "jitter", false).set(showJitter);
        cfg.get(STATS, "loss", false).set(showLoss);
        cfg.get(STATS, "connect", false).set(showConnect);
        cfg.get(STATS, "tabPing", false).set(showTabPing);
        cfg.save();
    }

    /** Puts every HUD look setting back to its default (stats and position are kept). */
    public static void resetStyle() {
        style = PingHud.STYLE_BOX;
        textColor = 0;
        latencyColors = false;
        shadow = false;
        bgOpacity = DEFAULT_BG_OPACITY;
        scalePercent = 100;
        stacked = false;
    }

    static int index(int i, int count, int def) {
        return i >= 0 && i < count ? i : def;
    }

    static int oneOf(int v, int[] allowed, int def) {
        for (int a : allowed) {
            if (a == v) {
                return v;
            }
        }
        return def;
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
