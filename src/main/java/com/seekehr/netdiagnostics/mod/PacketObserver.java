package com.seekehr.netdiagnostics.mod;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S38PacketPlayerListItem;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional, strictly passive Netty handler.
 *
 * <p>It sits immediately before Minecraft's {@code packet_handler}, i.e. after decryption,
 * decompression and decoding on the inbound side, and before encoding/compression/encryption on the
 * outbound side. It only inspects already-decoded keepalive and tab-list latency packet objects,
 * records {@link System#nanoTime()} timestamps, and forwards every message unchanged and in order
 * ({@code ctx.fireChannelRead(msg)} / {@code ctx.write(msg, promise)}). It never creates, drops,
 * delays, reorders or modifies packets and stores no packet contents other than the keepalive id
 * (for matching) and the user's own latency value. Chat, authentication and all other packets are
 * passed through without inspection.</p>
 *
 * <p>Timing boundaries: "arrival" = the moment the decoded packet reaches this handler on the Netty
 * thread; "reply" = the moment the client's keepalive response is written into this handler on its
 * way to the encoder.</p>
 */
public final class PacketObserver extends ChannelDuplexHandler {
    public static final String NAME = "seekernetdiagnostics:observer";
    private static final int MAX_QUEUED = 2048;

    /** Observation types passed to the client thread. */
    public static final int KEEPALIVE_IN = 1;
    public static final int KEEPALIVE_TURNAROUND = 2;
    public static final int LATENCY_UPDATE = 3;

    public static final class Event {
        public final int type;
        public final long wallMillis;
        public final double value;

        Event(int type, long wallMillis, double value) {
            this.type = type;
            this.wallMillis = wallMillis;
            this.value = value;
        }
    }

    private static final ConcurrentLinkedQueue<Event> QUEUE = new ConcurrentLinkedQueue<Event>();
    private static final AtomicInteger QUEUED = new AtomicInteger();
    /** Own profile id, used only in memory to pick our entry out of latency updates. */
    private static volatile UUID ownId;

    private volatile int lastKeepAliveId;
    private volatile long lastKeepAliveNanos;

    public static void setOwnId(UUID id) {
        ownId = id;
    }

    private static void offer(int type, double value) {
        if (QUEUED.incrementAndGet() > MAX_QUEUED) {
            QUEUED.decrementAndGet();
            return; // drop observation rather than grow without bound
        }
        QUEUE.add(new Event(type, System.currentTimeMillis(), value));
    }

    /** Drains queued observations (client thread). */
    public static Event poll() {
        Event e = QUEUE.poll();
        if (e != null) {
            QUEUED.decrementAndGet();
        }
        return e;
    }

    public static void clearQueue() {
        while (poll() != null) {
            // drain
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof S00PacketKeepAlive) {
                lastKeepAliveId = ((S00PacketKeepAlive) msg).func_149134_c();
                lastKeepAliveNanos = System.nanoTime();
                offer(KEEPALIVE_IN, 0);
            } else if (msg instanceof S38PacketPlayerListItem) {
                S38PacketPlayerListItem p = (S38PacketPlayerListItem) msg;
                if (p.func_179768_b() == S38PacketPlayerListItem.Action.UPDATE_LATENCY) {
                    UUID me = ownId;
                    List<S38PacketPlayerListItem.AddPlayerData> entries = p.func_179767_a();
                    if (me != null && entries != null) {
                        for (int i = 0; i < entries.size(); i++) {
                            S38PacketPlayerListItem.AddPlayerData d = entries.get(i);
                            if (d != null && d.getProfile() != null && me.equals(d.getProfile().getId())) {
                                offer(LATENCY_UPDATE, d.getPing());
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // observation must never interfere with packet processing
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (msg instanceof C00PacketKeepAlive) {
                long t = lastKeepAliveNanos;
                if (t != 0 && ((C00PacketKeepAlive) msg).getKey() == lastKeepAliveId) {
                    offer(KEEPALIVE_TURNAROUND, (System.nanoTime() - t) / 1e6);
                    lastKeepAliveNanos = 0;
                }
            }
        } catch (Throwable ignored) {
            // observation must never interfere with packet processing
        }
        ctx.write(msg, promise);
    }

    private static void runOnLoop(Channel channel, Runnable r) {
        try {
            if (channel.eventLoop().inEventLoop()) {
                r.run();
            } else {
                channel.eventLoop().execute(r);
            }
        } catch (RuntimeException ignored) {
            // event loop shut down: channel is gone, nothing to install/remove
        }
    }

    /** Installs the observer on the given channel (any thread). No-op if absent or already installed. */
    public static void install(final Channel channel) {
        if (channel == null) {
            return;
        }
        runOnLoop(channel, new Runnable() {
            @Override
            public void run() {
                ChannelPipeline p = channel.pipeline();
                if (!channel.isOpen() || p.get(NAME) != null || p.get("packet_handler") == null) {
                    return;
                }
                try {
                    p.addBefore("packet_handler", NAME, new PacketObserver());
                } catch (RuntimeException ignored) {
                    // pipeline changed concurrently; skip instrumentation for this connection
                }
            }
        });
    }

    /** Removes the observer from the given channel (any thread). */
    public static void uninstall(final Channel channel) {
        if (channel == null) {
            return;
        }
        runOnLoop(channel, new Runnable() {
            @Override
            public void run() {
                try {
                    if (channel.pipeline().get(NAME) != null) {
                        channel.pipeline().remove(NAME);
                    }
                } catch (RuntimeException ignored) {
                    // already removed
                }
            }
        });
    }
}
