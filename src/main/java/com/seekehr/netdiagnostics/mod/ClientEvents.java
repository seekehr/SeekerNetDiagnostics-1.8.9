package com.seekehr.netdiagnostics.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Opens screens requested by commands on the next tick (the chat screen is still closing when they run). */
public final class ClientEvents {
    private static GuiScreen pendingScreen;

    public static void openNextTick(GuiScreen screen) {
        pendingScreen = screen;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && pendingScreen != null) {
            GuiScreen s = pendingScreen;
            pendingScreen = null;
            Minecraft.getMinecraft().displayGuiScreen(s);
        }
    }
}
