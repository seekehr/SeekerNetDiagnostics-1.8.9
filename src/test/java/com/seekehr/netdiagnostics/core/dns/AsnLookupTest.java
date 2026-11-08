package com.seekehr.netdiagnostics.core.dns;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AsnLookupTest {
    @Test
    public void originQueryIpv4() {
        assertEquals("8.8.8.8.origin.asn.cymru.com", AsnLookup.originQuery("8.8.8.8"));
        assertEquals("4.3.2.1.origin.asn.cymru.com", AsnLookup.originQuery("1.2.3.4"));
    }

    @Test
    public void originQueryIpv6() {
        assertEquals("8.8.8.8.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.6.8.4.0.6.8.4.1.0.0.2.origin6.asn.cymru.com",
                AsnLookup.originQuery("2001:4860:4860::8888"));
    }

    @Test
    public void originQueryRejectsHostnames() {
        assertNull(AsnLookup.originQuery("play.example.com"));
        assertNull(AsnLookup.originQuery(null));
    }

    @Test
    public void fieldsStripQuotesAndTrim() {
        assertArrayEquals(new String[]{"15169", "8.8.8.0/24", "US", "arin", "2014-03-14"},
                AsnLookup.fields("\"15169 | 8.8.8.0/24 | US | arin | 2014-03-14\""));
        assertArrayEquals(new String[]{"15169", "8.8.8.0/24", "US"}, AsnLookup.fields("15169 | 8.8.8.0/24 | US"));
    }

    @Test
    public void labels() {
        assertEquals("AS15169 GOOGLE", AsnLookup.label("AS15169", "GOOGLE - Google LLC, US"));
        assertEquals("AS4134 CHINANET-BACKBONE No.31,Jin-rong Street",
                AsnLookup.label("AS4134", "CHINANET-BACKBONE No.31,Jin-rong Street, CN"));
        assertEquals("AS1", AsnLookup.label("AS1", null));
    }
}
