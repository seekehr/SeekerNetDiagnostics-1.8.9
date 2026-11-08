package com.seekehr.netdiagnostics.core.report;

import com.seekehr.netdiagnostics.core.json.Csv;
import com.seekehr.netdiagnostics.core.json.Json;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes JSON and CSV reports. No session tokens, account identifiers, public IP or local addresses
 * are ever collected, so none can be exported.
 */
public final class ReportWriter {
    private ReportWriter() {
    }

    public static final class Input {
        public Map<String, Object> environment = new LinkedHashMap<String, Object>();
        public Map<String, Object> settings = new LinkedHashMap<String, Object>();
        public List<TestRun> runs = new ArrayList<TestRun>();
        public Map<String, Object> comparison;
        public ZonedDateTime now = ZonedDateTime.now();
    }

    public static Map<String, Object> buildJson(Input in) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("generatedAt", in.now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("timezone", in.now.getZone().getId());
        meta.putAll(in.environment);
        meta.put("privacy", "Contains no Minecraft session tokens, account identifiers, public IP or local addresses.");
        List<Object> notes = new ArrayList<Object>();
        notes.add("pingMs is the Minecraft status ping/pong round trip, measured from the Minecraft JVM with a new "
                + "connection per sample (application-level, end to end).");
        notes.add("connectMs is the TCP connect time. Under a local proxy such as ExitLag it can be under 1 ms and "
                + "does not reflect distance to the server.");
        notes.add("statusMs includes the server's time to build its status response.");
        notes.add(Conclusions.EXITLAG_NOT_VERIFIED);
        meta.put("measurementNotes", notes);
        root.put("meta", meta);
        root.put("settings", in.settings);
        List<Object> runs = new ArrayList<Object>();
        for (TestRun r : in.runs) {
            runs.add(r.toMap());
        }
        root.put("runs", runs);
        root.put("routeComparison", in.comparison);
        return root;
    }

    public static final String[] SAMPLE_HEADER = {"run_id", "run_started", "host", "port", "connect_host",
            "connect_port", "resolved_ip", "profile_label", "exitlag_settings", "timeout_ms", "sample_index", "warmup",
            "sample_time", "outcome", "ping_ms", "connect_ms", "status_ms", "detail"};

    public static String buildSamplesCsv(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row(SAMPLE_HEADER));
        for (TestRun r : in.runs) {
            String started = time(r.startedAt, in);
            for (TestRun.Attempt a : r.attempts()) {
                sb.append(Csv.row(String.valueOf(r.id), started, r.target.host, String.valueOf(r.target.port),
                        r.connectHost, String.valueOf(r.connectPort), nz(r.resolvedAddress), nz(r.profileLabel),
                        nz(r.exitLagDescription), String.valueOf(r.timeoutMs), String.valueOf(a.index),
                        String.valueOf(a.warmup), time(a.timestamp, in), a.outcome.name(), num(a.pingMs),
                        num(a.connectMs), num(a.statusMs), nz(a.detail)));
            }
        }
        return sb.toString();
    }

    public static final String[] SUMMARY_HEADER = {"run_id", "run_started", "host", "port", "resolved_ip",
            "dns_lookup_ms", "profile_label", "exitlag_settings", "timeout_ms", "samples_requested", "successful",
            "failed", "connect_timeouts", "no_response", "ping_median_ms", "ping_mean_ms", "ping_min_ms",
            "ping_max_ms", "ping_p95_ms", "ping_p99_ms", "ping_stddev_ms", "ping_jitter_ms", "connect_median_ms",
            "local_intercept_detected", "server_version", "duration_ms", "state"};

    public static String buildSummaryCsv(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row(SUMMARY_HEADER));
        for (TestRun r : in.runs) {
            StatSummary s = r.summary();
            sb.append(Csv.row(String.valueOf(r.id), time(r.startedAt, in), r.target.host,
                    String.valueOf(r.target.port), nz(r.resolvedAddress),
                    r.dns == null ? "" : num(r.dns.lookupMs), nz(r.profileLabel), nz(r.exitLagDescription),
                    String.valueOf(r.timeoutMs), String.valueOf(r.samplesRequested), String.valueOf(s.count),
                    String.valueOf(r.failedAttempts()), String.valueOf(r.count(ProbeOutcome.TIMEOUT)),
                    String.valueOf(r.count(ProbeOutcome.NO_RESPONSE)), num(s.median), num(s.mean), num(s.min),
                    num(s.max), num(s.p95), num(s.p99), num(s.stdDev), num(s.jitter),
                    num(r.connectSummary().median), String.valueOf(r.localInterceptDetected()),
                    r.serverInfo == null ? "" : nz(r.serverInfo.versionName), String.valueOf(r.durationMs()),
                    r.state.name()));
        }
        return sb.toString();
    }

    private static String time(long epochMs, Input in) {
        if (epochMs <= 0) {
            return "";
        }
        return Instant.ofEpochMilli(epochMs).atZone(in.now.getZone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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
