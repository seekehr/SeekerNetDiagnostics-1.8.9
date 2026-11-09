package com.seekehr.netdiagnostics.core.monitor;

import java.util.Locale;

/**
 * Known game-route optimizers and VPNs that carry a game connection through their own relays or tunnel servers.
 * Recognition by name is only a starting point: the process that actually accepts the game's connection on this
 * PC is followed as well, whatever it is called (see {@link Traceroute.TcpResult#acceptedPid}).
 */
public final class Proxies {
    /** Lower-case executable-name fragment, display name. */
    private static final String[][] KNOWN = {
            {"exitlag", "ExitLag"},
            {"noping", "NoPing"},
            {"wtfast", "WTFast"},
            {"mudfish", "Mudfish"},
            {"haste", "Haste"},
            {"gearup", "GearUP"},
            {"lagofast", "LagoFast"},
            {"outfox", "Outfox"},
            {"pingzapper", "PingZapper"},
            {"battleping", "Battleping"},
            {"pingbooster", "Ping Booster"},
            {"wireguard", "WireGuard"},
            {"openvpn", "OpenVPN"},
            {"mullvad", "Mullvad VPN"},
            {"nordvpn", "NordVPN"},
            {"nordlynx", "NordVPN"},
            {"expressvpn", "ExpressVPN"},
            {"protonvpn", "Proton VPN"},
            {"surfshark", "Surfshark"},
            {"softether", "SoftEther VPN"},
            {"vpnclient", "VPN client"},
            {"zerotier", "ZeroTier"},
            {"tailscale", "Tailscale"},
    };

    /** Adapter-description fragments of tunnel/VPN virtual adapters. */
    private static final String[] TUNNEL_ADAPTERS = {"tap-windows", "tap adapter", "wintun", "wireguard", "openvpn",
            "nordlynx", "mullvad", "exitlag", "noping", "wtfast", "mudfish", "zerotier", "tailscale", "softether",
            "vpn", "tunnel"};

    private Proxies() {
    }

    /** Display name of a known optimizer/VPN from a process name or "Name.exe (pid N)" text, or null. */
    public static String known(String processName) {
        if (processName == null) {
            return null;
        }
        String s = processName.toLowerCase(Locale.ROOT);
        for (String[] k : KNOWN) {
            if (s.contains(k[0])) {
                return k[1];
            }
        }
        return null;
    }

    /** True for a virtual tunnel/VPN adapter description such as "Wintun Userspace Tunnel". */
    public static boolean tunnelAdapter(String adapterDescription) {
        if (adapterDescription == null) {
            return false;
        }
        String s = adapterDescription.toLowerCase(Locale.ROOT);
        for (String t : TUNNEL_ADAPTERS) {
            if (s.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Friendly name for a process: the known product name, else the executable without ".exe" and without a
     * trailing " (pid N)".
     */
    public static String label(String processName) {
        String k = known(processName);
        if (k != null) {
            return k;
        }
        if (processName == null) {
            return "a local proxy";
        }
        String s = processName.replaceFirst("\\s*\\(pid \\d+\\)$", "");
        return s.toLowerCase(Locale.ROOT).endsWith(".exe") ? s.substring(0, s.length() - 4) : s;
    }
}
