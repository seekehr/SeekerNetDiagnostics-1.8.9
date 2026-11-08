package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.gui.Theme;
import net.minecraft.client.gui.FontRenderer;

import java.util.Locale;

/** The one-line ping HUD, shared by the in-game overlay and the HUD editor's preview. */
public final class PingHud {
    private static final String PREVIEW_REST = "  avg 45  jit 2.1  loss 0.0%";

    private PingHud() {
    }

    /** Draws the HUD at (x, y) and returns its width. A null snapshot draws sample values. */
    public static int draw(FontRenderer fr, int x, int y, PingMonitor.Snapshot s) {
        String now;
        int color;
        String rest;
        if (s == null) {
            now = "42 ms";
            color = Theme.GOOD;
            rest = PREVIEW_REST;
        } else {
            PingMonitor.Sample last = s.last();
            now = last == null ? "..." : last.ok() ? String.format(Locale.ROOT, "%.0f ms", last.pingMs) : "FAIL";
            color = last == null || !last.ok() ? Theme.BAD : Theme.latencyColor(last.pingMs);
            rest = s.ping.isEmpty() ? "" : String.format(Locale.ROOT, "  avg %.0f  jit %.1f  loss %.1f%%",
                    s.ping.mean, s.ping.jitter, s.lossPercent());
        }
        int cx = fr.drawStringWithShadow("Ping ", x, y, Theme.TEXT_DIM);
        cx = fr.drawStringWithShadow(now, cx, y, color);
        cx = fr.drawStringWithShadow(rest, cx, y, Theme.TEXT_DIM);
        return cx - x;
    }

    /** Width of the preview HUD, used for dragging and clamping. */
    public static int previewWidth(FontRenderer fr) {
        return fr.getStringWidth("Ping 42 ms" + PREVIEW_REST);
    }

    /** Keeps the saved position on screen for the current GUI size. */
    public static int clampX(int x, int screenW, FontRenderer fr) {
        return Math.max(0, Math.min(x, screenW - previewWidth(fr)));
    }

    public static int clampY(int y, int screenH, FontRenderer fr) {
        return Math.max(0, Math.min(y, screenH - fr.FONT_HEIGHT));
    }
}
