package com.seekehr.netdiagnostics.core.dns;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DNS resolution for diagnostics. Must only be called from worker threads (it blocks).
 *
 * <p>Addresses always come from the operating-system resolver ({@link InetAddress}) because that is
 * what Minecraft itself uses. A supplementary direct UDP query to the system's configured DNS server
 * is attempted only to obtain record TTLs and SRV records; if it fails the TTL is reported as not
 * obtainable instead of guessed.</p>
 */
public final class DnsResolver {
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int directTimeoutMs;

    public DnsResolver(int directTimeoutMs) {
        this.directTimeoutMs = Math.max(250, directTimeoutMs);
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

    /** Resolve {@code host} and fill timing, addresses and (where obtainable) TTL information. */
    public DnsInfo resolve(String host, boolean preferIpv4) {
        DnsInfo info = new DnsInfo();
        info.query = host;
        info.ipLiteral = isIpLiteral(host);
        long t0 = System.nanoTime();
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            info.systemLookupMs = (System.nanoTime() - t0) / 1e6;
            info.error = "DNS resolution failed: host '" + host + "' could not be resolved";
            return info;
        } catch (SecurityException e) {
            info.error = "DNS resolution blocked: " + e.getMessage();
            return info;
        }
        info.systemLookupMs = (System.nanoTime() - t0) / 1e6;
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
        if (info.ipLiteral) {
            info.notes.add("Target is an IP address; no DNS lookup was needed.");
            return info;
        }
        info.notes.add("Lookup time is from the operating-system resolver and may reflect a cached answer.");
        directLookup(host, info);
        return info;
    }

    private void directLookup(String host, DnsInfo info) {
        List<InetAddress> servers = systemNameservers();
        if (servers.isEmpty()) {
            info.notes.add("TTL not obtainable: the system DNS server list is not accessible from Java.");
            return;
        }
        long minTtl = Long.MAX_VALUE;
        boolean any = false;
        long t0 = System.nanoTime();
        for (int type : new int[]{DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA}) {
            DirectResult r = query(servers, host, type);
            if (r == null) {
                continue;
            }
            info.directServer = r.server.getHostAddress();
            for (DnsMessage.Record rec : r.response.answers) {
                info.directRecords.add(rec.toString());
                if (rec.type == DnsMessage.TYPE_A || rec.type == DnsMessage.TYPE_AAAA) {
                    minTtl = Math.min(minTtl, rec.ttl);
                    any = true;
                }
            }
        }
        info.directQueryMs = (System.nanoTime() - t0) / 1e6;
        if (any) {
            info.minTtlSeconds = minTtl;
        } else {
            info.notes.add("TTL not obtainable: direct DNS query returned no address records.");
        }
    }

    /** Looks up {@code _minecraft._tcp.<host>}. Returns null when no SRV record exists or lookup fails. */
    public DnsMessage.Record lookupMinecraftSrv(String host) {
        if (isIpLiteral(host)) {
            return null;
        }
        String name = "_minecraft._tcp." + host;
        DirectResult r = query(systemNameservers(), name, DnsMessage.TYPE_SRV);
        if (r != null) {
            List<DnsMessage.Record> srv = r.response.ofType(DnsMessage.TYPE_SRV);
            if (!srv.isEmpty()) {
                return lowestPriority(srv);
            }
            if (r.response.rcode == 0 || r.response.rcode == 3) {
                return null; // authoritative "no SRV"
            }
        }
        return jndiSrv(name);
    }

    static DnsMessage.Record lowestPriority(List<DnsMessage.Record> srv) {
        DnsMessage.Record best = null;
        for (DnsMessage.Record rec : srv) {
            if (best == null || rec.priority < best.priority
                    || (rec.priority == best.priority && rec.weight > best.weight)) {
                best = rec;
            }
        }
        return best;
    }

    /** Same mechanism Minecraft 1.8.9 uses for SRV lookups (JNDI DNS provider). TTL is unknown. */
    private static DnsMessage.Record jndiSrv(String name) {
        try {
            Hashtable<String, String> env = new Hashtable<String, String>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.initial", "1000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            try {
                javax.naming.directory.Attributes attrs = ctx.getAttributes(name, new String[]{"SRV"});
                javax.naming.directory.Attribute a = attrs.get("srv");
                if (a == null || a.size() == 0) {
                    return null;
                }
                String[] parts = a.get(0).toString().split(" ");
                if (parts.length < 4) {
                    return null;
                }
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return new DnsMessage.Record(name, DnsMessage.TYPE_SRV, -1, target,
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } finally {
                ctx.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class DirectResult {
        final InetAddress server;
        final DnsMessage.Response response;

        DirectResult(InetAddress server, DnsMessage.Response response) {
            this.server = server;
            this.response = response;
        }
    }

    private DirectResult query(List<InetAddress> servers, String name, int type) {
        for (InetAddress server : servers) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setSoTimeout(directTimeoutMs);
                int id = RANDOM.nextInt(0x10000);
                byte[] q = DnsMessage.buildQuery(id, name, type);
                socket.send(new DatagramPacket(q, q.length, new InetSocketAddress(server, 53)));
                byte[] buf = new byte[4096];
                long deadline = System.currentTimeMillis() + directTimeoutMs;
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    if (!server.equals(p.getAddress()) || p.getPort() != 53) {
                        if (System.currentTimeMillis() > deadline) {
                            break;
                        }
                        continue; // ignore unrelated datagrams
                    }
                    DnsMessage.Response resp = DnsMessage.parse(buf, p.getLength());
                    if (resp.id != id) {
                        if (System.currentTimeMillis() > deadline) {
                            break;
                        }
                        continue;
                    }
                    return new DirectResult(server, resp);
                }
            } catch (SocketTimeoutException e) {
                // try next server
            } catch (Exception e) {
                // malformed reply, unreachable server etc. - try next server
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }
        return null;
    }

    /**
     * System DNS servers via the JDK-internal resolver configuration (present in Java 8 on Windows,
     * Linux and macOS). Returns an empty list if unavailable.
     */
    @SuppressWarnings("unchecked")
    public static List<InetAddress> systemNameservers() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        try {
            Class<?> c = Class.forName("sun.net.dns.ResolverConfiguration");
            Object conf = c.getMethod("open").invoke(null);
            List<String> ns = (List<String>) c.getMethod("nameservers").invoke(conf);
            for (String s : ns) {
                if (isIpLiteral(s)) {
                    out.add(InetAddress.getByName(s));
                }
                if (out.size() >= 3) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            // not available on this JVM
        }
        return out;
    }
}
