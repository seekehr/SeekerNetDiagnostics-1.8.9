package com.seekehr.netdiagnostics.core.dns;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DnsMessageTest {
    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8);
        o.write(v & 0xff);
    }

    private static void u32(ByteArrayOutputStream o, long v) {
        u16(o, (int) (v >>> 16));
        u16(o, (int) (v & 0xffff));
    }

    @Test
    public void buildsStandardQuery() {
        byte[] q = DnsMessage.buildQuery(0x1234, "mc.example.com", DnsMessage.TYPE_A);
        assertEquals(0x12, q[0]);
        assertEquals(0x34, q[1]);
        assertEquals(0x01, q[2]); // RD
        assertEquals(1, q[5]); // QDCOUNT
        byte[] expectedName = {2, 'm', 'c', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0};
        assertTrue(Arrays.equals(expectedName, Arrays.copyOfRange(q, 12, 12 + expectedName.length)));
        assertEquals(0, q[12 + expectedName.length]);
        assertEquals(1, q[13 + expectedName.length]); // type A
        assertEquals(1, q[15 + expectedName.length]); // class IN
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyLabel() {
        DnsMessage.buildQuery(1, "a..b", DnsMessage.TYPE_A);
    }

    /** Response with CNAME (compressed), A, AAAA and SRV answers. */
    private static byte[] sampleResponse() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, 0xBEEF); // id
        u16(o, 0x8180); // QR, RD, RA, rcode 0
        u16(o, 1);
        u16(o, 4);
        u16(o, 0);
        u16(o, 0);
        // question at offset 12: "play.example.com" A IN
        byte[] qname = {4, 'p', 'l', 'a', 'y', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0};
        o.write(qname, 0, qname.length);
        u16(o, 1);
        u16(o, 1);
        // answer 1: play.example.com CNAME edge.example.com  (pointer to 12, rdata "edge" + ptr to example.com @17)
        u16(o, 0xC00C);
        u16(o, DnsMessage.TYPE_CNAME);
        u16(o, 1);
        u32(o, 300);
        u16(o, 7);
        o.write(4);
        o.write('e');
        o.write('d');
        o.write('g');
        o.write('e');
        u16(o, 0xC011);
        // answer 2: A 203.0.113.7 TTL 60, name = pointer to 12
        u16(o, 0xC00C);
        u16(o, DnsMessage.TYPE_A);
        u16(o, 1);
        u32(o, 60);
        u16(o, 4);
        o.write(203);
        o.write(0);
        o.write(113);
        o.write(7);
        // answer 3: AAAA 2001:db8::1 TTL 120
        u16(o, 0xC00C);
        u16(o, DnsMessage.TYPE_AAAA);
        u16(o, 1);
        u32(o, 120);
        u16(o, 16);
        byte[] v6 = new byte[16];
        v6[0] = 0x20;
        v6[1] = 0x01;
        v6[2] = 0x0d;
        v6[3] = (byte) 0xb8;
        v6[15] = 1;
        o.write(v6, 0, 16);
        // answer 4: SRV 5 10 25577 mc.example.com (target "mc" + ptr to example.com @17)
        u16(o, 0xC00C);
        u16(o, DnsMessage.TYPE_SRV);
        u16(o, 1);
        u32(o, 3600);
        u16(o, 6 + 5);
        u16(o, 5);
        u16(o, 10);
        u16(o, 25577);
        o.write(2);
        o.write('m');
        o.write('c');
        u16(o, 0xC011);
        return o.toByteArray();
    }

    @Test
    public void parsesAnswersWithCompression() {
        byte[] data = sampleResponse();
        DnsMessage.Response r = DnsMessage.parse(data, data.length);
        assertEquals(0xBEEF, r.id);
        assertEquals(0, r.rcode);
        assertFalse(r.truncated);
        assertEquals(4, r.answers.size());
        DnsMessage.Record cname = r.answers.get(0);
        assertEquals("play.example.com", cname.name);
        assertEquals("edge.example.com", cname.data);
        assertEquals(300, cname.ttl);
        assertEquals("203.0.113.7", r.ofType(DnsMessage.TYPE_A).get(0).data);
        assertEquals(60, r.ofType(DnsMessage.TYPE_A).get(0).ttl);
        assertEquals("2001:db8:0:0:0:0:0:1", r.ofType(DnsMessage.TYPE_AAAA).get(0).data);
        DnsMessage.Record srv = r.ofType(DnsMessage.TYPE_SRV).get(0);
        assertEquals(5, srv.priority);
        assertEquals(10, srv.weight);
        assertEquals(25577, srv.port);
        assertEquals("mc.example.com", srv.data);
        assertEquals(3600, srv.ttl);
    }

    @Test
    public void rejectsTruncatedMessages() {
        byte[] data = sampleResponse();
        for (int cut : new int[]{5, 20, 40, data.length - 3}) {
            try {
                DnsMessage.parse(data, cut);
                fail("accepted truncated message at " + cut);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void rejectsCompressionLoop() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, 1);
        u16(o, 0x8180);
        u16(o, 1);
        u16(o, 0);
        u16(o, 0);
        u16(o, 0);
        u16(o, 0xC00C); // question name points to itself
        u16(o, 1);
        u16(o, 1);
        byte[] d = o.toByteArray();
        try {
            DnsMessage.parse(d, d.length);
            fail("compression loop accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("loop"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsQueries() {
        byte[] q = DnsMessage.buildQuery(1, "a.b", DnsMessage.TYPE_A);
        DnsMessage.parse(q, q.length);
    }

    @Test
    public void srvSelectionPrefersLowestPriorityThenWeight() {
        byte[] data = sampleResponse();
        DnsMessage.Response r = DnsMessage.parse(data, data.length);
        assertEquals(25577, DnsResolver.lowestPriority(r.ofType(DnsMessage.TYPE_SRV)).port);
    }

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
    public void resolvesIpLiteralWithoutLookup() {
        DnsInfo info = new DnsResolver(500).resolve("127.0.0.1", true);
        assertTrue(info.ipLiteral);
        assertEquals("127.0.0.1", info.addresses.get(0));
        assertEquals(-1, info.minTtlSeconds);
    }
}
