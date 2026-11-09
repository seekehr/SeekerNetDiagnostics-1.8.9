package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.gui.Draw;
import com.seekehr.netdiagnostics.gui.Theme;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ping HUD, shared by the in-game overlay, the HUD editor and the settings preview. Its look and the stats it
 * shows come from {@link HudConfig}. Positions are the top-left corner of the whole HUD (box included), in GUI pixels.
 */
public final class PingHud {
    /** Background styles, indexed by {@link HudConfig#style}. */
    public static final String[] STYLES = {"Box", "Rounded", "Accent bar", "Text only"};
    public static final int STYLE_BOX = 0, STYLE_ROUNDED = 1, STYLE_BAR = 2, STYLE_TEXT = 3;

    /** Text colours, indexed by {@link HudConfig#textColor}. */
    public static final String[] COLOR_NAMES = {"Gray", "Light gray", "White", "Aqua", "Green", "Yellow", "Gold",
            "Pink"};
    public static final int[] COLORS = {0xFFAAAAAA, 0xFFD0D0D0, 0xFFFFFFFF, 0xFF55FFFF, 0xFF55FF55, 0xFFFFFF55,
            0xFFFFAA00, 0xFFFF55FF};

    public static final int[] OPACITIES = {25, 40, 55, 70, 85, 100};
    public static final int[] SCALES = {50, 75, 100, 125, 150, 200};

    private static final int PAD_X = 4;
    private static final int PAD_Y = 3;
    private static final int BAR_W = 2;
    private static final String SEP = "  ";

    private PingHud() {
    }

    /** One "Label: value" entry. A value colour of 0 means "use the text colour". */
    private static final class Item {
        final String label;
        final String value;
        final int valueColor;

        Item(String label, String value, int valueColor) {
            this.label = label;
            this.value = value;
            this.valueColor = valueColor;
        }
    }

    /** Builds the entries to show. A null snapshot gives sample values for previews. */
    private static List<Item> items(PingMonitor.Snapshot s) {
        double now;
        String nowText;
        double avg, min, max, jitter, loss, connect;
        int tab;
        if (s == null) {
            now = 103;
            nowText = "103ms";
            avg = 98;
            min = 87;
            max = 141;
            jitter = 4.2;
            loss = 0;
            connect = 31;
            tab = 100;
        } else {
            PingMonitor.Sample last = s.last();
            now = last != null && last.ok() ? last.pingMs : Double.NaN;
            nowText = last == null ? "..." : last.ok() ? ms(last.pingMs) : "FAIL";
            avg = s.ping.mean;
            min = s.ping.min;
            max = s.ping.max;
            jitter = s.ping.jitter;
            loss = s.lossPercent();
            connect = last == null ? Double.NaN : last.connectMs;
            tab = last == null ? -1 : last.tabPingMs;
        }
        boolean byLatency = HudConfig.latencyColors;
        List<Item> out = new ArrayList<Item>();
        out.add(new Item("Ping", nowText, byLatency ? (Double.isNaN(now) ? Theme.BAD : Theme.latencyColor(now)) : 0));
        if (HudConfig.showAvg) {
            out.add(new Item("Avg", ms(avg), byLatency ? Theme.latencyColor(avg) : 0));
        }
        if (HudConfig.showMin) {
            out.add(new Item("Min", ms(min), byLatency ? Theme.latencyColor(min) : 0));
        }
        if (HudConfig.showMax) {
            out.add(new Item("Max", ms(max), byLatency ? Theme.latencyColor(max) : 0));
        }
        if (HudConfig.showJitter) {
            out.add(new Item("Jitter", Double.isNaN(jitter) ? "-" : String.format(Locale.ROOT, "%.1fms", jitter),
                    byLatency && !Double.isNaN(jitter) ? (jitter < 5 ? Theme.GOOD : jitter < 15 ? Theme.WARN : Theme.BAD)
                            : 0));
        }
        if (HudConfig.showLoss) {
            out.add(new Item("Loss", String.format(Locale.ROOT, "%.1f%%", loss),
                    byLatency ? (loss == 0 ? Theme.GOOD : loss < 2 ? Theme.WARN : Theme.BAD) : 0));
        }
        if (HudConfig.showConnect) {
            out.add(new Item("Connect", ms(connect), byLatency ? Theme.latencyColor(connect) : 0));
        }
        if (HudConfig.showTabPing) {
            out.add(new Item("Tab", tab < 0 ? "-" : tab + "ms", byLatency && tab >= 0 ? Theme.latencyColor(tab) : 0));
        }
        return out;
    }

    private static String ms(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0fms", v);
    }

    /** One row of the HUD, with the "  Label: " heads pre-built so drawing allocates nothing. */
    private static final class Line {
        final String[] heads;
        final String[] values;
        final int[] valueColors;

        Line(List<Item> items) {
            int n = items.size();
            heads = new String[n];
            values = new String[n];
            valueColors = new int[n];
            for (int i = 0; i < n; i++) {
                Item it = items.get(i);
                heads[i] = (i > 0 ? SEP : "") + it.label + ": ";
                values[i] = it.value;
                valueColors[i] = it.valueColor;
            }
        }

        int width(FontRenderer fr) {
            int w = 0;
            for (int i = 0; i < heads.length; i++) {
                w += fr.getStringWidth(heads[i]) + fr.getStringWidth(values[i]);
            }
            return w;
        }
    }

