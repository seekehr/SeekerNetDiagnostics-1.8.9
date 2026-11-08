package com.seekehr.netdiagnostics.core.mc;

import com.seekehr.netdiagnostics.core.net.ProbeOutcome;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs a single Minecraft server-list ping (status request + ping/pong). It never proceeds to
 * the login state, so it never joins a server. Blocking; call from a worker thread only.
 *
 * <p>Timing boundaries (all {@link System#nanoTime()}):</p>
 * <ul>
 *   <li>tcpConnectMs: {@code Socket.connect()} call → return (TCP handshake).</li>
 *   <li>statusResponseMs: status request written → full response frame read. Application-level:
 *       includes server/proxy processing and JSON generation, so it is NOT network RTT.</li>
 *   <li>pingPongMs: ping packet written → matching pong read. Application-level round trip through
 *       the server's (or proxy's) network stack and protocol handler.</li>
 * </ul>
 */
public final class McStatusClient {
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024;

    private McStatusClient() {
    }

    public enum Stage {
        TCP_CONNECT, STATUS_REQUEST, PING, DONE
    }

    public static final class Result {
        public String connectHost;
        public int connectPort;
        public String handshakeHost;
        public String remoteAddress;
        /** Local address the probe socket was bound to (shows which network adapter carried it). */
        public java.net.InetAddress localAddress;
        public ProbeOutcome tcpOutcome = ProbeOutcome.UNKNOWN;
        public double tcpConnectMs = Double.NaN;
        public boolean statusOk;
        public double statusResponseMs = Double.NaN;
        public boolean pingOk;
        public double pingPongMs = Double.NaN;
        public Stage failedStage;
        public String error;
        public McProtocol.StatusInfo status;
        public int responseBytes;
        public String protocolNote;
        /** True when the status or ping step failed because the server did not answer in time. */
        public boolean timedOut;

        /** One outcome for the whole sample; SUCCESS only when the ping/pong completed. */
        public ProbeOutcome outcome() {
            if (tcpOutcome != ProbeOutcome.SUCCESS) {
                return tcpOutcome;
            }
            if (pingOk) {
                return ProbeOutcome.SUCCESS;
            }
            if ("cancelled".equals(error)) {
                return ProbeOutcome.CANCELLED;
            }
            if (!statusOk) {
                return timedOut ? ProbeOutcome.NO_RESPONSE : ProbeOutcome.PROTOCOL_ERROR;
            }
            return ProbeOutcome.NO_PONG;
        }
    }

    public static Result ping(InetSocketAddress address, String handshakeHost, int timeoutMs,
                              int maxResponseBytes, AtomicBoolean cancel) {
        Result r = new Result();
        r.connectHost = address.getHostString();
        r.connectPort = address.getPort();
        r.handshakeHost = handshakeHost;
        r.remoteAddress = address.isUnresolved() ? null : address.getAddress().getHostAddress();
        if (address.isUnresolved()) {
            r.tcpOutcome = ProbeOutcome.DNS_FAILURE;
            r.failedStage = Stage.TCP_CONNECT;
            r.error = "address could not be resolved";
            return r;
        }
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            long t0 = System.nanoTime();
            try {
                socket.connect(address, timeoutMs);
            } catch (SocketTimeoutException e) {
                r.tcpOutcome = ProbeOutcome.TIMEOUT;
                r.failedStage = Stage.TCP_CONNECT;
                r.error = "TCP connection timed out after " + timeoutMs + " ms";
                return r;
            } catch (ConnectException e) {
                String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                r.tcpOutcome = m.contains("timed out") ? ProbeOutcome.TIMEOUT : ProbeOutcome.REFUSED;
                r.failedStage = Stage.TCP_CONNECT;
                r.error = "TCP connection failed: " + e.getMessage();
                return r;
            } catch (NoRouteToHostException e) {
                r.tcpOutcome = ProbeOutcome.UNREACHABLE;
                r.failedStage = Stage.TCP_CONNECT;
                r.error = "No route to host: " + e.getMessage();
                return r;
            }
            r.tcpConnectMs = (System.nanoTime() - t0) / 1e6;
            r.localAddress = socket.getLocalAddress();
            r.tcpOutcome = ProbeOutcome.SUCCESS;
            if (cancel != null && cancel.get()) {
                r.failedStage = Stage.STATUS_REQUEST;
                r.error = "cancelled";
                return r;
            }

            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            OutputStream out = socket.getOutputStream();
            InputStream in = new DeadlineInputStream(new BufferedInputStream(socket.getInputStream()), socket,
                    deadline, cancel);

            // --- status request
            byte[] hs = McProtocol.handshake(McProtocol.PROTOCOL_1_8, handshakeHost, address.getPort(),
                    McProtocol.STATE_STATUS);
            out.write(hs);
            long s0 = System.nanoTime();
            out.write(McProtocol.statusRequest());
            out.flush();
            McProtocol.Frame f;
            try {
                f = McProtocol.readFrame(in, maxResponseBytes);
            } catch (IOException e) {
                r.failedStage = Stage.STATUS_REQUEST;
                r.timedOut = e instanceof SocketTimeoutException;
                r.error = cancelled(cancel) ? "cancelled" : describeIo("status response", e, timeoutMs);
                return r;
            }
            r.statusResponseMs = (System.nanoTime() - s0) / 1e6;
            r.responseBytes = f.payload.length;
            if (f.packetId != 0x00) {
                r.failedStage = Stage.STATUS_REQUEST;
                r.error = "unexpected packet id 0x" + Integer.toHexString(f.packetId)
                        + " (server may not speak the Minecraft status protocol)";
                r.statusResponseMs = Double.NaN;
                return r;
            }
            try {
                String json = McProtocol.decodeStatusJson(f.payload);
                r.status = McProtocol.parseStatus(json, maxResponseBytes);
                r.statusOk = true;
            } catch (RuntimeException e) {
                r.failedStage = Stage.STATUS_REQUEST;
                r.error = "could not parse status response: " + e.getMessage();
                r.statusResponseMs = Double.NaN;
                return r;
            }
            if (r.status.protocol >= 0 && r.status.protocol != McProtocol.PROTOCOL_1_8) {
                r.protocolNote = "Server reports protocol " + r.status.protocol + " (" + r.status.versionName
                        + "); 1.8.9 uses 47. Status still works, but joining may require a different version.";
            }

            // --- ping / pong
            long payload = System.nanoTime() ^ 0x5EEC4E7L;
            long p0 = System.nanoTime();
            out.write(McProtocol.ping(payload));
            out.flush();
            try {
                McProtocol.Frame pong = McProtocol.readFrame(in, 64);
                long p1 = System.nanoTime();
                if (pong.packetId != 0x01) {
                    r.failedStage = Stage.PING;
                    r.error = "server replied to ping with packet 0x" + Integer.toHexString(pong.packetId);
                } else if (McProtocol.decodeLong(pong.payload) != payload) {
                    r.failedStage = Stage.PING;
                    r.error = "pong payload did not match the ping payload";
                } else {
                    r.pingOk = true;
                    r.pingPongMs = (p1 - p0) / 1e6;
                    r.failedStage = Stage.DONE;
                }
            } catch (IOException e) {
                r.failedStage = Stage.PING;
                r.timedOut = e instanceof SocketTimeoutException;
                r.error = cancelled(cancel) ? "cancelled" : describeIo("ping response", e, timeoutMs)
                        + " (some proxies close the connection after the status response)";
            }
            return r;
        } catch (IOException e) {
            if (r.tcpOutcome != ProbeOutcome.SUCCESS) {
                r.tcpOutcome = ProbeOutcome.LOCAL_ERROR;
                r.failedStage = Stage.TCP_CONNECT;
            } else if (r.failedStage == null) {
                r.failedStage = r.statusOk ? Stage.PING : Stage.STATUS_REQUEST;
            }
            r.error = "I/O error: " + e.getMessage();
            return r;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private static boolean cancelled(AtomicBoolean cancel) {
        return cancel != null && cancel.get();
    }

    private static String describeIo(String what, IOException e, int timeoutMs) {
        if (e instanceof SocketTimeoutException) {
            return "no " + what + " within " + timeoutMs + " ms";
        }
        if (e instanceof java.io.EOFException) {
            return "server closed the connection before sending a complete " + what;
        }
        return what + " failed: " + e.getMessage();
    }

    /** Applies an absolute deadline (and cancellation) to every blocking read. */
    static final class DeadlineInputStream extends InputStream {
        private final InputStream in;
        private final Socket socket;
        private final long deadlineNanos;
        private final AtomicBoolean cancel;

        DeadlineInputStream(InputStream in, Socket socket, long deadlineNanos, AtomicBoolean cancel) {
            this.in = in;
            this.socket = socket;
            this.deadlineNanos = deadlineNanos;
            this.cancel = cancel;
        }

        private void arm() throws IOException {
            if (cancel != null && cancel.get()) {
                throw new IOException("cancelled");
            }
            long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                throw new SocketTimeoutException("deadline exceeded");
            }
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining));
        }

        @Override
        public int read() throws IOException {
            arm();
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            arm();
            return in.read(b, off, len);
        }
    }
}
