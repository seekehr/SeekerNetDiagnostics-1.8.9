package com.seekehr.netdiagnostics.core.monitor;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TcpTraceAssessTest {
    private static final List<String> NONE = Collections.emptyList();
    private static final double[] RTT = {20.5, 21.0, Double.NaN};

    private static Traceroute.TcpResult assess(Traceroute.Probes p, boolean ttl1, String by, String adapter,
                                               boolean running, int hops, List<String> eps) {
        return Traceroute.assess(p, ttl1, by, adapter, running ? "ExitLag" : "", false, hops, RTT, eps, 25565);
    }

    @Test
    public void directRouteIsNotExitLagEvenWhenExitLagRuns() {
        Traceroute.TcpResult r = assess(Traceroute.Probes.REACHED, false, null, "Intel Wi-Fi", true, 7, NONE);
        assertEquals(Traceroute.Verdict.DIRECT, r.verdict);
        assertEquals(7, r.hopDistance);
        assertTrue(r.detail.contains("ExitLag is running but did not take this connection"));
        assertEquals(20.5, r.best(), 1e-9);
    }

    @Test
    public void exitLagProcessAcceptingProbeIsVerified() {
        List<String> eps = Arrays.asList("1.2.3.4:22184");
        Traceroute.TcpResult r = assess(Traceroute.Probes.REACHED, true, "ExitLag.exe (pid 42)", "Ethernet", true,
                -1, eps);
        assertEquals(Traceroute.Verdict.PROXY_VERIFIED, r.verdict);
        assertEquals("ExitLag", r.proxyName);
        assertTrue(r.detail.contains("ExitLag.exe (pid 42)"));
        assertTrue(r.detail.contains("1.2.3.4:22184"));
        assertEquals(-1, r.hopDistance);
    }

    @Test
    public void unidentifiedInterceptionIsNotNamed() {
        Traceroute.TcpResult r = assess(Traceroute.Probes.REACHED, true, null, "Ethernet", true, -1, NONE);
        assertEquals(Traceroute.Verdict.LOCAL_PROXY, r.verdict);
        assertTrue(r.detail.contains("could not be verified"));
        assertNull(r.proxyName);
    }

    @Test
    public void anyIdentifiedProcessIsVerifiedByName() {
        Traceroute.TcpResult r = assess(Traceroute.Probes.REACHED, false, "NoPing.exe (pid 9)", "Ethernet", false, 5,
                NONE);
        assertEquals(Traceroute.Verdict.PROXY_VERIFIED, r.verdict);
        assertEquals("NoPing", r.proxyName);
        assertEquals(9, r.acceptedPid);
        r = assess(Traceroute.Probes.REACHED, true, "MyTunnel.exe (pid 77)", "Ethernet", false, -1, NONE);
        assertEquals("MyTunnel", r.proxyName);
        assertEquals(77, r.acceptedPid);
    }

    @Test
    public void vpnAdapterIsVerified() {
        Traceroute.TcpResult r = assess(Traceroute.Probes.REACHED, false, null, "Wintun Userspace Tunnel", false, 12,
                NONE);
        assertEquals(Traceroute.Verdict.PROXY_VERIFIED, r.verdict);
        assertEquals(12, r.hopDistance);
        assertEquals(Traceroute.Verdict.DIRECT,
                assess(Traceroute.Probes.REACHED, false, null, "Realtek Gaming GbE", false, 7, NONE).verdict);
    }

    @Test
    public void exitLagAdapterIsVerified() {
        Traceroute.TcpResult r = assess(Traceroute.Probes.REACHED, false, null, "ExitLag Tap Adapter", true, 9, NONE);
        assertEquals(Traceroute.Verdict.PROXY_VERIFIED, r.verdict);
        assertEquals(9, r.hopDistance);
    }

    @Test
    public void noReplyOrUnavailableIsUnverified() {
        assertEquals(Traceroute.Verdict.UNVERIFIED,
                assess(Traceroute.Probes.NO_REPLY, false, null, null, true, -1, NONE).verdict);
        Traceroute.TcpResult r = assess(Traceroute.Probes.UNAVAILABLE, false, null, null, false, -1, NONE);
        assertEquals(Traceroute.Verdict.UNVERIFIED, r.verdict);
        assertTrue(r.detail.contains("could not be verified"));
    }

    @Test
    public void parsesIcmpErrorInfo() {
        byte[] b = new byte[36];
        b[0] = 2; // AF_INET
        b[4] = 10;
        b[5] = 0;
        b[6] = 0;
        b[7] = 1;
        b[28] = 1; // IPPROTO_ICMP
        b[32] = 11; // time exceeded
        WinTcpTrace.IcmpError e = WinTcpTrace.parseIcmpError(b);
        assertEquals("10.0.0.1", e.from);
        assertTrue(e.timeExceeded());
        b[32] = 3;
        assertFalse(WinTcpTrace.parseIcmpError(b).timeExceeded());
        b[0] = 0;
        assertNull(WinTcpTrace.parseIcmpError(b).from);
    }

    @Test
    public void buildsSockaddrs() throws Exception {
        byte[] v4 = WinTcpTrace.sockaddr(InetAddress.getByName("1.2.3.4"), 25565);
        assertEquals(16, v4.length);
        assertEquals(2, v4[0]);
        assertEquals(0x63, v4[2] & 0xFF); // 25565 = 0x63DD, network order
        assertEquals(0xDD, v4[3] & 0xFF);
        assertEquals("1.2.3.4", WinTcpTrace.sockaddrHost(v4, 0));
        byte[] v6 = WinTcpTrace.sockaddr(InetAddress.getByName("2001:db8::1"), 443);
        assertEquals(28, v6.length);
        assertEquals(23, v6[0]);
        assertEquals("2001:db8:0:0:0:0:0:1", WinTcpTrace.sockaddrHost(v6, 0));
    }
}
