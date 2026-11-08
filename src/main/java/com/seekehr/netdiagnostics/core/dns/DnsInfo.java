package com.seekehr.netdiagnostics.core.dns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of resolving a diagnostic target. */
public final class DnsInfo {
    public String query;
    public boolean ipLiteral;
    /** Time spent in the OS resolver (InetAddress), ms. May reflect a cached answer. */
    public double systemLookupMs = Double.NaN;
    public final List<String> addresses = new ArrayList<String>();
    /** Records returned by a direct DNS query (includes TTLs), if that query succeeded. */
    public final List<String> directRecords = new ArrayList<String>();
    /** Lowest TTL observed for the A/AAAA answers in seconds, or -1 if not obtainable. */
    public long minTtlSeconds = -1;
    public String directServer;
    public double directQueryMs = Double.NaN;
    public String srvRecord;
    public String error;
    public final List<String> notes = new ArrayList<String>();

    public boolean resolved() {
        return !addresses.isEmpty();
    }

    public Map<String, Object> toMap(boolean includeResolverAddress) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("query", query);
        m.put("ipLiteral", ipLiteral);
        m.put("systemLookupMs", systemLookupMs);
        m.put("addresses", new ArrayList<String>(addresses));
        m.put("srvRecord", srvRecord);
        m.put("directRecords", new ArrayList<String>(directRecords));
        m.put("minTtlSeconds", minTtlSeconds >= 0 ? (Object) minTtlSeconds : null);
        m.put("directQueryMs", directQueryMs);
        m.put("directDnsServer", includeResolverAddress ? directServer : (directServer == null ? null : "(hidden)"));
        m.put("error", error);
        m.put("notes", new ArrayList<String>(notes));
        return m;
    }
}
