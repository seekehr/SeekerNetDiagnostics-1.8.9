package com.seekehr.netdiagnostics.helper;

import com.seekehr.netdiagnostics.core.monitor.Proxies;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HelperLogicTest {

    // ------------------------------------------------------------------ packets

    private static byte[] ipHeader(int proto, String src, String dst, int payload) throws Exception {
        byte[] b = new byte[20 + payload];
        b[0] = 0x45;
        b[2] = (byte) (b.length >> 8);
        b[3] = (byte) b.length;
        b[8] = 64;
        b[9] = (byte) proto;
        System.arraycopy(InetAddress.getByName(src).getAddress(), 0, b, 12, 4);
        System.arraycopy(InetAddress.getByName(dst).getAddress(), 0, b, 16, 4);
        return b;
    }

    private static void port(byte[] b, int o, int port) {
        b[o] = (byte) (port >> 8);
        b[o + 1] = (byte) port;
    }

    @Test
    public void icmpTimeExceededMatchesTcpProbe() throws Exception {
        // outer: router 10.0.0.1 -> us; ICMP 11/0; quoted: our SYN 192.168.1.5:50000 -> 1.2.3.4:25565
        byte[] inner = ipHeader(Packets.TCP, "192.168.1.5", "1.2.3.4", 8);
        port(inner, 20, 50000);
        port(inner, 22, 25565);
        byte[] b = ipHeader(Packets.ICMP, "10.0.0.1", "192.168.1.5", 8 + inner.length);
        b[20] = 11;
        System.arraycopy(inner, 0, b, 28, inner.length);
        Packets.Ip4 p = Packets.parse(b, b.length);
        assertEquals(11, p.icmpType);
        assertTrue(p.icmpError());
        assertEquals("1.2.3.4", p.innerDst);
        assertEquals(50000, p.innerSrcPort);
        assertEquals(Packets.probeKey(Packets.TCP, "1.2.3.4", 50000), Packets.replyKey(p));
    }

    @Test
    public void directTcpAnswerHasSameKeyAsProbe() throws Exception {
        byte[] b = ipHeader(Packets.TCP, "1.2.3.4", "192.168.1.5", 20);
        port(b, 20, 25565);
        port(b, 22, 50000);
        b[33] = (byte) (Packets.TCP_SYN | Packets.TCP_ACK);
        Packets.Ip4 p = Packets.parse(b, b.length);
        assertEquals(Packets.TCP_SYN | Packets.TCP_ACK, p.tcpFlags);
        assertEquals(Packets.probeKey(Packets.TCP, "1.2.3.4", 50000), Packets.replyKey(p));
    }

    @Test
    public void rejectsNonIpv4AndShortPackets() {
        assertNull(Packets.parse(new byte[10], 10));
        byte[] v6 = new byte[40];
        v6[0] = 0x60;
        assertNull(Packets.parse(v6, 40));
    }

    @Test
    public void udpUnreachableQuotesUdpPorts() throws Exception {
        byte[] inner = ipHeader(Packets.UDP, "192.168.1.5", "5.6.7.8", 8);
        port(inner, 20, 40000);
        port(inner, 22, 30000);
        byte[] b = ipHeader(Packets.ICMP, "5.6.7.8", "192.168.1.5", 8 + inner.length);
        b[20] = 3;
        b[21] = 3;
        System.arraycopy(inner, 0, b, 28, inner.length);
        Packets.Ip4 p = Packets.parse(b, b.length);
        assertEquals(Packets.probeKey(Packets.UDP, "5.6.7.8", 40000), Packets.replyKey(p));
        assertEquals("5.6.7.8", p.src); // the target itself answered: reached
    }

    // ------------------------------------------------------------------ wire + snapshot

    @Test
    public void wireEscapesRoundTrip() {
        String[] in = {"A", "tab\there", "line\nbreak", "back\\slash", "", "cr\r"};
        assertArrayEquals(in, Wire.split(Wire.line(in)));
    }

    @Test
    public void snapshotRoundTrip() {
        Snapshot s = new Snapshot();
        s.captureOk = true;
        s.status = "Capturing on [192.168.1.5]";
        s.icmpSeen = true;
        s.proxyRunning = true;
        s.proxyNames = "ExitLag, NoPing";
        Snapshot.Relay r = new Snapshot.Relay();
        r.protocol = "TCP";
        r.ip = "1.2.3.4";
        r.port = 22184;
        r.pid = 42;
        r.process = "ExitLag.exe";
        r.packetsOutPerSec = 20.5;
        r.verified = true;
        r.inUse = true;
        r.primary = true;
        r.activeSeconds = 90;
        r.connSeconds = 75;
        r.lastSeenAgoMs = 100;
        r.pathKey = RelayMonitor.relayKey("TCP", "1.2.3.4", 22184);
        s.relays.add(r);
        Snapshot.Path p = new Snapshot.Path();
        p.key = r.pathKey;
        p.kind = "relay";
        p.protocol = "ICMP";
        p.target = "1.2.3.4";
        p.port = 22184;
        p.state = "done";
        p.reached = true;
        p.note = "Reached\twith tab";
        p.traces = 2;
        Snapshot.Hop h = new Snapshot.Hop();
        h.ttl = 1;
        h.address = "192.168.1.1";
        h.sent = 3;
        h.received = 2;
        h.avg = 1.5;
        p.hops.add(h);
        Snapshot.Hop silent = new Snapshot.Hop();
        silent.ttl = 2;
        silent.sent = 3;
        p.hops.add(silent);
        s.paths.add(p);
        Snapshot.Event e = new Snapshot.Event();
        e.time = 5;
        e.text = "ExitLag started routing through relay TCP 1.2.3.4:22184";
        s.events.add(e);

        List<String> lines = s.encode();
        assertEquals("END", lines.get(lines.size() - 1));
        Snapshot d = Snapshot.decode(lines.subList(0, lines.size() - 1));
        assertTrue(d.captureOk && d.icmpSeen && d.proxyRunning);
        assertEquals("ExitLag, NoPing", d.proxyNames);
        Snapshot.Relay dr = d.relays.get(0);
        assertEquals("1.2.3.4:22184", dr.endpoint());
        assertTrue(dr.inUse && dr.primary && dr.verified);
        assertEquals(90, dr.activeSeconds);
        assertEquals(75, dr.connSeconds);
        assertEquals(20.5, dr.packetsOutPerSec, 1e-9);
        Snapshot.Path dp = d.path(dr.pathKey);
        assertEquals("Reached\twith tab", dp.note);
        assertEquals(2, dp.hops.size());
        assertEquals(33.33, dp.hops.get(0).lossPercent(), 0.01);
        assertNull(dp.hops.get(1).address);
        assertTrue(Double.isNaN(dp.hops.get(1).avg));
        assertEquals(e.text, d.events.get(0).text);
    }

    // ------------------------------------------------------------------ relay logic

    @Test
    public void shortMeasurementIsNotInUse() {
        // a few packets over 2-3 ticks: ExitLag measuring a candidate relay
        assertFalse(RelayMonitor.inUse(new int[]{4, 2}, 4000));
        assertFalse(RelayMonitor.inUse(new int[]{3, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 20000));
    }

    @Test
    public void sustainedOrBusyTrafficIsInUse() {
        assertTrue(RelayMonitor.inUse(new int[]{2, 2, 1, 0, 2, 2, 3, 2}, 16000)); // 7 of 8 ticks
        assertTrue(RelayMonitor.inUse(new int[]{40, 45, 50}, 6000)); // game stream
        assertFalse(RelayMonitor.inUse(new int[]{2, 2, 1, 0, 2, 2, 3, 2}, 14000)); // too young
    }

    @Test
    public void inUseIsHeldThroughShortGaps() {
        long now = 100000;
        assertTrue(RelayMonitor.held(true, null, now, 0));
        assertTrue(RelayMonitor.held(false, now - 5000, now, 3000)); // multipath gap
        assertFalse(RelayMonitor.held(false, now - 5000, now, 25000)); // silent too long
        assertTrue(RelayMonitor.held(false, now - 30000, now, 1000)); // still carrying traffic
        assertFalse(RelayMonitor.held(false, null, now, 0));
    }

    @Test
    public void relaySetChangesBecomeEvents() {
        Set<String> before = new LinkedHashSet<String>(Arrays.asList("TCP 1.1.1.1:1", "TCP 2.2.2.2:2"));
        Set<String> after = new LinkedHashSet<String>(Arrays.asList("TCP 2.2.2.2:2", "UDP 3.3.3.3:3"));
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("UDP 3.3.3.3:3", "NoPing");
        List<String> ev = RelayMonitor.diffActive(before, after, labels);
        assertEquals(Arrays.asList("NoPing started routing through relay UDP 3.3.3.3:3",
                "The proxy stopped routing through relay TCP 1.1.1.1:1"), ev);
        assertTrue(RelayMonitor.diffActive(after, after, labels).isEmpty());
    }

    private static Snapshot.Hop hop(int ttl, String addr, boolean dest) {
        Snapshot.Hop h = new Snapshot.Hop();
        h.ttl = ttl;
        h.address = addr;
        h.destination = dest;
        h.sent = 3;
        h.received = addr == null ? 0 : 3;
        return h;
    }

    @Test
    public void pathChangeIgnoresLostProbesButReportsNewRouter() {
        List<Snapshot.Hop> a = Arrays.asList(hop(1, "10.0.0.1", false), hop(2, "20.0.0.1", false),
                hop(3, "9.9.9.9", true));
        List<Snapshot.Hop> lost = Arrays.asList(hop(1, "10.0.0.1", false), hop(2, null, false),
                hop(3, "9.9.9.9", true));
        assertNull(RelayMonitor.pathChange(a, lost, "9.9.9.9"));
        List<Snapshot.Hop> moved = Arrays.asList(hop(1, "10.0.0.1", false), hop(2, "30.0.0.1", false),
                hop(3, "9.9.9.9", true));
        assertEquals("Path to 9.9.9.9 changed at hop 2: 20.0.0.1 -> 30.0.0.1",
                RelayMonitor.pathChange(a, moved, "9.9.9.9"));
        List<Snapshot.Hop> longer = Arrays.asList(hop(1, "10.0.0.1", false), hop(2, "20.0.0.1", false),
                hop(3, null, false), hop(4, "9.9.9.9", true));
        assertEquals("Path to 9.9.9.9 is now 4 hops (was 3)", RelayMonitor.pathChange(a, longer, "9.9.9.9"));
        assertNull(RelayMonitor.pathChange(null, a, "9.9.9.9"));
    }

    @Test
    public void loadBalancedAlternativeIsNotAChange() {
        Snapshot.Hop b = hop(1, "10.0.0.2", false);
        b.others = "10.0.0.1";
        assertNull(RelayMonitor.pathChange(Arrays.asList(hop(1, "10.0.0.1", false)), Arrays.asList(b), "x"));
    }

    private static Snapshot.Relay relay(String ep, double pps, boolean inUse) {
        Snapshot.Relay r = new Snapshot.Relay();
        r.protocol = "UDP";
        r.ip = ep.substring(0, ep.indexOf(':'));
        r.port = Integer.parseInt(ep.substring(ep.indexOf(':') + 1));
        r.packetsOutPerSec = pps;
        r.inUse = inUse;
        r.verified = true;
        return r;
    }

    @Test
    public void primaryHasHysteresis() {
        List<Snapshot.Relay> list = new ArrayList<Snapshot.Relay>(Arrays.asList(relay("1.1.1.1:1", 20, true),
                relay("2.2.2.2:2", 25, true)));
        assertEquals("UDP 1.1.1.1:1", RelayMonitor.choosePrimary("UDP 1.1.1.1:1", "UDP 2.2.2.2:2", list));
        list.set(1, relay("2.2.2.2:2", 40, true));
        assertEquals("UDP 2.2.2.2:2", RelayMonitor.choosePrimary("UDP 1.1.1.1:1", "UDP 2.2.2.2:2", list));
        list.set(0, relay("1.1.1.1:1", 20, false));
        list.set(1, relay("2.2.2.2:2", 5, true));
        assertEquals("UDP 2.2.2.2:2", RelayMonitor.choosePrimary("UDP 1.1.1.1:1", "UDP 2.2.2.2:2", list));
        assertNull(RelayMonitor.choosePrimary("UDP 1.1.1.1:1", null, list));
    }

    @Test
    public void knownProxyNames() {
        assertEquals("ExitLag", Proxies.known("ExitLag.exe"));
        assertEquals("ExitLag", Proxies.known("ExitLagPmService.exe"));
        assertEquals("NoPing", Proxies.known("NoPing.exe (pid 4)"));
        assertEquals("WireGuard", Proxies.known("wireguard.exe"));
        assertNull(Proxies.known("javaw.exe"));
        assertNull(Proxies.known(null));
        assertEquals("MyTunnel", Proxies.label("MyTunnel.exe (pid 12)"));
        assertTrue(Proxies.tunnelAdapter("WireGuard Tunnel"));
        assertTrue(Proxies.tunnelAdapter("TAP-Windows Adapter V9"));
        assertFalse(Proxies.tunnelAdapter("Realtek Gaming GbE Family Controller"));
    }

    // ------------------------------------------------------------------ hop aggregation

    @Test
    public void hopFillPicksMostCommonAndStats() {
        Snapshot.Hop h = new Snapshot.Hop();
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put("10.0.0.1", 2);
        counts.put("10.0.0.9", 1);
        PathTracer.fill(h, counts, Arrays.asList(10.0, 20.0, 30.0));
        assertEquals("10.0.0.1", h.address);
        assertEquals("10.0.0.9", h.others);
        assertEquals(10.0, h.min, 1e-9);
        assertEquals(20.0, h.avg, 1e-9);
        assertEquals(30.0, h.max, 1e-9);
    }

    @Test
    public void trimKeepsOneSilentHop() {
        List<Snapshot.Hop> hops = new ArrayList<Snapshot.Hop>(Arrays.asList(hop(1, "a", false), hop(2, "b", false),
                hop(3, null, false), hop(4, null, false), hop(5, null, false)));
        assertEquals(1, PathTracer.trimSilentTail(hops));
        assertEquals(3, hops.size());
        assertNull(hops.get(2).address);
    }

    @Test
    public void icmpAddressIsNetworkOrder() throws Exception {
        int a = IcmpEcho.addr((Inet4Address) InetAddress.getByName("1.2.3.4"));
        assertEquals(0x04030201, a);
    }
}
