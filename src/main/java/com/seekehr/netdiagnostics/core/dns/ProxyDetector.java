package com.seekehr.netdiagnostics.core.dns;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Detects servers that sit behind a DDoS-protection / Minecraft proxy network (Cloudflare Spectrum, TCPShield,
 * NeoProtect, "...shield" proxies, ...). Such an IP is only the proxy's edge: traceroute stops there and the
 * proxy-to-backend leg is invisible. Evidence comes from the hostname's CNAME chain, the SRV target, the IP's
 * reverse DNS (PTR) and the IP's network owner (AS). Async and cached; {@link #get} never blocks.
 */
public final class ProxyDetector {
    /** Detection result; {@code provider} is null when no proxy was found. */
    public static final class Result {
        public final String provider;
        /** What matched, e.g. "CNAME play.x.net -> abc.tcpshield.com" or "AS13335 CLOUDFLARENET". */
        public final String evidence;

        Result(String provider, String evidence) {
            this.provider = provider;
            this.evidence = evidence;
        }
    }

    public static final Result PENDING = new Result(null, null);
    public static final Result NONE = new Result(null, null);

    /** Normalized (lowercase, letters/digits only) keyword -> display name. Checked in order. */
    private static final String[][] PROVIDERS = {
            {"cloudflare", "Cloudflare"},
            {"tcpshield", "TCPShield"},
            {"neoprotect", "NeoProtect"},
            {"cosmicguard", "CosmicGuard"},
            {"infinityfilter", "Infinity Filter"},
            {"pathnetwork", "Path.net"},
            {"pathnet", "Path.net"},
            {"gslnetworks", "GSL Networks"},
            {"ddosguard", "DDoS-Guard"},
            {"stormwall", "StormWall"},
            {"x4bnet", "X4B"},
            {"voxility", "Voxility"},
            {"incapsula", "Imperva"},
            {"imperva", "Imperva"},
            {"prolexic", "Akamai Prolexic"},
    };

    private static final ConcurrentHashMap<String, Result> CACHE = new ConcurrentHashMap<String, Result>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SeekerNetDiagnostics-proxy");
            t.setDaemon(true);
            return t;
        }
    });

    private ProxyDetector() {
    }

    /**
     * @param host the hostname actually connected to (after SRV), or null
     * @param srvTarget SRV target host, or null
     * @param ip the resolved server IP
     */
    public static Result get(final String host, final String srvTarget, final String ip) {
        if (ip == null) {
            return PENDING;
        }
        final String key = host + "|" + srvTarget + "|" + ip;
        Result r = CACHE.get(key);
        if (r != null) {
            return r;
        }
        if (CACHE.putIfAbsent(key, PENDING) == null) {
            WORKER.execute(new Runnable() {
                @Override
                public void run() {
                    CACHE.put(key, detect(host, srvTarget, ip));
                }
            });
        }
        return PENDING;
    }

    private static Result detect(String host, String srvTarget, String ip) {
        // 1. hostname chain (strongest: TCPShield & co. are set up with a CNAME to their edge)
        List<String> names = new ArrayList<String>();
        if (srvTarget != null) {
            names.add(srvTarget);
        }
        if (host != null && !DnsResolver.isIpLiteral(host) && !host.equals(srvTarget)) {
            names.add(host);
        }
        for (String n : names) {
            String prev = n;
            for (String c : cnameChain(n)) {
                String p = match(c);
                if (p != null) {
                    return new Result(p, "CNAME " + prev + " -> " + c);
                }
                prev = c;
            }
            String p = match(n);
            if (p != null) {
                return new Result(p, "hostname " + n);
            }
        }
        // 2. reverse DNS of the IP
        try {
            String ptr = InetAddress.getByName(ip).getCanonicalHostName();
            if (ptr != null && !ptr.equals(ip)) {
                String p = match(ptr);
                if (p != null) {
                    return new Result(p, "reverse DNS " + ptr);
                }
            }
        } catch (Exception ignored) {
            // no PTR
        }
        // 3. network owner of the IP
        try {
            AsnLookup.Info a = AsnLookup.await(ip, 8000);
            if (a != null && a.asn != null && a.name != null) {
                String p = match(a.name);
                if (p != null) {
                    return new Result(p, a.asn + " " + a.name);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return NONE;
    }

    private static List<String> cnameChain(String host) {
        List<String> out = new ArrayList<String>();
        String cur = host;
        for (int i = 0; i < 6; i++) {
            List<String> c;
            try {
                c = AsnLookup.records(cur, "CNAME");
            } catch (Exception e) {
                break; // no CNAME (NameNotFound) or lookup failure
            }
            if (c.isEmpty()) {
                break;
            }
            cur = stripDot(c.get(0));
            out.add(cur);
        }
        return out;
    }

    // ------------------------------------------------------------------ pure helpers (tested)

    /** Provider name if {@code text} (hostname or AS name) names a known proxy network, else null. */
    static String match(String text) {
        if (text == null) {
            return null;
        }
        String norm = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String[] p : PROVIDERS) {
            if (norm.contains(p[0])) {
                return p[1];
            }
        }
        // generic "<name>shield" / anti-DDoS proxies (DonutShield, ...): name it after the matching word
        for (String word : text.split("[^A-Za-z0-9]+")) {
            String w = word.toLowerCase(Locale.ROOT);
            if (w.endsWith("shield") || w.contains("antiddos") || w.equals("ddos") || w.endsWith("ddos")) {
                return capitalize(word);
            }
        }
        return null;
    }

    static String stripDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String capitalize(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        if (l.endsWith("shield") && l.length() > 6) {
            return Character.toUpperCase(l.charAt(0)) + l.substring(1, l.length() - 6) + "Shield";
        }
        return Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }
}
