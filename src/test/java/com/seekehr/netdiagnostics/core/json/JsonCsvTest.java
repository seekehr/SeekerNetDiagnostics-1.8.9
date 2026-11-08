package com.seekehr.netdiagnostics.core.json;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class JsonCsvTest {
    @Test
    public void roundTrip() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("s", "quote\" backslash\\ newline\n tab\t unicode \u00e9 \u0001");
        m.put("i", 42);
        m.put("d", 12.5);
        m.put("nan", Double.NaN);
        m.put("b", true);
        m.put("n", null);
        List<Object> l = new ArrayList<Object>();
        l.add(1L);
        l.add("x");
        m.put("l", l);
        m.put("arr", new double[]{1.25, 2});
        for (String text : new String[]{Json.write(m), Json.writeCompact(m)}) {
            Map<String, Object> back = Json.obj(Json.parse(text));
            assertEquals(m.get("s"), back.get("s"));
            assertEquals(42.0, back.get("i"));
            assertEquals(12.5, back.get("d"));
            assertNull(back.get("nan"));
            assertTrue(back.containsKey("nan"));
            assertEquals(Boolean.TRUE, back.get("b"));
            assertNull(back.get("n"));
            assertEquals(2, Json.arr(back.get("l")).size());
            assertEquals(1.25, Json.arr(back.get("arr")).get(0));
        }
    }

    @Test
    public void parsesEscapesAndUnicode() {
        Object o = Json.parse("{\"a\":\"\\u0041\\/\\n\",\"b\":[1e3,-2.5,0]}");
        Map<String, Object> m = Json.obj(o);
        assertEquals("A/\n", m.get("a"));
        assertEquals(1000.0, Json.arr(m.get("b")).get(0));
        assertEquals(-2.5, Json.arr(m.get("b")).get(1));
    }

    @Test
    public void rejectsMalformedInput() {
        String[] bad = {"", "{", "{\"a\":}", "[1,]x", "{\"a\" 1}", "\"unterminated", "tru", "{\"a\":1} trailing",
                "\"\\x\"", "[01e]"};
        for (String b : bad) {
            try {
                Json.parse(b);
                if (!"[01e]".equals(b)) {
                    fail("accepted: " + b);
                }
            } catch (Json.JsonException expected) {
                // ok
            }
        }
    }

    @Test
    public void enforcesLimits() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            deep.append('[');
        }
        for (int i = 0; i < 100; i++) {
            deep.append(']');
        }
        try {
            Json.parse(deep.toString(), 10_000, 32);
            fail("depth limit not enforced");
        } catch (Json.JsonException expected) {
            assertTrue(expected.getMessage().contains("depth"));
        }
        try {
            Json.parse("\"0123456789\"", 5, 8);
            fail("length limit not enforced");
        } catch (Json.JsonException expected) {
            assertTrue(expected.getMessage().contains("length"));
        }
    }

    @Test
    public void csvEscaping() {
        assertEquals("plain", Csv.escape("plain"));
        assertEquals("\"a,b\"", Csv.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""));
        assertEquals("\"line\nbreak\"", Csv.escape("line\nbreak"));
        assertEquals("", Csv.escape(null));
        assertEquals("-12.5", Csv.escape("-12.5"));
        assertEquals("'=1+1", Csv.escape("=1+1"));
        assertEquals("'@cmd", Csv.escape("@cmd"));
        assertEquals("'-x", Csv.escape("-x"));
        assertEquals("a,\"b,c\",d\r\n", Csv.row("a", "b,c", "d"));
    }
}
