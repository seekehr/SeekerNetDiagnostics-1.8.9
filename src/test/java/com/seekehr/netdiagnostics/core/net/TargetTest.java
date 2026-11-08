package com.seekehr.netdiagnostics.core.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TargetTest {
    @Test
    public void defaultsPort() {
        Target t = Target.parse("203.27.106.53", null);
        assertEquals("203.27.106.53", t.host);
        assertEquals(25565, t.port);
        assertFalse(t.portExplicit);
    }

    @Test
    public void parsesPortForms() {
        Target a = Target.parse("play.example.com:25577", null);
        assertEquals("play.example.com", a.host);
        assertEquals(25577, a.port);
        assertTrue(a.portExplicit);
        Target b = Target.parse(" 203.27.106.53 ", "443");
        assertEquals(443, b.port);
        Target c = Target.parse("[2001:db8::1]:25566", null);
        assertEquals("2001:db8::1", c.host);
        assertEquals(25566, c.port);
        assertEquals("[2001:db8::1]:25566", c.display());
        Target d = Target.parse("2001:db8::1", "25565");
        assertEquals("2001:db8::1", d.host);
        assertTrue(d.portExplicit);
        Target e = Target.parse("example.com.", null);
        assertEquals("example.com", e.host);
    }

    @Test
    public void separatePortWins() {
        assertEquals(1234, Target.parse("host.example:99", "1234").port);
    }

    @Test
    public void rejectsBadInput() {
        String[][] bad = {{"", null}, {"host", "0"}, {"host", "65536"}, {"host", "abc"}, {"bad host", null},
                {"[::1", null}, {"exa$mple.com", null}};
        for (String[] b : bad) {
            try {
                Target.parse(b[0], b[1]);
                fail("accepted " + b[0] + " / " + b[1]);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().length() > 5);
            }
        }
    }

    @Test
    public void equalityIgnoresCase() {
        assertEquals(Target.parse("Example.COM", null), Target.parse("example.com", "25565"));
    }
}
