package com.seekehr.netdiagnostics.mod.commands;

import com.seekehr.netdiagnostics.gui.GuiDashboard;
import com.seekehr.netdiagnostics.mod.ClientEvents;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

import java.util.Arrays;
import java.util.List;

/** {@code /seekernetmod} (alias {@code /snm}): opens the dashboard. Client-side only. */
public final class CommandSnm extends CommandBase {
    @Override
    public String getCommandName() {
        return "seekernetmod";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("snm");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/seekernetmod (or /snm) - open the SeekerNetDiagnostics dashboard";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        ClientEvents.openNextTick(new GuiDashboard(null));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }
}
