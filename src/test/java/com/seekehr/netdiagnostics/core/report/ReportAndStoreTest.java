package com.seekehr.netdiagnostics.core.report;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.json.Json;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.test.ResultStore;
import com.seekehr.netdiagnostics.core.test.TestRun;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReportAndStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A run to a public address with the given ping times and connect time, plus one no-response sample. */
    static TestRun sampleRun(String host, double connectMs, double... pings) {
        TestRun r = new TestRun(new Target(host, 25565, true), pings.length + 1, 0, 1000, 5000, "Profile A",
                "ExitLag Manual, Singapore");
        r.startedAt = 1_700_000_000_000L;
        r.resolvedAddress = "203.0.113.5";
        DnsInfo d = new DnsInfo();
        d.query = host;
        d.addresses.add("203.0.113.5");
        d.lookupMs = 1.5;
        r.dns = d;
        int i = 1;
        for (double v : pings) {
            r.addAttempt(new TestRun.Attempt(i, false, r.startedAt + i * 1000, ProbeOutcome.SUCCESS, v, connectMs,
                    v + 5, null));
            i++;
        }
        r.addAttempt(new TestRun.Attempt(i, false, r.startedAt + i * 1000, ProbeOutcome.NO_RESPONSE, 999, connectMs,
                Double.NaN, "no status response within 5000 ms"));
        r.state = TestRun.State.DONE;
        r.finishedAt = r.startedAt + 20_000;
        return r;
    }

    private static ReportWriter.Input input(TestRun... runs) {
        ReportWriter.Input in = new ReportWriter.Input();
        in.now = ZonedDateTime.of(2026, 11, 8, 21, 0, 0, 0, ZoneId.of("Asia/Karachi"));
        in.environment.put("minecraftVersion", "1.8.9");
        in.environment.put("forgeVersion", "11.15.1.2318");
        in.environment.put("javaVersion", "1.8.0_504");
        in.runs = Arrays.asList(runs);
        return in;
    }

    @Test
    public void failedSampleNeverCarriesPing() {
        TestRun r = sampleRun("h", 30, 100, 102);
        TestRun.Attempt last = r.attempts().get(2);
        assertEquals(ProbeOutcome.NO_RESPONSE, last.outcome);
        assertTrue(Double.isNaN(last.pingMs));
        assertEquals(2, r.summary().count);
        assertEquals(1, r.failedAttempts());
    }

    @Test
    public void detectsLocalInterceptionOnlyForPublicAddresses() {
        TestRun proxied = sampleRun("play.example.com", 0.4, 99, 100, 101);
        assertTrue(proxied.localInterceptDetected());
        assertTrue(Conclusions.interceptLine(proxied).contains("A local proxy (such as ExitLag)"));
        assertTrue(Conclusions.interceptLine(proxied).contains("does not show which route"));

        TestRun direct = sampleRun("play.example.com", 95, 99, 100, 101);
        assertFalse(direct.localInterceptDetected());
        assertTrue(Conclusions.interceptLine(direct).startsWith("No local interception detected"));

        TestRun lan = sampleRun("lan", 0.3, 1, 1, 1);
        lan.resolvedAddress = "192.168.1.10";
        assertFalse("a LAN server can legitimately answer in under 1 ms", lan.localInterceptDetected());
    }

    @Test
    public void headlineUsesPingNotConnectTime() {
        TestRun r = sampleRun("play.example.com", 0.4, 98, 100, 102);
        String head = Conclusions.describe(r).get(0);
        assertTrue(head, head.startsWith("Your Minecraft ping to 203.0.113.5:25565 averaged 100 ms (median 100 ms)"));
    }

    @Test
    public void jsonReportContainsRequiredFields() {
        TestRun r = sampleRun("play.example.com", 0.4, 98, 99, 101);
        Map<String, Object> root = Json.obj(Json.parse(Json.write(ReportWriter.buildJson(input(r)))));
        Map<String, Object> meta = Json.obj(root.get("meta"));
        assertEquals("2026-11-08T21:00:00+05:00", meta.get("generatedAt"));
        assertEquals("Asia/Karachi", meta.get("timezone"));
        assertEquals("1.8.9", meta.get("minecraftVersion"));
        assertEquals("11.15.1.2318", meta.get("forgeVersion"));
        Map<String, Object> run = Json.obj(Json.arr(root.get("runs")).get(0));
        assertEquals("play.example.com", run.get("host"));
        assertEquals(25565.0, run.get("port"));
        assertEquals(5000.0, run.get("timeoutMs"));
        assertEquals("Profile A", run.get("profileLabel"));
        assertEquals("ExitLag Manual, Singapore", run.get("exitLagSettingsDescription"));
        assertEquals("not verified", run.get("exitLagRouting"));
        assertEquals(Boolean.TRUE, run.get("localInterceptDetected"));
        assertEquals(99.0, Json.obj(run.get("pingSummary")).get("medianMs"));
        assertEquals(1.0, Json.obj(run.get("outcomes")).get("NO_RESPONSE"));
        List<Object> samples = Json.arr(run.get("samples"));
        assertEquals(4, samples.size());
        assertNull(Json.obj(samples.get(3)).get("pingMs"));
    }

    @Test
    public void csvReports() {
        TestRun r = sampleRun("play.example.com", 0.4, 10.5, 11.25);
        String[] lines = ReportWriter.buildSamplesCsv(input(r)).split("\r\n");
        assertEquals(1 + 3, lines.length);
        assertTrue(lines[0].startsWith("run_id,run_started,host,port"));
        assertTrue(lines[1], lines[1].contains(",SUCCESS,10.500,0.400,15.500,"));
        assertTrue(lines[3], lines[3].contains(",NO_RESPONSE,,0.400,,"));
        assertTrue(lines[1].contains("\"ExitLag Manual, Singapore\""));
        String[] sl = ReportWriter.buildSummaryCsv(input(r)).split("\r\n");
        assertEquals(2, sl.length);
        assertEquals(ReportWriter.SUMMARY_HEADER.length, sl[0].split(",").length);
        assertTrue(sl[1], sl[1].contains(",10.875,"));
        assertTrue(sl[1], sl[1].contains(",true,"));
    }

    @Test
    public void writesFilesToReportDirectory() throws Exception {
        File dir = new File(tmp.getRoot(), "config/netscope/reports");
        ReportWriter.Input in = input(sampleRun("h", 30, 20, 30));
        CompareSession cs = new CompareSession(new Target("h", 25565, true), 10);
        cs.recordRun(cs.addProfile("A", "auto"), sampleRun("h", 30, 20, 21));
        in.comparison = cs.toMap();
        List<File> files = ReportWriter.write(dir, in, true, true);
        assertEquals(3, files.size());
        for (File f : files) {
            assertTrue(f.isFile() && f.length() > 50);
        }
        assertFalse(ReportWriter.write(dir, in, true, false).get(0).equals(files.get(0)));
    }

    @Test
    public void storePersistsAndQueries() throws Exception {
        File f = new File(tmp.getRoot(), "history.json");
        ResultStore store = new ResultStore(f);
        TestRun a = sampleRun("alpha.example", 0.4, 50, 52);
        TestRun b = sampleRun("beta.example", 0.4, 20, 22, 24);
        b.startedAt = a.startedAt + 5000;
        store.add(a);
        store.add(b);
        store.save();

        ResultStore loaded = new ResultStore(f);
        assertEquals(2, loaded.load());
        TestRun lb = loaded.all().get(0);
        assertEquals("beta.example", lb.target.host);
        assertEquals(3, lb.summary().count);
        assertEquals(22, lb.summary().median, 1e-9);
        assertEquals(1, lb.count(ProbeOutcome.NO_RESPONSE));
        assertTrue(lb.localInterceptDetected());
        assertEquals("Profile A", lb.profileLabel);

        assertEquals(1, loaded.query("alpha", ResultStore.SortKey.DATE, false).size());
        assertEquals("beta.example", loaded.query("", ResultStore.SortKey.MEDIAN, true).get(0).target.host);
        assertEquals("beta.example", loaded.query("", ResultStore.SortKey.HOST, false).get(0).target.host);
        loaded.clear();
        assertTrue(loaded.all().isEmpty());
    }

    @Test
    public void storeIgnoresOldFormatAndCorruptFiles() throws Exception {
        File old = new File(tmp.getRoot(), "old.json");
        ReportWriter.writeFile(old, "{\"format\":1,\"runs\":[{\"host\":\"x\",\"attempts\":[]}]}");
        assertEquals(0, new ResultStore(old).load());
        File bad = new File(tmp.getRoot(), "bad.json");
        ReportWriter.writeFile(bad, "{not json");
        ResultStore s = new ResultStore(bad);
        assertEquals(0, s.load());
        for (int i = 0; i < ResultStore.MAX_RUNS + 10; i++) {
            s.add(sampleRun("h" + i, 30, 1));
        }
        assertEquals(ResultStore.MAX_RUNS, s.all().size());
    }
}
