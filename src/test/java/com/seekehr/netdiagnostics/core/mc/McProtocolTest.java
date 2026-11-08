package com.seekehr.netdiagnostics.core.mc;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class McProtocolTest {
    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    @Test
    public void varIntKnownEncodings() throws IOException {
        int[] values = {0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1};
        byte[][] enc = {b(0x00), b(0x01), b(0x7f), b(0x80, 0x01), b(0xff, 0x01), b(0xdd, 0xc7, 0x01),
                b(0xff, 0xff, 0x7f), b(0xff, 0xff, 0xff, 0xff, 0x07), b(0xff, 0xff, 0xff, 0xff, 0x0f)};
        for (int i = 0; i < values.length; i++) {
            assertArrayEquals("encode " + values[i], enc[i], VarInt.encode(values[i]));
            assertEquals(enc[i].length, VarInt.size(values[i]));
            assertEquals(values[i], VarInt.read(new ByteArrayInputStream(enc[i]), 5));
            int[] r = VarInt.read(enc[i], 0, enc[i].length);
            assertEquals(values[i], r[0]);
            assertEquals(enc[i].length, r[1]);
        }
    }

    @Test
    public void varIntRejectsOverlongAndTruncated() {
        try {
            VarInt.read(new ByteArrayInputStream(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)), 5);
            fail("accepted 6-byte VarInt");
        } catch (IOException expected) {
            // ok
        }
        try {
            VarInt.read(new ByteArrayInputStream(b(0x80, 0x80)), 5);
            fail("accepted truncated VarInt");
        } catch (EOFException expected) {
            // ok
        } catch (IOException e) {
            fail("wrong exception " + e);
        }
        try {
            VarInt.read(new ByteArrayInputStream(b(0x80, 0x80, 0x80, 0x01)), 3);
            fail("frame length limit not enforced");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void handshakeLayout() throws IOException {
        byte[] hs = McProtocol.handshake(47, "localhost", 25565, 1);
        ByteArrayInputStream in = new ByteArrayInputStream(hs);
        int len = VarInt.read(in, 3);
        assertEquals(hs.length - 1, len);
        assertEquals(0x00, VarInt.read(in, 5)); // packet id
        assertEquals(47, VarInt.read(in, 5)); // protocol
        assertEquals(9, VarInt.read(in, 5)); // string length
        byte[] host = new byte[9];
        assertEquals(9, in.read(host));
        assertEquals("localhost", new String(host, "UTF-8"));
        assertEquals(0x63, in.read()); // 25565 = 0x63DD big-endian
        assertEquals(0xDD, in.read());
        assertEquals(1, VarInt.read(in, 5)); // next state
        assertEquals(-1, in.read());
    }

    @Test
    public void statusRequestAndPingFrames() throws IOException {
        assertArrayEquals(b(0x01, 0x00), McProtocol.statusRequest());
        byte[] ping = McProtocol.ping(0x0102030405060708L);
        assertArrayEquals(b(0x09, 0x01, 1, 2, 3, 4, 5, 6, 7, 8), ping);
        McProtocol.Frame f = McProtocol.readFrame(new ByteArrayInputStream(ping), 64);
        assertEquals(1, f.packetId);
        assertEquals(0x0102030405060708L, McProtocol.decodeLong(f.payload));
    }

    @Test
    public void readFrameEnforcesSizeLimit() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.write(out, 100_000);
        try {
            McProtocol.readFrame(new ByteArrayInputStream(out.toByteArray()), 1024);
            fail("oversized frame accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("limit"));
        }
        try {
            McProtocol.readFrame(new ByteArrayInputStream(b(0x05, 0x00, 0x01)), 1024);
            fail("truncated frame accepted");
        } catch (IOException expected) {
            // EOF
        }
    }

    @Test
    public void statusResponseDecodeAndParse() throws IOException {
        String json = "{\"version\":{\"name\":\"§aBungee 1.8.x\",\"protocol\":47},"
                + "\"players\":{\"max\":1000,\"online\":123,\"sample\":[]},"
                + "\"description\":{\"text\":\"Hello \",\"extra\":[{\"text\":\"§cWorld\"},\"!\"]},"
                + "\"favicon\":\"data:image/png;base64,AAAA\",\"modinfo\":{\"type\":\"FML\",\"modList\":[]}}";
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        McProtocol.writeString(payload, json);
        String decoded = McProtocol.decodeStatusJson(payload.toByteArray());
        assertEquals(json, decoded);
        McProtocol.StatusInfo s = McProtocol.parseStatus(decoded, 100_000);
        assertEquals("Bungee 1.8.x", s.versionName);
        assertEquals(47, s.protocol);
        assertEquals(123, s.onlinePlayers);
        assertEquals(1000, s.maxPlayers);
        assertEquals("Hello World!", s.motd);
        assertTrue(s.hasFavicon);
        assertEquals("FML", s.modType);
    }

    @Test
    public void legacyStringDescriptionAndMissingFields() {
        McProtocol.StatusInfo s = McProtocol.parseStatus("{\"description\":\"§6Gold\\nline\"}", 1000);
        assertEquals("Gold line", s.motd);
        assertEquals(-1, s.protocol);
        assertEquals(-1, s.onlinePlayers);
        assertFalse(s.hasFavicon);
    }

    @Test(expected = EOFException.class)
    public void statusStringLongerThanPacket() throws IOException {
        McProtocol.decodeStatusJson(b(0x10, 'a', 'b'));
    }

    @Test(expected = IOException.class)
    public void pongWithWrongLength() throws IOException {
        McProtocol.decodeLong(b(1, 2, 3));
    }
}
