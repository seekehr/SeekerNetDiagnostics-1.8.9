package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

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
        if (!HudConfig.enabled || m == null || m.stopped() || mc.gameSettings.showDebugInfo
                || mc.currentScreen != null) {
            return;
        }
        int w = event.resolution.getScaledWidth();
        int h = event.resolution.getScaledHeight();
        PingMonitor.Snapshot s = m.snapshot();
        int[] size = PingHud.size(mc.fontRendererObj, s);
        PingHud.draw(mc.fontRendererObj, PingHud.clampX(HudConfig.x, size[0], w),
                PingHud.clampY(HudConfig.y, size[1], h), s);
    }
}
