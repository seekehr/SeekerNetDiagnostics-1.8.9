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

        String text() {
            return label + ": " + value;
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

    /** Lines of entries: one line with every entry, or one entry per line when stacked. */
    private static List<List<Item>> lines(PingMonitor.Snapshot s) {
        List<Item> items = items(s);
        List<List<Item>> lines = new ArrayList<List<Item>>();
        if (HudConfig.stacked) {
            for (Item it : items) {
                List<Item> one = new ArrayList<Item>();
                one.add(it);
                lines.add(one);
            }
        } else {
            lines.add(items);
        }
        return lines;
    }

    private static int lineWidth(FontRenderer fr, List<Item> line) {
        int w = 0;
        for (int i = 0; i < line.size(); i++) {
            w += fr.getStringWidth((i > 0 ? SEP : "") + line.get(i).text());
        }
        return w;
    }

    private static boolean boxed() {
        return HudConfig.style != STYLE_TEXT;
    }

    private static float scale() {
        return HudConfig.scalePercent / 100f;
    }

    /** Unscaled size {width, height} of the HUD for these lines. */
    private static int[] rawSize(FontRenderer fr, List<List<Item>> lines) {
        int w = 0;
        for (List<Item> l : lines) {
            w = Math.max(w, lineWidth(fr, l));
        }
        int h = lines.size() * (fr.FONT_HEIGHT + 1) - 1;
        if (boxed()) {
            w += PAD_X * 2 + (HudConfig.style == STYLE_BAR ? BAR_W : 0);
            h += PAD_Y * 2;
        }
        return new int[]{w, h};
    }

    /** On-screen size {width, height} of the HUD, scale included. A null snapshot measures the preview. */
    public static int[] size(FontRenderer fr, PingMonitor.Snapshot s) {
        int[] r = rawSize(fr, lines(s));
        return new int[]{(int) Math.ceil(r[0] * scale()), (int) Math.ceil(r[1] * scale())};
    }

    /** Draws the HUD with its top-left corner at (x, y). A null snapshot draws sample values. */
    public static void draw(FontRenderer fr, int x, int y, PingMonitor.Snapshot s) {
        List<List<Item>> lines = lines(s);
        int[] size = rawSize(fr, lines);
        int text = COLORS[HudConfig.textColor];
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        float sc = scale();
        GlStateManager.scale(sc, sc, 1);
        int bg = (Math.round(HudConfig.bgOpacity * 2.55f) & 0xFF) << 24;
        int tx = 0;
        int ty = 0;
        if (HudConfig.style == STYLE_BOX) {
            Draw.rect(0, 0, size[0], size[1], bg);
        } else if (HudConfig.style == STYLE_ROUNDED) {
            Draw.roundRect(0, 0, size[0], size[1], 3, bg);
        } else if (HudConfig.style == STYLE_BAR) {
            Draw.rect(0, 0, size[0], size[1], bg);
            PingMonitor.Sample last = s == null ? null : s.last();
            int accent = s == null ? Theme.latencyColor(103)
                    : last == null || !last.ok() ? Theme.BAD : Theme.latencyColor(last.pingMs);
            Draw.rect(0, 0, BAR_W, size[1], accent);
            tx = BAR_W;
        }
        if (boxed()) {
            tx += PAD_X;
            ty += PAD_Y;
        }
        for (List<Item> line : lines) {
            float cx = tx;
            for (int i = 0; i < line.size(); i++) {
                Item it = line.get(i);
                String head = (i > 0 ? SEP : "") + it.label + ": ";
                cx = fr.drawString(head, cx, ty, text, HudConfig.shadow) - (HudConfig.shadow ? 1 : 0);
                cx = fr.drawString(it.value, cx, ty, it.valueColor != 0 ? it.valueColor : text, HudConfig.shadow)
                        - (HudConfig.shadow ? 1 : 0);
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
