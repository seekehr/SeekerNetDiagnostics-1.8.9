package com.seekehr.netdiagnostics.mod;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Ping HUD settings, saved in config/seekernetdiagnostics.cfg. Position is in scaled GUI pixels. */
public final class HudConfig {
    public static final int DEFAULT_X = 2;
    public static final int DEFAULT_Y = 2;
    private static final String CAT = "hud";

    public static boolean enabled = true;
    public static int x = DEFAULT_X;
    public static int y = DEFAULT_Y;
    private static Configuration cfg;

    private HudConfig() {
    }

    public static void load(File file) {
        cfg = new Configuration(file);
        cfg.load();
        enabled = cfg.getBoolean("enabled", CAT, true, "Show the ping HUD while monitoring.");
        x = cfg.getInt("x", CAT, DEFAULT_X, 0, 10000, "HUD left edge, in GUI pixels.");
        y = cfg.getInt("y", CAT, DEFAULT_Y, 0, 10000, "HUD top edge, in GUI pixels.");
        if (cfg.hasChanged()) {
            cfg.save();
        }
    }

    public static void save() {
        if (cfg == null) {
            return;
        }
        cfg.get(CAT, "enabled", true).set(enabled);
        cfg.get(CAT, "x", DEFAULT_X).set(x);
        cfg.get(CAT, "y", DEFAULT_Y).set(y);
        cfg.save();
    }
}
