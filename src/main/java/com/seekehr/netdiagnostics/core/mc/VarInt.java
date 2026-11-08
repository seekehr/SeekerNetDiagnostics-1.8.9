package com.seekehr.netdiagnostics.core.mc;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Minecraft protocol VarInt (LEB128-style, little-endian groups of 7 bits, max 5 bytes). */
public final class VarInt {
    public static final int MAX_BYTES = 5;

    private VarInt() {
    }

    public static int size(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            n++;
        }
        return n;
    }

    public static void write(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    public static byte[] encode(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        write(out, value);
        return out.toByteArray();
    }

    /** Reads a VarInt from a stream, limited to {@code maxBytes} bytes. */
    public static int read(InputStream in, int maxBytes) throws IOException {
        int result = 0;
        int numRead = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("stream ended inside VarInt");
            }
            result |= (b & 0x7F) << (7 * numRead);
            numRead++;
            if (numRead > maxBytes) {
                throw new IOException("VarInt too long (more than " + maxBytes + " bytes)");
            }
            if ((b & 0x80) == 0) {
                return result;
            }
        }
    }

    /** Reads a VarInt from a byte array; returns {value, bytesConsumed}. */
    public static int[] read(byte[] buf, int offset, int limit) throws IOException {
        int result = 0;
        int numRead = 0;
        while (true) {
            if (offset + numRead >= limit) {
                throw new EOFException("buffer ended inside VarInt");
            }
            int b = buf[offset + numRead] & 0xFF;
            result |= (b & 0x7F) << (7 * numRead);
            numRead++;
            if (numRead > MAX_BYTES) {
                throw new IOException("VarInt too long");
            }
            if ((b & 0x80) == 0) {
                return new int[]{result, numRead};
            }
        }
    }
}
