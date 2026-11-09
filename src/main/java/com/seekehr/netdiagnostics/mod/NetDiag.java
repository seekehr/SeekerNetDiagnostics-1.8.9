package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.SeekerNetDiagnostics;
import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.route.RouteAnalysis;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Holds the single running monitor and reads in-game connection facts (client thread only). */
public final class NetDiag {
    public static final Logger LOG = LogManager.getLogger(SeekerNetDiagnostics.NAME);

    private static volatile PingMonitor monitor;
    private static final RouteAnalysis ROUTES = new RouteAnalysis();
    private static volatile int tabPing = -1;
    private static volatile Target gameTarget;
    /** Server the user stopped with /snd stop: not auto-started again until another server is joined. */
    private static Target userStopped;
    private static Target lastGameTarget;
    /** The running monitor was started automatically (for the HUD), not by /snd. */
    private static boolean autoStarted;

    private NetDiag() {
    }

    public static PingMonitor monitor() {
        return monitor;
    }

    /** Route analysis rounds (TCP check, relays, ICMP) and the last finished report. */
    public static RouteAnalysis routes() {
        return ROUTES;
    }

    /** /snd: starts monitoring {@code t} at the user's request. */
    public static PingMonitor monitorByUser(Target t) {
        userStopped = null;
        PingMonitor m = monitor(t);
        autoStarted = false;
        return m;
    }

    /** /snd stop: stops monitoring and keeps it off on this server until you join another one. */
    public static void stopByUser() {
        userStopped = gameTarget;
        autoStarted = false;
        stop();
    }

    /** Starts monitoring {@code t}, replacing any running monitor (unless it already targets t). */
    public static PingMonitor monitor(Target t) {
        PingMonitor m = monitor;
        if (m != null && !m.stopped() && m.target.equals(t)) {
            return m;
        }
        stop();
        m = new PingMonitor(t, new PingMonitor.TabPing() {
            @Override
            public int get() {
                return isGameServer(monitor) ? tabPing : -1;
            }
        });
        m.intervalMs = HudConfig.pingIntervalMs;
        monitor = m;
        m.start();
        return m;
    }

    public static void stop() {
        PingMonitor m = monitor;
        if (m != null) {
            m.stop();
        }
        ROUTES.stop();
    }

    /** Starts a route analysis round now, at the user's request (may start the admin helper). */
    public static void startRoutes(String address, int port) {
        ROUTES.setIntervalSec(HudConfig.traceIntervalSec);
        ROUTES.start(address, port, AdvancedTracing.helper(), true, System.currentTimeMillis());
    }

    /** The player's real ping: tab-list ping on the game server, else the measured median; NaN if unknown. */
    public static double gamePing() {
        PingMonitor m = monitor;
        int tab = tabPing;
        if (m != null && tab > 0 && isGameServer(m)) {
            return tab;
        }
        if (m != null) {
            StatSummary p = m.snapshot().ping;
            if (!p.isEmpty()) {
                return p.median;
            }
        }
        return Double.NaN;
    }

    /**
     * Keeps a monitor running on the server you are on while the HUD is enabled and auto-start is on, so the HUD
     * works without /snd. A monitor it started is stopped again if the HUD or auto-start is turned off.
     */
    private static void autoMonitor(Target g) {
        if (g == null ? lastGameTarget != null : !g.equals(lastGameTarget)) {
            userStopped = null; // joined another server (or left)
        }
        lastGameTarget = g;
        PingMonitor m = monitor;
        boolean running = m != null && !m.stopped();
        boolean want = HudConfig.enabled && HudConfig.autoMonitor;
        if (g != null && want && !running && !g.equals(userStopped)) {
            monitor(g);
            autoStarted = true;
        } else if (running && autoStarted && !want) {
            stop();
            autoStarted = false;
        }
    }

    /** Client tick: caches the tab-list ping and stops monitoring once you leave the server. */
    static void tick() {
        ServerData d = currentServer();
        gameTarget = targetOf(d);
        tabPing = readTabPing();
        autoMonitor(gameTarget);
        ROUTES.setIntervalSec(HudConfig.traceIntervalSec);
        ROUTES.tick(System.currentTimeMillis(), gamePing(), AdvancedTracing.helper());
        PingMonitor m = monitor;
        if (d == null && m != null && !m.stopped()) {
            stop();
        }
    }

    // ------------------------------------------------------------------ current server

    public static ServerData currentServer() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isIntegratedServerRunning() || mc.getNetHandler() == null) {
            return null;
        }
        return mc.getCurrentServerData();
    }

    public static Target currentServerTarget() {
        return targetOf(currentServer());
    }

    private static Target targetOf(ServerData d) {
        if (d == null || d.serverIP == null) {
            return null;
        }
        try {
            return Target.parse(d.serverIP, null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when the monitor targets the server the game is connected to. */
    public static boolean isGameServer(PingMonitor m) {
        Target g = gameTarget;
        return m != null && g != null && g.equals(m.target);
    }

    public static int tabPing() {
        return tabPing;
    }

    private static int readTabPing() {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h == null || mc.thePlayer == null || currentServer() == null) {
            return -1;
        }
        NetworkPlayerInfo info = h.getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? -1 : info.getResponseTime();
    }

    // ------------------------------------------------------------------ game socket (read-only)

    /** The live game connection's remote and local socket addresses. Fields are null when unknown. */
    public static final class GameSocket {
        public String remote;
        public String local;
        public String localInterface;
    }

    private static Field channelField;

    private static final GameSocket NO_SOCKET = new GameSocket();
    private static NetworkManager cachedManager;
    private static GameSocket cachedSocket;

    /**
     * Cached per connection: the adapter lookup ({@link java.net.NetworkInterface#getByInetAddress}) is very slow on Windows
     * and this is called every frame by the Route tab.
     */
    public static GameSocket gameSocket() {
        NetHandlerPlayClient h = Minecraft.getMinecraft().getNetHandler();
        if (h == null || currentServer() == null) {
            return NO_SOCKET;
        }
        NetworkManager nm = h.getNetworkManager();
        if (nm != cachedManager || cachedSocket == null) {
            cachedSocket = readGameSocket(nm);
            cachedManager = nm;
        }
        return cachedSocket;
    }

    private static GameSocket readGameSocket(NetworkManager nm) {
        GameSocket g = new GameSocket();
        g.remote = describe(nm.getRemoteAddress());
        Channel ch = channel(nm);
        if (ch != null) {
            SocketAddress local = ch.localAddress();
            g.local = describe(local);
            if (local instanceof InetSocketAddress && ((InetSocketAddress) local).getAddress() != null) {
                g.localInterface = PingMonitor.interfaceName(((InetSocketAddress) local).getAddress());
            }
        }
        return g;
    }

    private static Channel channel(NetworkManager nm) {
        try {
            if (channelField == null) {
                for (Field f : NetworkManager.class.getDeclaredFields()) {
                    if (Channel.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        channelField = f;
                        break;
                    }
                }
            }
            return channelField == null ? null : (Channel) channelField.get(nm);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(SocketAddress a) {
        if (a instanceof InetSocketAddress) {
            InetSocketAddress i = (InetSocketAddress) a;
            String host = i.getAddress() != null ? i.getAddress().getHostAddress() : i.getHostString();
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + i.getPort();
        }
        return a == null ? null : a.toString();
    }
}
