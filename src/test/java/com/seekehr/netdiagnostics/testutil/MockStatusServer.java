package com.seekehr.netdiagnostics.testutil;

import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.mc.VarInt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local Minecraft status-protocol server for integration tests. Behaviour is selectable to exercise
 * success and failure paths without contacting any public server.
 */
public final class MockStatusServer implements AutoCloseable {
    public enum Mode {
        /** Correct status + pong. */
        NORMAL,
        /** Sends the status response then closes (no pong), like some proxies. */
        CLOSE_AFTER_STATUS,
        /** Accepts the TCP connection but never answers. */
        SILENT,
        /** Replies with non-Minecraft bytes. */
        GARBAGE,
        /** Announces an enormous frame length. */
        OVERSIZED,
        /** Pong payload differs from the ping payload. */
        WRONG_PONG
    }

    public static final String STATUS_JSON = "{\"version\":{\"name\":\"MockServer 1.8.9\",\"protocol\":47},"
            + "\"players\":{\"max\":20,\"online\":3},\"description\":{\"text\":\"Mock MOTD\"}}";

    private final ServerSocket server;
    private final Mode mode;
    private final Thread thread;
    public final AtomicInteger connections = new AtomicInteger();
    public volatile String lastHandshakeHost;
    public volatile int lastHandshakeProtocol = -1;
    public volatile int lastNextState = -1;
    private volatile boolean closed;

    public MockStatusServer(Mode mode) throws IOException {
        this.mode = mode;
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mock-status-server");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void loop() {
        while (!closed) {
            try {
                final Socket s = server.accept();
                connections.incrementAndGet();
                Thread h = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(s);
                    }
                }, "mock-status-conn");
                h.setDaemon(true);
                h.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            McProtocol.Frame hs;
            try {
                hs = McProtocol.readFrame(in, 1024);
            } catch (IOException e) {
                return; // plain TCP probe: client closed without sending anything
            }
            parseHandshake(hs.payload);
            if (mode == Mode.SILENT) {
                Thread.sleep(8_000);
                return;
            }
            McProtocol.readFrame(in, 16); // status request
            if (mode == Mode.GARBAGE) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("US-ASCII"));
                out.flush();
                return;
            }
            if (mode == Mode.OVERSIZED) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                VarInt.write(o, 2_000_000);
                out.write(o.toByteArray());
                out.flush();
                Thread.sleep(500);
                return;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            VarInt.write(body, 0x00);
            McProtocol.writeString(body, STATUS_JSON);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            VarInt.write(frame, body.size());
            frame.write(body.toByteArray());
            out.write(frame.toByteArray());
            out.flush();
            if (mode == Mode.CLOSE_AFTER_STATUS) {
                return;
            }
            McProtocol.Frame ping = McProtocol.readFrame(in, 16);
            long payload = McProtocol.decodeLong(ping.payload);
            out.write(McProtocol.ping(mode == Mode.WRONG_PONG ? payload + 1 : payload));
            out.flush();
            Thread.sleep(100);
        } catch (Exception ignored) {
            // test server: ignore
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private void parseHandshake(byte[] p) throws IOException {
        DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(p));
        lastHandshakeProtocol = VarInt.read(d, 5);
        int len = VarInt.read(d, 5);
        byte[] host = new byte[len];
        d.readFully(host);
        lastHandshakeHost = new String(host, "UTF-8");
        d.readUnsignedShort();
        lastNextState = VarInt.read(d, 5);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }

    /** Returns a local port with nothing listening on it (connections are refused). */
    public static int closedPort() throws IOException {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int p = s.getLocalPort();
        s.close();
        return p;
    }
}
