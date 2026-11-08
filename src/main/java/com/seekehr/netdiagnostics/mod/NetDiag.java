package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.SeekerNetDiagnostics;
import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.core.monitor.Traceroute;
import com.seekehr.netdiagnostics.core.net.Target;
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
    private static volatile Traceroute traceroute;
    private static volatile int tabPing = -1;
    private static volatile Target gameTarget;

    private NetDiag() {
    }

    public static PingMonitor monitor() {
        return monitor;
    }

    public static Traceroute traceroute() {
        return traceroute;
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
        monitor = m;
        m.start();
        return m;
    }

    public static void stop() {
        PingMonitor m = monitor;
        if (m != null) {
            m.stop();
        }
        Traceroute tr = traceroute;
        if (tr != null) {
            tr.cancel();
        }
    }

    public static Traceroute startTraceroute(String address) {
        Traceroute old = traceroute;
        if (old != null) {
            old.cancel();
        }
        Traceroute tr = new Traceroute(address);
        traceroute = tr;
        tr.start();
        return tr;
    }

    /** Client tick: caches the tab-list ping and stops monitoring once you leave the server. */
    static void tick() {
        ServerData d = currentServer();
        gameTarget = targetOf(d);
        tabPing = readTabPing();
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
