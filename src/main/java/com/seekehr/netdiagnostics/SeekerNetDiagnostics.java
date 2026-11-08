package com.seekehr.netdiagnostics;

import com.seekehr.netdiagnostics.mod.ClientEvents;
import com.seekehr.netdiagnostics.mod.NetDiag;
import com.seekehr.netdiagnostics.mod.commands.CommandSnd;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import java.io.File;

/**
 * SeekerNetDiagnostics: client-side network diagnostics for Minecraft 1.8.9.
 *
 * <p>Client-only ({@code clientSideOnly = true}); servers do not need the mod, and
 * {@code acceptableRemoteVersions = "*"} means it never affects the connection handshake.</p>
 */
@Mod(modid = SeekerNetDiagnostics.MODID, name = SeekerNetDiagnostics.NAME, version = SeekerNetDiagnostics.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*")
public class SeekerNetDiagnostics {
    public static final String MODID = "seekernetdiagnostics";
    public static final String NAME = "SeekerNetDiagnostics";
    public static final String VERSION = "1.0.0";

    private File configDir;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        configDir = event.getModConfigurationDirectory();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        final NetDiag nd = NetDiag.init(configDir != null ? configDir : new File("config"));
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        ClientCommandHandler.instance.registerCommand(new CommandSnd());
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                nd.shutdown();
            }
        }, "SeekerNetDiagnostics-shutdown"));
    }
}
