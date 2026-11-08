package com.seekehr.netdiagnostics.core.monitor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TracerouteTest {
    private static final double EPS = 1e-9;

    @Test
    public void windowsHop() {
        Traceroute.Hop h = Traceroute.parse("  3    12 ms    <1 ms    14 ms  203.27.106.53");
        assertEquals(3, h.number);
        assertEquals("203.27.106.53", h.address);
        assertEquals(3, h.rtts.length);
        assertEquals(1, h.best(), EPS);
    }

    @Test
    public void windowsTimeout() {
        Traceroute.Hop h = Traceroute.parse("  7     *        *        *     Request timed out.");
        assertEquals(7, h.number);
        assertNull(h.address);
        assertTrue(Double.isNaN(h.best()));
    }

    @Test
    public void unixHop() {
        Traceroute.Hop h = Traceroute.parse(" 2  10.0.0.1  1.234 ms  1.100 ms  *");
        assertEquals("10.0.0.1", h.address);
        assertEquals(1.1, h.best(), EPS);
    }

    @Test
    public void headerIgnored() {
        assertNull(Traceroute.parse("Tracing route to 203.27.106.53 over a maximum of 30 hops"));
        assertNull(Traceroute.parse("traceroute to 1.1.1.1 (1.1.1.1), 30 hops max, 60 byte packets"));
    }
}
