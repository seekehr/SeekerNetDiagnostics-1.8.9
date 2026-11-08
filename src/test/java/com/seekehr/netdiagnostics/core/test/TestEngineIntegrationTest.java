package com.seekehr.netdiagnostics.core.test;

import com.seekehr.netdiagnostics.core.config.Settings;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.testutil.MockStatusServer;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestEngineIntegrationTest {
    private TestEngine engine;
    private Settings settings;

    @Before
    public void setUp() {
        engine = new TestEngine();
        settings = new Settings();
        settings.intervalMs = 500;
        settings.warmupSamples = 1;
    }

    @After
    public void tearDown() {
        engine.shutdown();
    }

    private TestRun runAndWait(TestRun run, long timeoutMs) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final TestRun target = run;
        engine.addListener(new TestEngine.Listener() {
            @Override
            public void onUpdate(TestRun r) {
                if (r == target && r.finished() && r.finishedAt > 0) {
                    done.countDown();
                }
            }
        });
        engine.submit(run, settings);
        assertTrue("run did not finish in time", done.await(timeoutMs, TimeUnit.MILLISECONDS));
        return run;
    }

    @Test
    public void fullTestAgainstMockServer() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            TestRun run = new TestRun(TestRun.Kind.FULL, new Target("127.0.0.1", s.port(), true), 3, 1, 500, 2000,
                    3000, null, null);
            runAndWait(run, 15_000);
            assertEquals(TestRun.State.DONE, run.state);
            assertEquals(3, run.summary().count);
            assertEquals(3, run.measuredAttempts());
            assertEquals(0, run.failedAttempts());
            List<TestRun.Attempt> atts = run.attempts();
            assertEquals(4, atts.size()); // 1 warm-up + 3
            assertTrue(atts.get(0).warmup);
            // rate limiting: consecutive attempts to the same destination >= 500 ms apart
            for (int i = 1; i < atts.size(); i++) {
                long gap = atts.get(i).timestamp - atts.get(i - 1).timestamp;
                assertTrue("gap " + gap, gap >= 480);
            }
            assertNotNull(run.mcStatus);
            assertTrue(run.mcStatus.statusOk);
            assertTrue(run.mcStatus.pingOk);
            assertEquals("127.0.0.1", run.resolvedAddress);
            String text = Conclusions.describe(run).get(0);
            assertTrue(text, text.startsWith("Your connection to the selected TCP endpoint (127.0.0.1:" + s.port()));
            assertTrue(Conclusions.clipboardSummary(run).contains(Conclusions.EXITLAG_NOT_VERIFIED));
        } finally {
            s.close();
        }
    }

    @Test
    public void refusedPortNeverProducesLatency() throws Exception {
        settings.warmupSamples = 0;
        int port = MockStatusServer.closedPort();
        TestRun run = new TestRun(TestRun.Kind.TCP, new Target("127.0.0.1", port, true), 2, 0, 500, 5000, 3000,
                null, null);
        runAndWait(run, 30_000);
        assertEquals(TestRun.State.DONE, run.state);
        assertTrue(run.summary().isEmpty());
        assertEquals(2, run.count(ProbeOutcome.REFUSED));
        String text = Conclusions.describe(run).get(0);
        assertTrue(text, text.contains("A valid TCP handshake latency cannot be measured"));
        assertTrue(text, !text.contains(" ms"));
    }

    @Test
    public void dnsFailureIsReported() throws Exception {
        String host = "no-such-host-seekernetdiag.invalid";
        try {
            InetAddress.getAllByName(host);
            Assume.assumeTrue("resolver hijacks NXDOMAIN; skipping", false);
        } catch (java.net.UnknownHostException expected) {
            // good: resolver behaves
        }
        TestRun run = new TestRun(TestRun.Kind.TCP, new Target(host, 25565, true), 3, 0, 500, 1000, 1000, null, null);
        runAndWait(run, 30_000);
        assertEquals(TestRun.State.FAILED, run.state);
        assertEquals(1, run.count(ProbeOutcome.DNS_FAILURE));
        assertTrue(run.summary().isEmpty());
        assertTrue(Conclusions.describe(run).get(0).startsWith("DNS resolution failed"));
    }

    @Test
    public void cancellationStopsRun() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            final TestRun run = new TestRun(TestRun.Kind.TCP, new Target("127.0.0.1", s.port(), true), 50, 0, 500,
                    2000, 2000, null, null);
            final CountDownLatch done = new CountDownLatch(1);
            engine.addListener(new TestEngine.Listener() {
                @Override
                public void onUpdate(TestRun r) {
                    if (r == run && r.measuredAttempts() >= 2) {
                        r.cancelFlag.set(true);
                    }
                    if (r == run && r.finished() && r.finishedAt > 0) {
                        done.countDown();
                    }
                }
            });
            engine.submit(run, settings);
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertEquals(TestRun.State.CANCELLED, run.state);
            assertTrue(run.measuredAttempts() < 50);
        } finally {
            s.close();
        }
    }

    @Test
    public void queueIsBounded() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            int accepted = 0;
            try {
                for (int i = 0; i < TestEngine.MAX_QUEUED_JOBS + 3; i++) {
                    engine.submit(new TestRun(TestRun.Kind.TCP, new Target("127.0.0.1", s.port(), true), 20, 0, 500,
                            1000, 1000, null, null), settings);
                    accepted++;
                }
                fail("queue accepted unlimited jobs");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("queued"));
            }
            assertTrue(accepted <= TestEngine.MAX_QUEUED_JOBS + 1);
            engine.cancelAll();
        } finally {
            s.close();
        }
    }
}
