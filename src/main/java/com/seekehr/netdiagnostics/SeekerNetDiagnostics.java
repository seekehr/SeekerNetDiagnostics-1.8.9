package com.seekehr.netdiagnostics;

import com.seekehr.netdiagnostics.mod.ClientEvents;
import com.seekehr.netdiagnostics.mod.CommandSnd;
import com.seekehr.netdiagnostics.mod.HudConfig;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import java.io.File;

/**
 * SeekerNetDiagnostics: in-game ping monitor for Minecraft 1.8.9.
 *
 * <p>Client-only; servers do not need the mod, and {@code acceptableRemoteVersions = "*"} means it never
 * affects the connection handshake.</p>
 */
@Mod(modid = SeekerNetDiagnostics.MODID, name = SeekerNetDiagnostics.NAME, version = SeekerNetDiagnostics.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*")
public class SeekerNetDiagnostics {
    public static final String MODID = "seekernetdiagnostics";
    public static final String NAME = "SeekerNetDiagnostics";
    public static final String VERSION = "2.0.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        HudConfig.load(new File(event.getModConfigurationDirectory(), MODID + ".cfg"));
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        ClientCommandHandler.instance.registerCommand(new CommandSnd());
    }
}
