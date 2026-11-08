package com.seekehr.netdiagnostics.core.dns;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Who owns an IP address (its origin AS), via Team Cymru's DNS TXT service
 * ({@code <reversed-ip>.origin.asn.cymru.com}, then {@code AS<n>.asn.cymru.com} for the name).
 * Lookups run on one background thread and are cached; {@link #get} never blocks.
 */
public final class AsnLookup {
    /** Owner of an address. {@code asn} is null when unknown or not routed publicly. */
    public static final class Info {
        public final String asn;
        public final String prefix;
        public final String country;
        /** Full registry name, e.g. "GOOGLE - Google LLC, US". */
        public final String name;
        /** Short label for tight layouts, e.g. "AS15169 GOOGLE". */
        public final String label;

        Info(String asn, String prefix, String country, String name, String label) {
            this.asn = asn;
            this.prefix = prefix;
            this.country = country;
            this.name = name;
            this.label = label;
        }
    }

    private static final Info PENDING = new Info(null, null, null, null, "...");
    private static final Info PRIVATE = new Info(null, null, null, "Private address (your LAN or ISP's CGNAT)",
            "private");
    private static final Info NONE = new Info(null, null, null, "No public route announced", "no ASN");
    private static final Info FAILED = new Info(null, null, null, "Lookup failed", "?");

    private static final ConcurrentHashMap<String, Info> BY_IP = new ConcurrentHashMap<String, Info>();
    private static final ConcurrentHashMap<String, Long> FAILED_AT = new ConcurrentHashMap<String, Long>();
    private static final long RETRY_MS = 30000;
    private static final ConcurrentHashMap<String, String> AS_NAMES = new ConcurrentHashMap<String, String>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SeekerNetDiagnostics-asn");
            t.setDaemon(true);
            return t;
        }
    });

    private AsnLookup() {
    }

    /** Cached owner of {@code ip}, starting a background lookup the first time. Null for a null ip. */
    public static Info get(final String ip) {
        if (ip == null) {
            return null;
        }
        Info cached = BY_IP.get(ip);
        if (cached == FAILED) {
            Long at = FAILED_AT.get(ip);
            if (at != null && System.currentTimeMillis() - at < RETRY_MS) {
                return FAILED;
            }
            BY_IP.remove(ip, FAILED);
        } else if (cached != null) {
            return cached;
        }
        if (DnsResolver.isLocalOrPrivate(ip)) {
            BY_IP.put(ip, PRIVATE);
            return PRIVATE;
        }
        if (BY_IP.putIfAbsent(ip, PENDING) == null) {
            WORKER.execute(new Runnable() {
                @Override
                public void run() {
                    Info r = resolve(ip);
                    if (r == FAILED) {
                        FAILED_AT.put(ip, System.currentTimeMillis());
                    }
                    BY_IP.put(ip, r);
                }
            });
        }
        return PENDING;
    }

    public static boolean pending(Info i) {
        return i == PENDING;
    }

    private static Info resolve(String ip) {
        String q = originQuery(ip);
        if (q == null) {
            return FAILED;
        }
        try {
            List<String> txt = txt(q);
            if (txt.isEmpty()) {
                return NONE;
            }
            String[] best = null;
            int bestLen = -1;
            for (String t : txt) {
                String[] f = fields(t);
                if (f.length < 3) {
                    continue;
                }
                int slash = f[1].indexOf('/');
                int len = slash < 0 ? 0 : Integer.parseInt(f[1].substring(slash + 1).trim());
                if (len > bestLen) {
                    best = f;
                    bestLen = len;
                }
            }
            if (best == null) {
                return NONE;
            }
            String asn = best[0].split("\\s+")[0];
            String name = asName(asn);
            return new Info("AS" + asn, best[1], best[2], name, label("AS" + asn, name));
        } catch (NameNotFoundException e) {
            return NONE;
        } catch (Exception e) {
            return FAILED; // retried after RETRY_MS
        }
    }

    private static String asName(String asn) {
        String cached = AS_NAMES.get(asn);
        if (cached != null) {
            return cached;
        }
        String name = null;
        try {
            List<String> txt = txt("AS" + asn + ".asn.cymru.com");
            if (!txt.isEmpty()) {
                String[] f = fields(txt.get(0));
                name = f.length >= 5 ? f[4] : null;
            }
        } catch (Exception ignored) {
            // name is optional
        }
        if (name != null) {
            AS_NAMES.put(asn, name);
        }
        return name;
    }

    // ------------------------------------------------------------------ pure helpers (tested)

    /** The origin query name for an IP literal, or null if it is not one. */
    static String originQuery(String ip) {
        if (!DnsResolver.isIpLiteral(ip)) {
            return null;
        }
        byte[] b;
        try {
            b = InetAddress.getByName(ip).getAddress(); // literal: no lookup
        } catch (Exception e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (b.length == 4) {
            for (int i = 3; i >= 0; i--) {
                sb.append(b[i] & 0xFF).append('.');
            }
            return sb.append("origin.asn.cymru.com").toString();
        }
        for (int i = b.length - 1; i >= 0; i--) {
            sb.append(Character.forDigit(b[i] & 0xF, 16)).append('.');
            sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append('.');
        }
        return sb.append("origin6.asn.cymru.com").toString();
    }

    /** Splits a Cymru TXT answer ("a | b | c") into trimmed fields, dropping surrounding quotes. */
    static String[] fields(String txt) {
        String t = txt.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1);
        }
        String[] f = t.split("\\|");
        for (int i = 0; i < f.length; i++) {
            f[i] = f[i].trim();
        }
        return f;
    }

    /** "AS15169" + "GOOGLE - Google LLC, US" -> "AS15169 GOOGLE". */
    static String label(String asn, String name) {
        if (name == null || name.isEmpty()) {
            return asn;
        }
        String n = name;
        int dash = n.indexOf(" - ");
        if (dash > 0) {
            n = n.substring(0, dash);
        } else {
            int comma = n.lastIndexOf(", ");
            if (comma > 0 && n.length() - comma == 4) {
                n = n.substring(0, comma); // trailing ", CC"
            }
        }
        return asn + " " + n.trim();
    }

    private static List<String> txt(String name) throws Exception {
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns:");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        DirContext ctx = new InitialDirContext(env);
        try {
            Attributes attrs = ctx.getAttributes(name, new String[]{"TXT"});
            List<String> out = new ArrayList<String>();
            Attribute a = attrs.get("TXT");
            if (a != null) {
                NamingEnumeration<?> e = a.getAll();
                while (e.hasMore()) {
                    out.add(String.valueOf(e.next()));
                }
            }
            return out;
        } finally {
            ctx.close();
        }
    }
}
