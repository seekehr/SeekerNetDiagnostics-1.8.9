package com.seekehr.netdiagnostics.core.net;

import com.seekehr.netdiagnostics.testutil.MockStatusServer;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TcpProbeIntegrationTest {
    @Test
    public void successfulHandshakeIsTimed() throws Exception {
        MockStatusServer server = new MockStatusServer(MockStatusServer.Mode.NORMAL);
        try {
            TcpProbe.Result r = TcpProbe.probe(new InetSocketAddress("127.0.0.1", server.port()), 2000, null);
            assertEquals(ProbeOutcome.SUCCESS, r.outcome);
            assertTrue(r.success());
            assertTrue("rtt " + r.rttMs, r.rttMs > 0 && r.rttMs < 2000);
            assertEquals("127.0.0.1", r.remoteAddress);
            assertEquals(server.port(), r.remotePort);
            assertNotNull(r.localAddress);
            assertTrue(r.localPort > 0);
        } finally {
            server.close();
        }
    }

    @Test
    public void refusedPortIsNotAnRtt() throws Exception {
        int port = MockStatusServer.closedPort();
        TcpProbe.Result r = TcpProbe.probe(new InetSocketAddress("127.0.0.1", port), 5000, null);
        assertEquals(ProbeOutcome.REFUSED, r.outcome);
        assertFalse(r.success());
        assertTrue(Double.isNaN(r.rttMs));
    }

    @Test
    public void unroutableAddressTimesOutOrIsUnreachable() throws Exception {
        // 192.0.2.0/24 is TEST-NET-1 (RFC 5737): never routed on the public internet.
        long t0 = System.currentTimeMillis();
        TcpProbe.Result r = TcpProbe.probe(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 25565), 400, null);
        long took = System.currentTimeMillis() - t0;
        assertTrue("outcome " + r.outcome, r.outcome == ProbeOutcome.TIMEOUT || r.outcome == ProbeOutcome.UNREACHABLE
                || r.outcome == ProbeOutcome.LOCAL_ERROR);
        assertTrue(Double.isNaN(r.rttMs));
        assertTrue("took " + took, took < 2500);
        if (r.outcome == ProbeOutcome.TIMEOUT) {
            assertTrue(r.elapsedMs >= 390);
        }
    }

    @Test
    public void cancellationStopsAttempt() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(true);
        TcpProbe.Result r = TcpProbe.probe(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 25565), 5000,
                cancel);
        assertTrue("outcome " + r.outcome, r.outcome == ProbeOutcome.CANCELLED
                || r.outcome == ProbeOutcome.UNREACHABLE || r.outcome == ProbeOutcome.LOCAL_ERROR);
        assertTrue(r.elapsedMs < 1000);
    }

    @Test
    public void unresolvedAddressIsDnsFailure() {
        TcpProbe.Result r = TcpProbe.probe(InetSocketAddress.createUnresolved("does-not-exist.invalid", 25565), 500,
                null);
        assertEquals(ProbeOutcome.DNS_FAILURE, r.outcome);
        assertTrue(Double.isNaN(r.rttMs));
    }
}
