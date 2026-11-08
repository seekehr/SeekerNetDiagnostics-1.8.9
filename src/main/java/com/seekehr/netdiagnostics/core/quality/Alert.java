package com.seekehr.netdiagnostics.core.quality;

import java.util.LinkedHashMap;
import java.util.Map;

/** A stability alert. {@link #metric} names exactly which measurement triggered it. */
public final class Alert {
    public enum Severity {
        INFO, WARNING
    }

    public final long timestamp;
    public final String rule;
    public final String metric;
    public final Severity severity;
    public final String message;

    public Alert(long timestamp, String rule, String metric, Severity severity, String message) {
        this.timestamp = timestamp;
        this.rule = rule;
        this.metric = metric;
        this.severity = severity;
        this.message = message;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("timestamp", timestamp);
        m.put("rule", rule);
        m.put("metric", metric);
        m.put("severity", severity.name());
        m.put("message", message);
        return m;
    }
}
