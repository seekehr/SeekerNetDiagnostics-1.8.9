package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.mod.ClientEvents;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import java.util.Locale;

/** Compact in-game overlay (top-right). Statistics are recomputed at most once per second. */
public final class HudOverlay {
    private static long lastCompute;
    private static String line1 = "";
    private static String line2 = "";
    private static int color = Theme.TEXT;

    private HudOverlay() {
    }

    public static void render(NetDiag nd, ScaledResolution res) {
        Minecraft mc = Minecraft.getMinecraft();
        if (NetDiag.currentServer() == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCompute > 1000) {
            lastCompute = now;
            int ping = ClientEvents.ownReportedLatency(mc);
            StatSummary s = nd.monitor.summary(now - 60_000L);
            line1 = "Ping " + (ping < 0 ? "n/a" : ping + " ms");
            line2 = s.count < 2 ? (nd.monitor.active() ? "jitter: collecting" : "monitor off")
                    : String.format(Locale.ROOT, "jitter %.1f ms · 1m", s.jitter);
            color = Theme.latencyColor(ping < 0 ? Double.NaN : ping);
        }
        FontRenderer fr = mc.fontRendererObj;
        int w = Math.max(fr.getStringWidth(line1), fr.getStringWidth(line2)) + 10;
        int x = res.getScaledWidth() - w - 4;
        int y = 4;
        Draw.roundRect(x, y, w, 24, 4, 0x99101520);
        Draw.text(fr, line1, x + 5, y + 4, color);
        Draw.text(fr, line2, x + 5, y + 14, Theme.TEXT_DIM);
    }
}
