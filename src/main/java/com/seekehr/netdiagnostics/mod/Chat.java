package com.seekehr.netdiagnostics.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/** Local chat output. Messages are printed client-side only and never sent to the server. */
public final class Chat {
    private static final String PREFIX = EnumChatFormatting.DARK_AQUA + "[SND] " + EnumChatFormatting.RESET;

    private Chat() {
    }

    public static void info(String msg) {
        print(PREFIX + EnumChatFormatting.GRAY + msg);
    }

    public static void error(String msg) {
        print(PREFIX + EnumChatFormatting.RED + msg);
    }

    private static void print(String formatted) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null && mc.thePlayer != null) {
            mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(formatted));
        } else {
            NetDiag.LOG.info(EnumChatFormatting.getTextWithoutFormattingCodes(formatted));
        }
    }
}
