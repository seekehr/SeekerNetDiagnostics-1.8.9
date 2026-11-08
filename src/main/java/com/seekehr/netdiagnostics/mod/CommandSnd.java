package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.gui.GuiMonitor;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /snd} (alias {@code /seekernetdiag}). Registered with Forge's ClientCommandHandler, so it runs
 * locally and is never sent to the server.
 */
public final class CommandSnd extends CommandBase {
    @Override
    public String getCommandName() {
        return "snd";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("seekernetdiag", "seekernetdiagnostics");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/snd [host[:port]] | /snd stop | /snd hud";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "stop", "hud", "help") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("stop".equals(sub)) {
            NetDiag.stop();
            Chat.info("Monitoring stopped.");
            return;
        }
        if ("hud".equals(sub)) {
            NetDiag.hud = !NetDiag.hud;
            Chat.info("Ping HUD " + (NetDiag.hud ? "on" : "off") + ".");
            return;
        }
        if ("help".equals(sub)) {
            Chat.info("/snd - monitor the server you're on");
            Chat.info("/snd <host> [port] - monitor another server");
            Chat.info("/snd stop - stop pinging  |  /snd hud - toggle the ping HUD");
            return;
        }
        if (NetDiag.currentServer() == null) {
            Chat.error("Join a multiplayer server first, then use /snd.");
            return;
        }
        Target t;
        try {
            t = args.length == 0 ? NetDiag.currentServerTarget() : Target.parse(args[0], args.length > 1 ? args[1] : null);
        } catch (IllegalArgumentException e) {
            Chat.error(e.getMessage());
            return;
        }
        if (t == null) {
            Chat.error("Could not read the current server's address.");
            return;
        }
        NetDiag.monitor(t);
        ClientEvents.openNextTick(new GuiMonitor());
    }
}
