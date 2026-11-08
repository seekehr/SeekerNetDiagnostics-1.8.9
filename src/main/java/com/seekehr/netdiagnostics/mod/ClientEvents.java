package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Locale;

/** Client tick bookkeeping, deferred screen opening, and the small ping HUD. */
public final class ClientEvents {
    private static GuiScreen pendingScreen;

    /** Commands run while the chat screen is still closing, so screens open on the next tick. */
    public static void openNextTick(GuiScreen screen) {
        pendingScreen = screen;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        NetDiag.tick();
        if (pendingScreen != null) {
            GuiScreen s = pendingScreen;
            pendingScreen = null;
            Minecraft.getMinecraft().displayGuiScreen(s);
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        PingMonitor m = NetDiag.monitor();
        if (!NetDiag.hud || m == null || m.stopped() || mc.gameSettings.showDebugInfo || mc.currentScreen != null) {
            return;
        }
        PingMonitor.Snapshot s = m.snapshot();
        PingMonitor.Sample last = s.last();
        String now = last == null ? "..." : last.ok() ? String.format(Locale.ROOT, "%.0f ms", last.pingMs) : "FAIL";
        int color = last == null || !last.ok() ? Theme.BAD : Theme.latencyColor(last.pingMs);
        String rest = s.ping.isEmpty() ? "" : String.format(Locale.ROOT, "  avg %.0f  jit %.1f  loss %.1f%%",
                s.ping.mean, s.ping.jitter, s.lossPercent());
        int x = mc.fontRendererObj.drawStringWithShadow("Ping ", 2, 2, Theme.TEXT_DIM);
        x = mc.fontRendererObj.drawStringWithShadow(now, x, 2, color);
        mc.fontRendererObj.drawStringWithShadow(rest, x, 2, Theme.TEXT_DIM);
    }
}
