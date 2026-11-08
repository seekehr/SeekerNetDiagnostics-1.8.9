package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.test.TestRun;
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

    public static void good(String msg) {
        print(PREFIX + EnumChatFormatting.GREEN + msg);
    }

    public static void warn(String msg) {
        print(PREFIX + EnumChatFormatting.GOLD + msg);
    }

    public static void error(String msg) {
        print(PREFIX + EnumChatFormatting.RED + msg);
    }

    public static void print(String formatted) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null && mc.thePlayer != null) {
            mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(formatted));
        } else {
            NetDiag.LOG.info(EnumChatFormatting.getTextWithoutFormattingCodes(formatted));
        }
    }

    public static void runResult(TestRun run) {
        String head = run.kind.label + " test of " + run.target.display();
        if (run.state == TestRun.State.CANCELLED) {
            warn(head + " was cancelled.");
        } else if (run.summary().isEmpty()) {
            error(head + " finished without a successful TCP handshake.");
        } else {
            good(head + " finished.");
        }
        for (String line : Conclusions.describe(run)) {
            info(line);
        }
        print(PREFIX + EnumChatFormatting.DARK_GRAY + Conclusions.EXITLAG_NOT_VERIFIED);
    }
}
