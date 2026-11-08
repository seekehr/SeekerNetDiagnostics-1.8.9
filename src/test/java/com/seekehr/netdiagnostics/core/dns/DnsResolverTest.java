package com.seekehr.netdiagnostics.core.dns;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DnsResolverTest {
    @Test
    public void ipLiteralDetection() {
        assertTrue(DnsResolver.isIpLiteral("203.27.106.53"));
        assertTrue(DnsResolver.isIpLiteral("::1"));
        assertTrue(DnsResolver.isIpLiteral("2001:db8::1"));
        assertFalse(DnsResolver.isIpLiteral("256.1.1.1"));
        assertFalse(DnsResolver.isIpLiteral("play.example.com"));
        assertFalse(DnsResolver.isIpLiteral(null));
    }

    @Test
    public void localOrPrivateAddresses() {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.10", "169.254.1.1",
                "100.64.0.1", "::1", "fd00::1", "fe80::1"}) {
            assertTrue(ip, DnsResolver.isLocalOrPrivate(ip));
        }
        for (String ip : new String[]{"172.65.183.141", "203.27.106.53", "8.8.8.8", "100.128.0.1", "2606:4700::1",
                "play.example.com", null}) {
            assertFalse(String.valueOf(ip), DnsResolver.isLocalOrPrivate(ip));
        }
    }

    @Test
    public void parsesJndiSrvText() {
        DnsResolver.Srv s = DnsResolver.parseSrv("5 10 25577 mc.example.com.");
        assertEquals("mc.example.com", s.target);
        assertEquals(25577, s.port);
        assertEquals(5, s.priority);
        assertEquals(10, s.weight);
        assertNull(DnsResolver.parseSrv("garbage"));
        assertNull(DnsResolver.parseSrv("1 1 0 host."));
        assertNull(DnsResolver.parseSrv("1 1 x host."));
    }

    @Test
    public void srvSelectionPrefersLowestPriorityThenHighestWeight() {
        DnsResolver.Srv best = DnsResolver.best(Arrays.asList(
                new DnsResolver.Srv("a", 1, 10, 100),
                new DnsResolver.Srv("b", 2, 5, 1),
                new DnsResolver.Srv("c", 3, 5, 50)));
        assertEquals("c", best.target);
    }

    @Test
    public void resolvesIpLiteralWithoutLookup() {
        DnsInfo info = DnsResolver.resolve("127.0.0.1", true);
        assertTrue(info.ipLiteral);
        assertEquals("127.0.0.1", info.addresses.get(0));
        assertNull(DnsResolver.lookupMinecraftSrv("127.0.0.1"));
    }
}
