package com.seekehr.netdiagnostics.core.mc;

import com.seekehr.netdiagnostics.core.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * Encoding/decoding of the Minecraft server-list-ping ("status") protocol as spoken by 1.7–1.8.9
 * (uncompressed framing, protocol version 47 for 1.8.x).
 *
 * <pre>
 * frame     = VarInt length, VarInt packetId, payload
 * handshake = 0x00: VarInt protocol, String host (max 255), UShort port, VarInt nextState (1 = status)
 * request   = 0x00 (empty)
 * response  = 0x00: String json (max 32767 chars)
 * ping/pong = 0x01: Long payload (echoed back by the server)
 * </pre>
 */
public final class McProtocol {
    public static final int PROTOCOL_1_8 = 47;
    public static final int STATE_STATUS = 1;
    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int MAX_STATUS_JSON_CHARS = 32767;

    private McProtocol() {
    }

    public static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(UTF8);
        VarInt.write(out, b.length);
        out.write(b, 0, b.length);
    }

    private static byte[] frame(int packetId, byte[] payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(payload.length + 5);
        VarInt.write(body, packetId);
        body.write(payload, 0, payload.length);
        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes.length + 5);
        VarInt.write(out, bodyBytes.length);
        out.write(bodyBytes, 0, bodyBytes.length);
        return out.toByteArray();
    }

    public static byte[] handshake(int protocol, String host, int port, int nextState) {
        if (host.length() > 255) {
            throw new IllegalArgumentException("host too long for handshake");
        }
        ByteArrayOutputStream p = new ByteArrayOutputStream(32 + host.length());
        VarInt.write(p, protocol);
        writeString(p, host);
        p.write((port >>> 8) & 0xFF);
        p.write(port & 0xFF);
        VarInt.write(p, nextState);
        return frame(0x00, p.toByteArray());
    }

    public static byte[] statusRequest() {
        return frame(0x00, new byte[0]);
    }

    public static byte[] ping(long payload) {
        byte[] p = new byte[8];
        for (int i = 7; i >= 0; i--) {
            p[i] = (byte) (payload & 0xFF);
            payload >>>= 8;
        }
        return frame(0x01, p);
    }

    /** A decoded frame: packet id and the remaining payload bytes. */
    public static final class Frame {
        public final int packetId;
        public final byte[] payload;

        Frame(int packetId, byte[] payload) {
            this.packetId = packetId;
            this.payload = payload;
        }
    }

    /** Reads one length-prefixed frame, rejecting lengths over {@code maxFrameBytes}. */
    public static Frame readFrame(InputStream in, int maxFrameBytes) throws IOException {
        int len = VarInt.read(in, 3); // vanilla frame length is at most 3 bytes (2 MiB)
        if (len <= 0) {
            throw new IOException("invalid frame length " + len);
        }
        if (len > maxFrameBytes) {
            throw new IOException("response of " + len + " bytes exceeds the " + maxFrameBytes + "-byte limit");
        }
        byte[] data = new byte[len];
        new DataInputStream(in).readFully(data);
        int[] id = VarInt.read(data, 0, len);
        byte[] payload = new byte[len - id[1]];
        System.arraycopy(data, id[1], payload, 0, payload.length);
        return new Frame(id[0], payload);
    }

    /** Decodes the JSON string from a status response (0x00) payload. */
    public static String decodeStatusJson(byte[] payload) throws IOException {
        int[] strLen = VarInt.read(payload, 0, payload.length);
        int n = strLen[0];
        if (n < 0 || strLen[1] + n > payload.length) {
            throw new EOFException("status string length " + n + " exceeds packet");
        }
        String s = new String(payload, strLen[1], n, UTF8);
        if (s.length() > MAX_STATUS_JSON_CHARS * 4) {
            throw new IOException("status JSON too long");
        }
        return s;
    }

    public static long decodeLong(byte[] payload) throws IOException {
        if (payload.length != 8) {
            throw new IOException("pong payload has " + payload.length + " bytes, expected 8");
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (payload[i] & 0xFFL);
        }
        return v;
    }

    /** Parsed subset of the status JSON. Unknown/missing fields are left null / -1. */
    public static final class StatusInfo {
        public String versionName;
        public int protocol = -1;
        public int onlinePlayers = -1;
        public int maxPlayers = -1;
        public String motd;
        public boolean hasFavicon;
        public String modType;
    }

    public static StatusInfo parseStatus(String json, int maxChars) {
        Object root = Json.parse(json, maxChars, 64);
        Map<String, Object> m = Json.obj(root);
        if (m == null) {
            throw new Json.JsonException("status response is not a JSON object");
        }
        StatusInfo s = new StatusInfo();
        Map<String, Object> version = Json.obj(m.get("version"));
        s.versionName = stripFormatting(Json.str(version, "name", null));
        s.protocol = (int) Json.num(version, "protocol", -1);
        Map<String, Object> players = Json.obj(m.get("players"));
        s.onlinePlayers = (int) Json.num(players, "online", -1);
        s.maxPlayers = (int) Json.num(players, "max", -1);
        Object desc = m.get("description");
        StringBuilder sb = new StringBuilder();
        flattenChat(desc, sb, 0);
        s.motd = stripFormatting(sb.toString()).trim();
        s.hasFavicon = m.get("favicon") instanceof String;
        Map<String, Object> modinfo = Json.obj(m.get("modinfo"));
        s.modType = Json.str(modinfo, "type", null);
        return s;
    }

    private static void flattenChat(Object o, StringBuilder sb, int depth) {
        if (o == null || depth > 16 || sb.length() > 512) {
            return;
        }
        if (o instanceof String) {
            sb.append((String) o);
            return;
        }
        Map<String, Object> m = Json.obj(o);
        if (m != null) {
            Object text = m.get("text");
            if (text instanceof String) {
                sb.append((String) text);
            }
            flattenChat(m.get("extra"), sb, depth + 1);
            return;
        }
        List<Object> l = Json.arr(o);
        if (l != null) {
            for (Object e : l) {
                flattenChat(e, sb, depth + 1);
            }
        }
    }

    /** Removes legacy section-sign formatting codes and control characters. */
    public static String stripFormatting(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '\n') {
                sb.append(' ');
            } else if (c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
