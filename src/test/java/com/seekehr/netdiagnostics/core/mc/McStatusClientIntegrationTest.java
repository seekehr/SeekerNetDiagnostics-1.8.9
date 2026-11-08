package com.seekehr.netdiagnostics.core.mc;

import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.testutil.MockStatusServer;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class McStatusClientIntegrationTest {
    private static McStatusClient.Result ping(MockStatusServer s, int timeout) {
        return McStatusClient.ping(new InetSocketAddress("127.0.0.1", s.port()), "mock.local", timeout,
                McStatusClient.DEFAULT_MAX_RESPONSE_BYTES, null);
    }

    @Test
    public void fullStatusAndPing() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            McStatusClient.Result r = ping(s, 3000);
            assertEquals(ProbeOutcome.SUCCESS, r.tcpOutcome);
            assertTrue(r.tcpConnectMs >= 0);
            assertTrue(r.statusOk);
            assertTrue(r.statusResponseMs >= 0);
            assertTrue(r.pingOk);
            assertTrue(r.pingPongMs >= 0);
            assertEquals(McStatusClient.Stage.DONE, r.failedStage);
            assertEquals(ProbeOutcome.SUCCESS, r.outcome());
            assertEquals("MockServer 1.8.9", r.status.versionName);
            assertEquals(47, r.status.protocol);
            assertEquals(3, r.status.onlinePlayers);
            assertEquals("Mock MOTD", r.status.motd);
            assertNull(r.protocolNote);
            assertEquals("mock.local", s.lastHandshakeHost);
            assertEquals(47, s.lastHandshakeProtocol);
            assertEquals(1, s.lastNextState); // status, never login
        } finally {
            s.close();
        }
    }

    @Test
    public void proxyClosingAfterStatusKeepsStatusResult() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.CLOSE_AFTER_STATUS);
        try {
            McStatusClient.Result r = ping(s, 3000);
            assertTrue(r.statusOk);
            assertFalse(r.pingOk);
            assertTrue(Double.isNaN(r.pingPongMs));
            assertEquals(McStatusClient.Stage.PING, r.failedStage);
            assertNotNull(r.error);
            assertEquals(ProbeOutcome.NO_PONG, r.outcome());
        } finally {
            s.close();
        }
    }

    @Test
    public void tcpSucceedsButStatusFailsOnGarbage() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.GARBAGE);
        try {
            McStatusClient.Result r = ping(s, 3000);
            assertEquals(ProbeOutcome.SUCCESS, r.tcpOutcome);
            assertFalse(r.statusOk);
            assertTrue(Double.isNaN(r.statusResponseMs));
            assertEquals(McStatusClient.Stage.STATUS_REQUEST, r.failedStage);
            assertEquals(ProbeOutcome.PROTOCOL_ERROR, r.outcome());
        } finally {
            s.close();
        }
    }

    @Test
    public void oversizedResponseRejected() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.OVERSIZED);
        try {
            McStatusClient.Result r = ping(s, 3000);
            assertFalse(r.statusOk);
            assertTrue(r.error, r.error.contains("limit"));
        } finally {
            s.close();
        }
    }

    @Test
    public void silentServerTimesOut() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.SILENT);
        try {
            long t0 = System.currentTimeMillis();
            McStatusClient.Result r = ping(s, 1000);
            long took = System.currentTimeMillis() - t0;
            assertEquals(ProbeOutcome.SUCCESS, r.tcpOutcome);
            assertFalse(r.statusOk);
            assertTrue(r.error, r.error.contains("within 1000 ms"));
            assertEquals(ProbeOutcome.NO_RESPONSE, r.outcome());
            assertTrue("took " + took, took < 3000);
        } finally {
            s.close();
        }
    }

    @Test
    public void mismatchedPongIsRejected() throws Exception {
        MockStatusServer s = new MockStatusServer(MockStatusServer.Mode.WRONG_PONG);
        try {
            McStatusClient.Result r = ping(s, 3000);
            assertTrue(r.statusOk);
            assertFalse(r.pingOk);
            assertTrue(r.error.contains("did not match"));
        } finally {
            s.close();
        }
    }

    @Test
    public void refusedConnection() throws Exception {
        int port = MockStatusServer.closedPort();
        McStatusClient.Result r = McStatusClient.ping(new InetSocketAddress("127.0.0.1", port), "x", 5000, 1024,
                null);
        assertEquals(ProbeOutcome.REFUSED, r.tcpOutcome);
        assertFalse(r.statusOk);
        assertTrue(Double.isNaN(r.tcpConnectMs));
        assertEquals(McStatusClient.Stage.TCP_CONNECT, r.failedStage);
        assertEquals(ProbeOutcome.REFUSED, r.outcome());
    }
}
