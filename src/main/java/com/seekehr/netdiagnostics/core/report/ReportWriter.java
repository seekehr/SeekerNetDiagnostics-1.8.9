package com.seekehr.netdiagnostics.core.report;

import com.seekehr.netdiagnostics.core.json.Csv;
import com.seekehr.netdiagnostics.core.json.Json;
import com.seekehr.netdiagnostics.core.quality.Alert;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes JSON and CSV reports. Sensitive identifiers (session tokens, account names/UUIDs, public IP)
 * are never collected; local interface addresses/ports are only included when explicitly enabled.
 */
public final class ReportWriter {
    private ReportWriter() {
    }

    public static final class Input {
        public Map<String, Object> environment = new LinkedHashMap<String, Object>();
        public Map<String, Object> settings = new LinkedHashMap<String, Object>();
        public List<TestRun> runs = new ArrayList<TestRun>();
        public Map<String, Object> comparison;
        public Map<String, Object> monitor;
        public List<Alert> alerts = new ArrayList<Alert>();
        public boolean includeLocalAddresses;
        public ZonedDateTime now = ZonedDateTime.now();
    }

    public static Map<String, Object> buildJson(Input in) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("generatedAt", in.now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("timezone", in.now.getZone().getId());
        meta.putAll(in.environment);
        meta.put("privacy", "Excludes Minecraft session tokens, account identifiers and public IP address. Local "
                + "interface addresses " + (in.includeLocalAddresses ? "are included (user setting)." : "are excluded."));
        meta.put("measurementNotes", list(
                "TCP handshake times measure connection establishment from the Minecraft JVM, not game latency.",
                "Minecraft status ping/pong and status response times are application-level measurements.",
                "Timeouts are not treated as proof of packet loss.",
                Conclusions.EXITLAG_NOT_VERIFIED));
        root.put("meta", meta);
        root.put("settings", in.settings);
        List<Object> runs = new ArrayList<Object>();
        for (TestRun r : in.runs) {
            runs.add(r.toMap(in.includeLocalAddresses));
        }
        root.put("runs", runs);
        root.put("routeComparison", in.comparison);
        root.put("liveMonitor", in.monitor);
        List<Object> alerts = new ArrayList<Object>();
        for (Alert a : in.alerts) {
            alerts.add(a.toMap());
        }
        root.put("alerts", alerts);
        return root;
    }

    private static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<Object>();
        for (Object o : items) {
            l.add(o);
        }
        return l;
    }

    public static final String[] SAMPLE_HEADER = {"run_id", "run_started", "test_method", "host", "port",
            "connect_host", "connect_port", "resolved_ip", "profile_label", "exitlag_settings", "timeout_ms",
            "attempt_index", "warmup", "attempt_time", "outcome", "rtt_ms", "elapsed_ms", "local_address",
            "local_port", "detail"};

    public static String buildSamplesCsv(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row(SAMPLE_HEADER));
        DateTimeFormatter f = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        for (TestRun r : in.runs) {
            String started = time(r.startedAt, in, f);
            for (TestRun.Attempt a : r.attempts()) {
                sb.append(Csv.row(String.valueOf(r.id), started, r.kind.label, r.target.host,
                        String.valueOf(r.target.port), r.connectHost, String.valueOf(r.connectPort),
                        nz(r.resolvedAddress), nz(r.profileLabel), nz(r.exitLagDescription),
                        String.valueOf(r.tcpTimeoutMs), String.valueOf(a.index), String.valueOf(a.warmup),
                        time(a.timestamp, in, f), a.outcome.name(), num(a.rttMs), num(a.elapsedMs),
                        in.includeLocalAddresses ? nz(a.localAddress) : "",
                        in.includeLocalAddresses && a.localPort > 0 ? String.valueOf(a.localPort) : "",
                        nz(a.detail)));
            }
        }
        return sb.toString();
    }

    public static final String[] SUMMARY_HEADER = {"run_id", "run_started", "test_method", "host", "port",
            "resolved_ip", "dns_lookup_ms", "dns_min_ttl_s", "profile_label", "exitlag_settings", "timeout_ms",
            "samples_requested", "successful", "failed", "timeouts", "refused", "median_ms", "mean_ms", "min_ms",
            "max_ms", "p95_ms", "p99_ms", "stddev_ms", "jitter_ms", "mc_status_ok", "mc_ping_pong_ms",
            "mc_status_response_ms", "mc_tcp_connect_ms", "mc_error", "duration_ms", "state"};

    public static String buildSummaryCsv(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row(SUMMARY_HEADER));
        DateTimeFormatter f = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        for (TestRun r : in.runs) {
            StatSummary s = r.summary();
            sb.append(Csv.row(String.valueOf(r.id), time(r.startedAt, in, f), r.kind.label, r.target.host,
                    String.valueOf(r.target.port), nz(r.resolvedAddress),
                    r.dns == null ? "" : num(r.dns.systemLookupMs),
                    r.dns == null || r.dns.minTtlSeconds < 0 ? "" : String.valueOf(r.dns.minTtlSeconds),
                    nz(r.profileLabel), nz(r.exitLagDescription), String.valueOf(r.tcpTimeoutMs),
                    String.valueOf(r.samplesRequested), String.valueOf(s.count), String.valueOf(r.failedAttempts()),
                    String.valueOf(r.count(com.seekehr.netdiagnostics.core.net.ProbeOutcome.TIMEOUT)),
                    String.valueOf(r.count(com.seekehr.netdiagnostics.core.net.ProbeOutcome.REFUSED)),
                    num(s.median), num(s.mean), num(s.min), num(s.max), num(s.p95), num(s.p99), num(s.stdDev),
                    num(s.jitter),
                    r.mcStatus == null ? "" : String.valueOf(r.mcStatus.statusOk),
                    r.mcStatus == null ? "" : num(r.mcStatus.pingPongMs),
                    r.mcStatus == null ? "" : num(r.mcStatus.statusResponseMs),
                    r.mcStatus == null ? "" : num(r.mcStatus.tcpConnectMs),
                    r.mcStatus == null ? "" : nz(r.mcStatus.error),
                    String.valueOf(r.durationMs()), r.state.name()));
        }
        return sb.toString();
    }

    private static String time(long epochMs, Input in, DateTimeFormatter f) {
        if (epochMs <= 0) {
            return "";
        }
        return java.time.Instant.ofEpochMilli(epochMs).atZone(in.now.getZone()).format(f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String num(double d) {
        return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.3f", d);
    }

    /** Writes the enabled formats into {@code dir}; returns the files written. */
    public static List<File> write(File dir, Input in, boolean json, boolean csv) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create report directory " + dir);
        }
        String stamp = in.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        List<File> out = new ArrayList<File>();
        if (json) {
            File f = unique(dir, "report-" + stamp, ".json");
            writeFile(f, Json.write(buildJson(in)));
            out.add(f);
        }
        if (csv) {
            File s = unique(dir, "report-" + stamp + "-samples", ".csv");
            writeFile(s, buildSamplesCsv(in));
            out.add(s);
            File m = unique(dir, "report-" + stamp + "-summary", ".csv");
            writeFile(m, buildSummaryCsv(in));
            out.add(m);
        }
        return out;
    }

    static File unique(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        int i = 2;
        while (f.exists() && i < 1000) {
            f = new File(dir, base + "-" + i++ + ext);
        }
        return f;
    }

    public static void writeFile(File f, String content) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(content.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }
}
