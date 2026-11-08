package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.core.config.Settings;
import com.seekehr.netdiagnostics.core.monitor.LatencyMonitor;
import com.seekehr.netdiagnostics.gui.HudOverlay;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/** Forge event wiring. Heavy work never happens here: only cheap reads and queue draining. */
public final class ClientEvents {
    private static GuiScreen pendingScreen;
    private static volatile boolean connectPending;
    private static volatile boolean disconnectPending;
    private static volatile NetworkManager currentManager;
    private static int disconnectWatchTicks;
    private static boolean disconnectSeenScreen;
    private static long lastMonitorSample;
    private static boolean wasConnected;
    private static boolean packetLatencySeen;

    /** Opens a screen on the next client tick (commands run while the chat screen is still closing). */
    public static void openNextTick(GuiScreen screen) {
        pendingScreen = screen;
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // Netty thread: only record state and (optionally) install the passive observer.
        if (event.isLocal) {
            currentManager = null;
            return;
        }
        currentManager = event.manager;
        connectPending = true;
        NetDiag nd = NetDiag.get();
        if (nd != null && nd.settings().packetInstrumentation) {
            PacketObserver.install(event.manager.channel());
        }
    }

    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        NetworkManager m = event.manager;
        if (m != null && m.channel() != null) {
            PacketObserver.uninstall(m.channel());
        }
        if (m != null && m == currentManager) {
            currentManager = null;
            disconnectPending = true;
        }
    }

    /** Applies a changed instrumentation setting to the current connection. */
    public static void refreshInstrumentation() {
        NetworkManager m = currentManager;
        NetDiag nd = NetDiag.get();
        if (m == null || nd == null) {
            return;
        }
        Channel ch = m.channel();
        if (nd.settings().packetInstrumentation) {
            PacketObserver.install(ch);
        } else {
            PacketObserver.uninstall(ch);
            PacketObserver.clearQueue();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (pendingScreen != null) {
            GuiScreen s = pendingScreen;
            pendingScreen = null;
            mc.displayGuiScreen(s);
        }
        NetDiag nd = NetDiag.get();
        if (nd == null) {
            return;
        }
        long now = System.currentTimeMillis();
        LatencyMonitor mon = nd.monitor;

        if (connectPending && mc.getNetHandler() != null && mc.thePlayer != null) {
            connectPending = false;
            ServerData d = mc.getCurrentServerData();
            String name = d == null ? "server" : d.serverIP;
            mon.onConnected(now, name, NetDiag.currentRemoteAddress());
            PacketObserver.setOwnId(mc.getNetHandler().getGameProfile() == null ? null
                    : mc.getNetHandler().getGameProfile().getId());
            wasConnected = true;
            packetLatencySeen = false;
        }
        if (disconnectPending) {
            disconnectPending = false;
            disconnectWatchTicks = 10;
            disconnectSeenScreen = false;
            PacketObserver.setOwnId(null);
        }
        if (disconnectWatchTicks > 0) {
            if (mc.currentScreen instanceof GuiDisconnected) {
                disconnectSeenScreen = true;
            }
            if (--disconnectWatchTicks == 0 || disconnectSeenScreen) {
                disconnectWatchTicks = 0;
                if (wasConnected) {
                    mon.onDisconnected(now, disconnectSeenScreen, disconnectSeenScreen
                            ? "disconnect screen shown" : "no disconnect screen shown");
                    wasConnected = false;
                }
            }
        }

        // Drain passive packet observations (bounded queue filled on the Netty thread).
        PacketObserver.Event e;
        int guard = 0;
        while ((e = PacketObserver.poll()) != null && guard++ < 4096) {
            if (!wasConnected) {
                continue;
            }
            switch (e.type) {
                case PacketObserver.KEEPALIVE_IN:
                    mon.onKeepAliveArrival(e.wallMillis);
                    break;
                case PacketObserver.KEEPALIVE_TURNAROUND:
                    mon.onKeepAliveTurnaround(e.wallMillis, e.value);
                    break;
                case PacketObserver.LATENCY_UPDATE:
                    // Exact update observed: from now on rely on packets instead of polling the tab list.
                    packetLatencySeen = true;
                    mon.onReportedLatency(e.wallMillis, (int) e.value, true);
                    break;
                default:
                    break;
            }
        }

        Settings s = nd.settings();
        if (!s.packetInstrumentation) {
            packetLatencySeen = false;
        }
        if (wasConnected && !packetLatencySeen && now - lastMonitorSample >= s.monitorIntervalMs) {
            lastMonitorSample = now;
            int ping = ownReportedLatency(mc);
            if (ping >= 0) {
                mon.onReportedLatency(now, ping, false);
            }
        }
        mon.tick(now);
        announceAlerts(nd);
    }

    private static int announcedAlertsVersion = -1;
    private static long lastAnnouncedTimestamp;

    /** Prints new alerts to local chat according to the verbosity setting (quiet / warnings / all). */
    private static void announceAlerts(NetDiag nd) {
        int v = nd.quality.version();
        if (v == announcedAlertsVersion) {
            return;
        }
        announcedAlertsVersion = v;
        java.util.List<com.seekehr.netdiagnostics.core.quality.Alert> list = nd.quality.alerts();
        long newest = lastAnnouncedTimestamp;
        Settings.Verbosity verb = nd.settings().verbosity;
        for (int i = list.size() - 1; i >= 0; i--) {
            com.seekehr.netdiagnostics.core.quality.Alert a = list.get(i);
            if (a.timestamp <= lastAnnouncedTimestamp) {
                continue;
            }
            newest = Math.max(newest, a.timestamp);
            boolean show = verb == Settings.Verbosity.VERBOSE
                    || (verb == Settings.Verbosity.NORMAL && a.severity
                    == com.seekehr.netdiagnostics.core.quality.Alert.Severity.WARNING);
            if (show && Minecraft.getMinecraft().thePlayer != null) {
                Chat.warn("[" + a.metric + "] " + a.message);
            }
            if (verb == Settings.Verbosity.VERBOSE) {
                NetDiag.LOG.info("Alert [{}] {}: {}", a.rule, a.metric, a.message);
            }
        }
        lastAnnouncedTimestamp = newest;
    }

    /** Minecraft-reported latency of the local player from the tab list, or -1. */
    public static int ownReportedLatency(Minecraft mc) {
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h == null || mc.thePlayer == null) {
            return -1;
        }
        NetworkPlayerInfo info = h.getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? -1 : info.getResponseTime();
    }

    public static boolean channelOpen() {
        NetworkManager m = currentManager;
        return m != null && m.isChannelOpen();
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        NetDiag nd = NetDiag.get();
        if (nd != null && nd.settings().overlayVisible && Minecraft.getMinecraft().currentScreen == null) {
            HudOverlay.render(nd, event.resolution);
        }
    }
}
