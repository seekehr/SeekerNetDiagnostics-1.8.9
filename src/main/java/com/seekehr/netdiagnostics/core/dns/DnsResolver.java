package com.seekehr.netdiagnostics.core.dns;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves targets the same way Minecraft 1.8.9 does: SRV records through the JNDI DNS provider and
 * addresses through the operating-system resolver ({@link InetAddress}). Blocking; worker threads only.
 */
public final class DnsResolver {
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private DnsResolver() {
    }

    /** An SRV answer. */
    public static final class Srv {
        public final String target;
        public final int port;
        public final int priority;
        public final int weight;

        public Srv(String target, int port, int priority, int weight) {
            this.target = target;
            this.port = port;
            this.priority = priority;
            this.weight = weight;
        }
    }

    public static boolean isIpLiteral(String host) {
        if (host == null) {
            return false;
        }
        if (IPV4.matcher(host).matches()) {
            for (String part : host.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return host.indexOf(':') >= 0 && host.matches("^[0-9A-Fa-f:.%a-zA-Z0-9]+$");
    }

    /**
     * True for addresses that can legitimately answer a TCP handshake in under a millisecond:
     * loopback, link-local, site-local (private LAN), CGNAT and unique-local IPv6.
     */
    public static boolean isLocalOrPrivate(String ip) {
        if (ip == null || !isIpLiteral(ip)) {
            return false;
        }
        try {
            InetAddress a = InetAddress.getByName(ip); // literal: no lookup
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            if (b.length == 4) {
                int o0 = b[0] & 0xff;
                int o1 = b[1] & 0xff;
                return o0 == 100 && o1 >= 64 && o1 <= 127; // 100.64.0.0/10 (CGNAT)
            }
            return (b[0] & 0xfe) == 0xfc; // fc00::/7
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Resolve {@code host} with the OS resolver, timing the lookup. */
    public static DnsInfo resolve(String host, boolean preferIpv4) {
        DnsInfo info = new DnsInfo();
        info.query = host;
        info.ipLiteral = isIpLiteral(host);
        long t0 = System.nanoTime();
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            info.lookupMs = (System.nanoTime() - t0) / 1e6;
            info.error = "DNS resolution failed: host '" + host + "' could not be resolved";
            return info;
        } catch (SecurityException e) {
            info.error = "DNS resolution blocked: " + e.getMessage();
            return info;
        }
        info.lookupMs = (System.nanoTime() - t0) / 1e6;
        List<InetAddress> ordered = new ArrayList<InetAddress>();
        Collections.addAll(ordered, all);
        if (preferIpv4) {
            List<InetAddress> v4 = new ArrayList<InetAddress>();
            List<InetAddress> v6 = new ArrayList<InetAddress>();
            for (InetAddress a : ordered) {
                (a instanceof Inet4Address ? v4 : v6).add(a);
            }
            ordered.clear();
            ordered.addAll(v4);
            ordered.addAll(v6);
        }
        for (InetAddress a : ordered) {
            info.addresses.add(a.getHostAddress());
        }
        return info;
    }

    /** Looks up {@code _minecraft._tcp.<host>} like the vanilla client. Returns null if there is none. */
    public static Srv lookupMinecraftSrv(String host) {
        if (isIpLiteral(host)) {
            return null;
        }
        try {
            Hashtable<String, String> env = new Hashtable<String, String>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.initial", "1000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            try {
                javax.naming.directory.Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host,
                        new String[]{"SRV"});
                javax.naming.directory.Attribute a = attrs.get("srv");
                if (a == null) {
                    return null;
                }
                List<Srv> list = new ArrayList<Srv>();
                for (int i = 0; i < a.size(); i++) {
                    Srv s = parseSrv(String.valueOf(a.get(i)));
                    if (s != null) {
                        list.add(s);
                    }
                }
                return best(list);
            } finally {
                ctx.close();
            }
        } catch (Throwable t) {
            return null; // NXDOMAIN, no SRV, or DNS unavailable: use the host as given
        }
    }

    /** Parses JNDI SRV text: "priority weight port target." */
    static Srv parseSrv(String text) {
        String[] p = text.trim().split("\\s+");
        if (p.length < 4) {
            return null;
        }
        try {
            String target = p[3].endsWith(".") ? p[3].substring(0, p[3].length() - 1) : p[3];
            int port = Integer.parseInt(p[2]);
            if (target.isEmpty() || port < 1 || port > 65535) {
                return null;
            }
            return new Srv(target, port, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Lowest priority wins; ties go to the highest weight. */
    static Srv best(List<Srv> list) {
        Srv best = null;
        for (Srv s : list) {
            if (best == null || s.priority < best.priority || (s.priority == best.priority && s.weight > best.weight)) {
                best = s;
            }
        }
        return best;
    }
}
