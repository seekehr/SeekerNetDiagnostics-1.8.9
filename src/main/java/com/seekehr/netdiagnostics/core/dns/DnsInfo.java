package com.seekehr.netdiagnostics.core.dns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of resolving a test target. */
public final class DnsInfo {
    public String query;
    public boolean ipLiteral;
    /** Time spent in the OS resolver (InetAddress), ms. May reflect a cached answer. */
    public double lookupMs = Double.NaN;
    public final List<String> addresses = new ArrayList<String>();
    /** SRV record that was followed, e.g. "_minecraft._tcp.host -> target:port", or null. */
    public String srvRecord;
    public String error;

    public boolean resolved() {
        return !addresses.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("query", query);
        m.put("ipLiteral", ipLiteral);
        m.put("lookupMs", lookupMs);
        m.put("addresses", new ArrayList<String>(addresses));
        m.put("srvRecord", srvRecord);
        m.put("error", error);
        return m;
    }
}
