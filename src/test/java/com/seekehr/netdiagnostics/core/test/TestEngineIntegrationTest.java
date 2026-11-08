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
import static org.junit.Assert.assertFalse;
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
    }

    @After
    public void tearDown() {
        engine.shutdown();
    }

    private TestRun runAndWait(final TestRun run, long timeoutMs) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        engine.addListener(new TestEngine.Listener() {
            @Override
            public void onUpdate(TestRun r) {
                if (r == run && r.finished() && r.finishedAt > 0) {
                    done.countDown();
                }
            }
        });
        engine.submit(run, settings);
        assertTrue("run did not finish in time", done.await(timeoutMs, TimeUnit.MILLISECONDS));
        return run;
    }

    private static TestRun run(int port, int samples, int warmup, int timeout) {
        return new TestRun(new Target("127.0.0.1", port, true), samples, warmup, 500, timeout, null, null);
    }

    @Test
    public void pingsMockServerRepeatedly() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            TestRun r = runAndWait(run(s.port(), 3, 1, 3000), 15_000);
            assertEquals(TestRun.State.DONE, r.state);
            assertEquals(3, r.summary().count);
            assertEquals(0, r.failedAttempts());
            assertEquals(4, s.connections.get()); // one connection per sample, warm-up included
            List<TestRun.Attempt> atts = r.attempts();
            assertTrue(atts.get(0).warmup);
            for (TestRun.Attempt a : atts) {
                assertFalse(Double.isNaN(a.pingMs));
                assertFalse(Double.isNaN(a.connectMs));
                assertFalse(Double.isNaN(a.statusMs));
            }
            for (int i = 1; i < atts.size(); i++) {
                long gap = atts.get(i).timestamp - atts.get(i - 1).timestamp;
                assertTrue("gap " + gap, gap >= 480); // rate limit between connections
            }
            assertNotNull(r.serverInfo);
            assertEquals("MockServer 1.8.9", r.serverInfo.versionName);
            assertFalse("loopback must not count as interception", r.localInterceptDetected());
            String head = Conclusions.describe(r).get(0);
            assertTrue(head, head.startsWith("Your Minecraft ping to 127.0.0.1:" + s.port() + " averaged"));
        } finally {
            s.close();
        }
    }

    @Test
    public void refusedPortNeverProducesPing() throws Exception {
        int port = MockStatusServer.closedPort();
        TestRun r = runAndWait(run(port, 2, 0, 5000), 30_000);
        assertTrue(r.summary().isEmpty());
        assertEquals(2, r.count(ProbeOutcome.REFUSED));
        String head = Conclusions.describe(r).get(0);
        assertTrue(head, head.contains("A valid ping cannot be measured"));
        assertFalse(head, head.contains(" ms"));
    }

    @Test
    public void statusWithoutPongIsReportedSeparately() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.CLOSE_AFTER_STATUS);
        try {
            TestRun r = runAndWait(run(s.port(), 2, 0, 3000), 15_000);
            assertTrue(r.summary().isEmpty());
            assertEquals(2, r.count(ProbeOutcome.NO_PONG));
            assertEquals(2, r.statusSummary().count);
            assertTrue(Conclusions.describe(r).get(0).contains("never answered the ping"));
        } finally {
            s.close();
        }
    }

    @Test
    public void nonMinecraftServiceIsProtocolError() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.GARBAGE);
        try {
            TestRun r = runAndWait(run(s.port(), 1, 0, 3000), 15_000);
            assertEquals(1, r.count(ProbeOutcome.PROTOCOL_ERROR));
            assertTrue(r.summary().isEmpty());
        } finally {
            s.close();
        }
    }

    @Test
    public void silentServerIsNoResponse() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.SILENT);
        try {
            TestRun r = runAndWait(run(s.port(), 1, 0, 1000), 15_000);
            assertEquals(1, r.count(ProbeOutcome.NO_RESPONSE));
        } finally {
            s.close();
        }
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
        TestRun r = runAndWait(new TestRun(new Target(host, 25565, true), 3, 0, 500, 1000, null, null), 30_000);
        assertEquals(TestRun.State.FAILED, r.state);
        assertEquals(1, r.count(ProbeOutcome.DNS_FAILURE));
        assertTrue(Conclusions.describe(r).get(0).startsWith("DNS resolution failed"));
    }

    @Test
    public void cancellationStopsRun() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            final TestRun r = run(s.port(), 50, 0, 2000);
            final CountDownLatch done = new CountDownLatch(1);
            engine.addListener(new TestEngine.Listener() {
                @Override
                public void onUpdate(TestRun x) {
                    if (x == r && x.measuredAttempts() >= 2) {
                        x.cancelFlag.set(true);
                    }
                    if (x == r && x.finished() && x.finishedAt > 0) {
                        done.countDown();
                    }
                }
            });
            engine.submit(r, settings);
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertEquals(TestRun.State.CANCELLED, r.state);
            assertTrue(r.measuredAttempts() < 50);
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
                    engine.submit(run(s.port(), 20, 0, 1000), settings);
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
