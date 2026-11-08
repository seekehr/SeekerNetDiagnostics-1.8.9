package com.seekehr.netdiagnostics.core.report;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.json.Json;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.quality.Alert;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReportAndStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static TestRun sampleRun(String host, double... rtts) {
        TestRun r = new TestRun(TestRun.Kind.TCP, new Target(host, 25565, true), rtts.length + 1, 0, 1000, 3000, 5000,
                "Profile A", "ExitLag Manual, Singapore");
        r.startedAt = 1_700_000_000_000L;
        r.resolvedAddress = "203.0.113.5";
        DnsInfo d = new DnsInfo();
        d.query = host;
        d.addresses.add("203.0.113.5");
        d.systemLookupMs = 1.5;
        d.minTtlSeconds = 300;
        r.dns = d;
        int i = 1;
        for (double v : rtts) {
            r.addAttempt(new TestRun.Attempt(i++, false, r.startedAt + i * 1000, ProbeOutcome.SUCCESS, v, v,
                    "192.168.1.20", 50000 + i, "203.0.113.5", 25565, "connected"));
        }
        r.addAttempt(new TestRun.Attempt(i, false, r.startedAt + i * 1000, ProbeOutcome.TIMEOUT, 999, 3000,
                "192.168.1.20", 50100, "203.0.113.5", 25565, "no handshake response within 3000 ms"));
        r.state = TestRun.State.DONE;
        r.finishedAt = r.startedAt + 20_000;
        return r;
    }

    private static ReportWriter.Input input(boolean includeLocal, TestRun... runs) {
        ReportWriter.Input in = new ReportWriter.Input();
        in.now = ZonedDateTime.of(2026, 11, 8, 21, 0, 0, 0, ZoneId.of("Asia/Karachi"));
        in.environment.put("minecraftVersion", "1.8.9");
        in.environment.put("forgeVersion", "11.15.1.2318");
        in.environment.put("javaVersion", "1.8.0_504");
        in.runs = Arrays.asList(runs);
        in.includeLocalAddresses = includeLocal;
        in.alerts = Arrays.asList(new Alert(1L, "tcp-timeouts", "TCP handshake to x", Alert.Severity.WARNING, "m"));
        return in;
    }

    @Test
    public void failedAttemptNeverCarriesRtt() {
        TestRun r = sampleRun("h", 10, 20);
        TestRun.Attempt last = r.attempts().get(2);
        assertEquals(ProbeOutcome.TIMEOUT, last.outcome);
        assertTrue(Double.isNaN(last.rttMs));
        assertEquals(2, r.summary().count);
        assertEquals(1, r.failedAttempts());
    }

    @Test
    public void jsonReportContainsRequiredFields() {
        TestRun r = sampleRun("play.example.com", 98, 99, 101);
        Map<String, Object> root = Json.obj(Json.parse(Json.write(ReportWriter.buildJson(input(false, r)))));
        Map<String, Object> meta = Json.obj(root.get("meta"));
        assertEquals("2026-11-08T21:00:00+05:00", meta.get("generatedAt"));
        assertEquals("Asia/Karachi", meta.get("timezone"));
        assertEquals("1.8.9", meta.get("minecraftVersion"));
        assertEquals("11.15.1.2318", meta.get("forgeVersion"));
        assertEquals("1.8.0_504", meta.get("javaVersion"));
        Map<String, Object> run = Json.obj(Json.arr(root.get("runs")).get(0));
        assertEquals("play.example.com", run.get("host"));
        assertEquals(25565.0, run.get("port"));
        assertEquals("TCP handshake", run.get("testMethod"));
        assertEquals(3000.0, run.get("tcpTimeoutMs"));
        assertEquals("Profile A", run.get("profileLabel"));
        assertEquals("ExitLag Manual, Singapore", run.get("exitLagSettingsDescription"));
        assertEquals("not verified", run.get("exitLagRouting"));
        Map<String, Object> dns = Json.obj(run.get("dns"));
        assertEquals(300.0, dns.get("minTtlSeconds"));
        Map<String, Object> summary = Json.obj(run.get("summary"));
        assertEquals(99.0, summary.get("medianMs"));
        assertEquals(3.0, summary.get("successfulSamples"));
        assertEquals(1.0, Json.obj(run.get("failureCategories")).get("TIMEOUT"));
        List<Object> atts = Json.arr(run.get("attempts"));
        assertEquals(4, atts.size());
        Map<String, Object> failed = Json.obj(atts.get(3));
        assertNull(failed.get("rttMs"));
        assertFalse("local address must be excluded by default", Json.obj(atts.get(0)).containsKey("localAddress"));
        assertEquals(1, Json.arr(root.get("alerts")).size());
        String text = Json.write(ReportWriter.buildJson(input(false, r)));
        assertFalse(text.contains("192.168.1.20"));
    }

    @Test
    public void localAddressesOnlyWhenEnabled() {
        TestRun r = sampleRun("h", 50);
        String text = Json.write(ReportWriter.buildJson(input(true, r)));
        assertTrue(text.contains("192.168.1.20"));
        assertTrue(ReportWriter.buildSamplesCsv(input(true, r)).contains("192.168.1.20"));
        assertFalse(ReportWriter.buildSamplesCsv(input(false, r)).contains("192.168.1.20"));
    }

    @Test
    public void csvReports() {
        TestRun r = sampleRun("play.example.com", 10.5, 11.25);
        String samples = ReportWriter.buildSamplesCsv(input(false, r));
        String[] lines = samples.split("\r\n");
        assertEquals(1 + 3, lines.length);
        assertTrue(lines[0].startsWith("run_id,run_started,test_method,host,port"));
        assertTrue(lines[1], lines[1].contains(",SUCCESS,10.500,"));
        assertTrue(lines[3], lines[3].contains(",TIMEOUT,,3000.000,"));
        assertTrue(lines[1].contains("\"ExitLag Manual, Singapore\""));
        String summary = ReportWriter.buildSummaryCsv(input(false, r));
        String[] sl = summary.split("\r\n");
        assertEquals(2, sl.length);
        assertEquals(ReportWriter.SUMMARY_HEADER.length, sl[0].split(",").length);
        assertTrue(sl[1].contains(",10.875,"));
    }

    @Test
    public void writesFilesToReportDirectory() throws Exception {
        File dir = new File(tmp.getRoot(), "config/netscope/reports");
        ReportWriter.Input in = input(false, sampleRun("h", 20, 30));
        CompareSession cs = new CompareSession(new Target("h", 25565, true), 10);
        cs.recordRun(cs.addProfile("A", "auto"), sampleRun("h", 20, 21));
        in.comparison = cs.toMap();
        List<File> files = ReportWriter.write(dir, in, true, true);
        assertEquals(3, files.size());
        for (File f : files) {
            assertTrue(f.isFile() && f.length() > 50);
        }
        List<File> again = ReportWriter.write(dir, in, true, false);
        assertFalse(again.get(0).equals(files.get(0)));
    }

    @Test
    public void storePersistsAndQueries() throws Exception {
        File f = new File(tmp.getRoot(), "history.json");
        ResultStore store = new ResultStore(f);
        TestRun a = sampleRun("alpha.example", 50, 52);
        TestRun b = sampleRun("beta.example", 20, 22, 24);
        b.startedAt = a.startedAt + 5000;
        store.add(a);
        store.add(b);
        store.save();

        ResultStore loaded = new ResultStore(f);
        assertEquals(2, loaded.load());
        List<TestRun> all = loaded.all();
        assertEquals("beta.example", all.get(0).target.host);
        TestRun lb = all.get(0);
        assertEquals(3, lb.summary().count);
        assertEquals(22, lb.summary().median, 1e-9);
        assertEquals(1, lb.count(ProbeOutcome.TIMEOUT));
        assertEquals("Profile A", lb.profileLabel);
        assertEquals(300, lb.dns.minTtlSeconds);

        assertEquals(1, loaded.query("alpha", ResultStore.SortKey.DATE, false).size());
        List<TestRun> byMedian = loaded.query("", ResultStore.SortKey.MEDIAN, true);
        assertEquals("beta.example", byMedian.get(0).target.host);
        List<TestRun> byHostDesc = loaded.query("", ResultStore.SortKey.HOST, false);
        assertEquals("beta.example", byHostDesc.get(0).target.host);
        loaded.clear();
        assertTrue(loaded.all().isEmpty());
    }

    @Test
    public void storeIsBoundedAndToleratesCorruptFiles() throws Exception {
        File f = new File(tmp.getRoot(), "bad.json");
        ReportWriter.writeFile(f, "{not json");
        ResultStore s = new ResultStore(f);
        assertEquals(0, s.load());
        for (int i = 0; i < ResultStore.MAX_RUNS + 10; i++) {
            s.add(sampleRun("h" + i, 1));
        }
        assertEquals(ResultStore.MAX_RUNS, s.all().size());
        assertEquals("h" + (ResultStore.MAX_RUNS + 9), s.latest().target.host);
    }

    @Test
    public void previousForFindsEarlierRunToSameTarget() {
        ResultStore s = new ResultStore(new File(tmp.getRoot(), "x.json"));
        TestRun first = sampleRun("same", 90, 91);
        TestRun other = sampleRun("other", 10);
        TestRun second = sampleRun("same", 120, 121);
        s.add(first);
        s.add(other);
        s.add(second);
        assertEquals(first, s.previousFor(second));
        assertNotNull(s.previousFor(second));
    }
}
