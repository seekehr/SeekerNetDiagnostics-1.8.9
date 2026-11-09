package com.seekehr.netdiagnostics.core.dns;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProxyDetectorTest {
    @Test
    public void knownProvidersByAsName() {
        assertEquals("Cloudflare", ProxyDetector.match("CLOUDFLARENET - Cloudflare, Inc., US"));
        assertEquals("NeoProtect", ProxyDetector.match("NEOPROTECT-AS, DE"));
        assertEquals("Path.net", ProxyDetector.match("PATH-NETWORK, US"));
        assertEquals("DDoS-Guard", ProxyDetector.match("DDOS-GUARD CORP., BZ"));
    }

    @Test
    public void knownProvidersByHostname() {
        assertEquals("TCPShield", ProxyDetector.match("abc123.tcpshield.com"));
        assertEquals("Cloudflare", ProxyDetector.match("play.example.com.cdn.cloudflare.net"));
    }

    @Test
    public void genericShieldProxies() {
        assertEquals("DonutShield", ProxyDetector.match("na.donutshield.net"));
        assertEquals("DonutShield", ProxyDetector.match("edge-1.DonutShield.gg"));
    }

    @Test
    public void ordinaryNetworksAreNotProxies() {
        assertNull(ProxyDetector.match("GOOGLE - Google LLC, US"));
        assertNull(ProxyDetector.match("OVH, FR"));
        assertNull(ProxyDetector.match("mc.hypixel.net"));
        assertNull(ProxyDetector.match("HETZNER-AS, DE"));
        assertNull(ProxyDetector.match(null));
    }

    @Test
    public void stripsTrailingDot() {
        assertEquals("a.tcpshield.com", ProxyDetector.stripDot("a.tcpshield.com."));
    }
}
