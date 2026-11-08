package com.seekehr.netdiagnostics.core.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures TCP connection establishment time with a non-blocking NIO {@link SocketChannel}.
 *
 * <p>The timer starts immediately before {@code connect()} is issued and stops when
 * {@code finishConnect()} reports the connection established, i.e. it covers SYN → SYN/ACK plus
 * local kernel/JVM scheduling overhead. No application data is sent; the socket is closed
 * immediately after the handshake. The connection originates from the current JVM process.</p>
 */
public final class TcpProbe {
    private TcpProbe() {
    }

    public static final class Result {
        public final ProbeOutcome outcome;
        /** Handshake time in ms; NaN unless {@link #outcome} is SUCCESS. */
        public final double rttMs;
        /** Time until failure was detected, ms (for diagnostics only; never an RTT). */
        public final double elapsedMs;
        public final String localAddress;
        public final int localPort;
        public final String remoteAddress;
        public final int remotePort;
        public final String detail;

        Result(ProbeOutcome outcome, double rttMs, double elapsedMs, String localAddress, int localPort,
               String remoteAddress, int remotePort, String detail) {
            this.outcome = outcome;
            this.rttMs = rttMs;
            this.elapsedMs = elapsedMs;
            this.localAddress = localAddress;
            this.localPort = localPort;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.detail = detail;
        }

        public boolean success() {
            return outcome == ProbeOutcome.SUCCESS;
        }
    }

    /**
     * Perform one connection attempt. Blocks the calling (worker) thread for at most roughly
     * {@code timeoutMs}. {@code cancel} is polled every 50 ms.
     */
    public static Result probe(InetSocketAddress remote, int timeoutMs, AtomicBoolean cancel) {
        if (remote.isUnresolved()) {
            return new Result(ProbeOutcome.DNS_FAILURE, Double.NaN, 0, null, 0, remote.getHostString(),
                    remote.getPort(), "address is unresolved");
        }
        String rAddr = remote.getAddress().getHostAddress();
        int rPort = remote.getPort();
        SocketChannel ch = null;
        Selector selector = null;
        long start = 0;
        try {
            ch = SocketChannel.open();
            ch.configureBlocking(false);
            ch.socket().setTcpNoDelay(true);
            selector = Selector.open();
            start = System.nanoTime();
            boolean connected = ch.connect(remote);
            long end;
            if (connected) {
                end = System.nanoTime();
            } else {
                ch.register(selector, SelectionKey.OP_CONNECT);
                long deadline = start + timeoutMs * 1_000_000L;
                end = 0;
                while (true) {
                    if (cancel != null && cancel.get()) {
                        return fail(ProbeOutcome.CANCELLED, start, ch, rAddr, rPort, "cancelled");
                    }
                    long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainingMs <= 0) {
                        return fail(ProbeOutcome.TIMEOUT, start, ch, rAddr, rPort,
                                "no handshake response within " + timeoutMs + " ms");
                    }
                    int ready = selector.select(Math.min(50L, remainingMs));
                    if (ready > 0) {
                        selector.selectedKeys().clear();
                        if (ch.finishConnect()) {
                            end = System.nanoTime();
                            break;
                        }
                    }
                }
            }
            double rtt = (end - start) / 1e6;
            InetSocketAddress local = (InetSocketAddress) ch.getLocalAddress();
            return new Result(ProbeOutcome.SUCCESS, rtt, rtt,
                    local == null ? null : local.getAddress().getHostAddress(),
                    local == null ? 0 : local.getPort(), rAddr, rPort, "connected");
        } catch (ConnectException e) {
            return fail(classifyConnect(e), start, ch, rAddr, rPort, msg(e));
        } catch (NoRouteToHostException e) {
            return fail(ProbeOutcome.UNREACHABLE, start, ch, rAddr, rPort, msg(e));
        } catch (PortUnreachableException e) {
            return fail(ProbeOutcome.REFUSED, start, ch, rAddr, rPort, msg(e));
        } catch (UnresolvedAddressException e) {
            return fail(ProbeOutcome.DNS_FAILURE, start, ch, rAddr, rPort, "unresolved address");
        } catch (ClosedByInterruptException e) {
            return fail(ProbeOutcome.CANCELLED, start, ch, rAddr, rPort, "interrupted");
        } catch (SocketException e) {
            String m = msg(e).toLowerCase(java.util.Locale.ROOT);
            ProbeOutcome o = m.contains("unreachable") || m.contains("no route") ? ProbeOutcome.UNREACHABLE
                    : m.contains("refused") || m.contains("reset") ? ProbeOutcome.REFUSED
                    : m.contains("timed out") ? ProbeOutcome.TIMEOUT : ProbeOutcome.LOCAL_ERROR;
            return fail(o, start, ch, rAddr, rPort, msg(e));
        } catch (IOException e) {
            return fail(ProbeOutcome.LOCAL_ERROR, start, ch, rAddr, rPort, msg(e));
        } catch (RuntimeException e) {
            return fail(ProbeOutcome.UNKNOWN, start, ch, rAddr, rPort, msg(e));
        } finally {
            closeQuietly(ch);
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    static ProbeOutcome classifyConnect(ConnectException e) {
        String m = msg(e).toLowerCase(java.util.Locale.ROOT);
        if (m.contains("timed out")) {
            return ProbeOutcome.TIMEOUT;
        }
        if (m.contains("unreachable") || m.contains("no route")) {
            return ProbeOutcome.UNREACHABLE;
        }
        return ProbeOutcome.REFUSED; // "Connection refused" (RST received)
    }

    private static Result fail(ProbeOutcome o, long start, SocketChannel ch, String rAddr, int rPort, String detail) {
        double elapsed = start == 0 ? 0 : (System.nanoTime() - start) / 1e6;
        String la = null;
        int lp = 0;
        if (ch != null) {
            try {
                InetSocketAddress local = (InetSocketAddress) ch.getLocalAddress();
                if (local != null) {
                    la = local.getAddress().getHostAddress();
                    lp = local.getPort();
                }
            } catch (Exception ignored) {
                // closed
            }
        }
        return new Result(o, Double.NaN, elapsed, la, lp, rAddr, rPort, detail);
    }

    private static String msg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void closeQuietly(SocketChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
