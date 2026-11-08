package com.seekehr.netdiagnostics.core.net;

import com.seekehr.netdiagnostics.core.dns.DnsResolver;

/** A host and TCP port to test. */
public final class Target {
    public static final int DEFAULT_PORT = 25565;
    private static final int MAX_HOST_LENGTH = 253;

    public final String host;
    public final int port;
    /** False when the port was defaulted, which allows Minecraft SRV lookups. */
    public final boolean portExplicit;

    public Target(String host, int port, boolean portExplicit) {
        this.host = host;
        this.port = port;
        this.portExplicit = portExplicit;
    }

    public String display() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    @Override
    public String toString() {
        return display();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Target)) {
            return false;
        }
        Target t = (Target) o;
        return port == t.port && host.equalsIgnoreCase(t.host);
    }

    @Override
    public int hashCode() {
        return host.toLowerCase(java.util.Locale.ROOT).hashCode() * 31 + port;
    }

    /**
     * Parses user input. Accepts {@code host}, {@code host:port}, {@code [ipv6]:port}, bare IPv6, and an
     * optional separate port argument (which wins). Throws IllegalArgumentException with a friendly message.
     */
    public static Target parse(String hostInput, String portInput) {
        if (hostInput == null || hostInput.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a host name or IP address.");
        }
        String h = hostInput.trim();
        Integer port = null;
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Missing ']' in IPv6 address.");
            }
            String rest = h.substring(close + 1);
            h = h.substring(1, close);
            if (rest.startsWith(":") && rest.length() > 1) {
                port = parsePort(rest.substring(1));
            } else if (!rest.isEmpty()) {
                throw new IllegalArgumentException("Unexpected text after IPv6 address.");
            }
        } else {
            int first = h.indexOf(':');
            if (first >= 0 && first == h.lastIndexOf(':')) {
                String p = h.substring(first + 1);
                h = h.substring(0, first);
                if (!p.isEmpty()) {
                    port = parsePort(p);
                }
            }
        }
        if (portInput != null && !portInput.trim().isEmpty()) {
            port = parsePort(portInput.trim());
        }
        if (h.isEmpty() || h.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("Host name is empty or too long.");
        }
        if (!DnsResolver.isIpLiteral(h) && !h.matches("^[A-Za-z0-9]([A-Za-z0-9\\-_.]*[A-Za-z0-9])?\\.?$")) {
            throw new IllegalArgumentException("'" + h + "' is not a valid host name or IP address.");
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return new Target(h, port == null ? DEFAULT_PORT : port, port != null);
    }

    public static int parsePort(String s) {
        int p;
        try {
            p = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number between 1 and 65535.");
        }
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        return p;
    }
}
