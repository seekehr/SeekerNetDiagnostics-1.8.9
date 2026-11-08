package com.seekehr.netdiagnostics.gui;

/** Dark theme palette (ARGB). Text colours meet WCAG AA contrast against the card colour. */
public final class Theme {
    private Theme() {
    }

    public static final int BACKDROP = 0xE60B0E14;
    public static final int CARD = 0xF0161B24;
    public static final int CARD_RAISED = 0xF01D2330;
    public static final int BORDER = 0xFF2A3242;
    public static final int TEXT = 0xFFE6EAF2;
    public static final int TEXT_DIM = 0xFFA3ADBF;
    public static final int TEXT_FAINT = 0xFF7D879A;
    public static final int ACCENT = 0xFF4CB8FF;
    public static final int ACCENT_DARK = 0xFF1F6FB2;
    public static final int GOOD = 0xFF4ADE80;
    public static final int WARN = 0xFFFBBF24;
    public static final int BAD = 0xFFF87171;
    public static final int GRID = 0x332A3242;
    public static final int BUTTON = 0xFF232A38;
    public static final int BUTTON_HOVER = 0xFF2E3748;
    public static final int BUTTON_PRIMARY = 0xFF1F6FB2;
    public static final int BUTTON_PRIMARY_HOVER = 0xFF2A86D1;
    public static final int BUTTON_DANGER = 0xFF7F2A2A;
    public static final int BUTTON_DANGER_HOVER = 0xFF9B3434;
    public static final int FOCUS = 0xFF7DD3FC;
    public static final int FIELD = 0xFF0F131A;

    public static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0, Math.min(1, t));
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
        int r = (int) (((c1 >>> 16) & 0xFF) + (((c2 >>> 16) & 0xFF) - ((c1 >>> 16) & 0xFF)) * t);
        int g = (int) (((c1 >>> 8) & 0xFF) + (((c2 >>> 8) & 0xFF) - ((c1 >>> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Colour for a latency value in ms. */
    public static int latencyColor(double ms) {
        if (Double.isNaN(ms)) {
            return TEXT_DIM;
        }
        if (ms < 60) {
            return GOOD;
        }
        if (ms < 130) {
            return WARN;
        }
        return BAD;
    }
}