    /**
     * Text and unscaled size of the HUD for one snapshot and one set of content settings. Rebuilt only when a new
     * sample arrives or a setting that changes the content or size changes, not every frame.
     */
    private static final class Layout {
        final PingMonitor.Snapshot snapshot;
        final FontRenderer font;
        final boolean unicode;
        final int contentKey;
        final List<Line> lines = new ArrayList<Line>();
        final int rawW;
        final int rawH;
        int sizeScale = -1;
        final int[] size = new int[2];

        Layout(FontRenderer fr, PingMonitor.Snapshot s, int key) {
            snapshot = s;
            font = fr;
            unicode = fr.getUnicodeFlag();
            contentKey = key;
            List<Item> items = items(s);
            if (HudConfig.stacked) {
                for (Item it : items) {
                    List<Item> one = new ArrayList<Item>(1);
                    one.add(it);
                    lines.add(new Line(one));
                }
            } else {
                lines.add(new Line(items));
            }
            int w = 0;
            for (Line l : lines) {
                w = Math.max(w, l.width(fr));
            }
            int h = lines.size() * (fr.FONT_HEIGHT + 1) - 1;
            if (boxed()) {
                w += PAD_X * 2 + (HudConfig.style == STYLE_BAR ? BAR_W : 0);
                h += PAD_Y * 2;
            }
            rawW = w;
            rawH = h;
        }

        boolean matches(FontRenderer fr, PingMonitor.Snapshot s, int key) {
            return snapshot == s && font == fr && unicode == fr.getUnicodeFlag() && contentKey == key;
        }
    }

    /** HUD layouts are only built and drawn on the render thread. */
    private static Layout layout;

    /** Packs every setting that changes the HUD's text or unscaled size. */
    private static int contentKey() {
        int k = HudConfig.style & 0xFF;
        k = k << 1 | (HudConfig.stacked ? 1 : 0);
        k = k << 1 | (HudConfig.latencyColors ? 1 : 0);
        k = k << 1 | (HudConfig.showAvg ? 1 : 0);
        k = k << 1 | (HudConfig.showMin ? 1 : 0);
        k = k << 1 | (HudConfig.showMax ? 1 : 0);
        k = k << 1 | (HudConfig.showJitter ? 1 : 0);
        k = k << 1 | (HudConfig.showLoss ? 1 : 0);
        k = k << 1 | (HudConfig.showConnect ? 1 : 0);
        k = k << 1 | (HudConfig.showTabPing ? 1 : 0);
        return k;
    }

    private static Layout layout(FontRenderer fr, PingMonitor.Snapshot s) {
        int key = contentKey();
        Layout l = layout;
        if (l == null || !l.matches(fr, s, key)) {
            l = new Layout(fr, s, key);
            layout = l;
        }
        return l;
    }

    private static boolean boxed() {
        return HudConfig.style != STYLE_TEXT;
    }

    private static float scale() {
        return HudConfig.scalePercent / 100f;
    }

    /**
     * On-screen size {width, height} of the HUD, scale included. A null snapshot measures the preview. The returned
     * array is cached and shared: read it, do not modify or keep it.
     */
    public static int[] size(FontRenderer fr, PingMonitor.Snapshot s) {
        Layout l = layout(fr, s);
        if (l.sizeScale != HudConfig.scalePercent) {
            l.size[0] = (int) Math.ceil(l.rawW * scale());
            l.size[1] = (int) Math.ceil(l.rawH * scale());
            l.sizeScale = HudConfig.scalePercent;
        }
        return l.size;
    }

    /** Draws the HUD with its top-left corner at (x, y). A null snapshot draws sample values. */
    public static void draw(FontRenderer fr, int x, int y, PingMonitor.Snapshot s) {
        Layout l = layout(fr, s);
        int text = COLORS[HudConfig.textColor];
        boolean shadow = HudConfig.shadow;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        float sc = scale();
        GlStateManager.scale(sc, sc, 1);
        int bg = (Math.round(HudConfig.bgOpacity * 2.55f) & 0xFF) << 24;
        int tx = 0;
        int ty = 0;
        if (HudConfig.style == STYLE_BOX) {
            Draw.rect(0, 0, l.rawW, l.rawH, bg);
        } else if (HudConfig.style == STYLE_ROUNDED) {
            Draw.roundRect(0, 0, l.rawW, l.rawH, 3, bg);
        } else if (HudConfig.style == STYLE_BAR) {
            Draw.rect(0, 0, l.rawW, l.rawH, bg);
            PingMonitor.Sample last = s == null ? null : s.last();
            int accent = s == null ? Theme.latencyColor(103)
                    : last == null || !last.ok() ? Theme.BAD : Theme.latencyColor(last.pingMs);
            Draw.rect(0, 0, BAR_W, l.rawH, accent);
            tx = BAR_W;
        }
        if (boxed()) {
            tx += PAD_X;
            ty += PAD_Y;
        }
        int shadowFix = shadow ? 1 : 0;
        for (int li = 0; li < l.lines.size(); li++) {
            Line line = l.lines.get(li);
            float cx = tx;
            for (int i = 0; i < line.heads.length; i++) {
                cx = fr.drawString(line.heads[i], cx, ty, text, shadow) - shadowFix;
                int vc = line.valueColors[i];
                cx = fr.drawString(line.values[i], cx, ty, vc != 0 ? vc : text, shadow) - shadowFix;
            }
            ty += fr.FONT_HEIGHT + 1;
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }

    /** Keeps a HUD of width w / height h on screen for the current GUI size. */
    public static int clampX(int x, int w, int screenW) {
        return Math.max(0, Math.min(x, screenW - w));
    }

    public static int clampY(int y, int h, int screenH) {
        return Math.max(0, Math.min(y, screenH - h));
    }
}
