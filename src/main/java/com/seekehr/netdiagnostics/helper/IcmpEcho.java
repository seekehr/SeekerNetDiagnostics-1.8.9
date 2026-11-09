package com.seekehr.netdiagnostics.helper;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import java.net.Inet4Address;

/**
 * ICMP echo with a chosen TTL through Windows' {@code IcmpSendEcho} (the API tracert uses). Windows matches the
 * Time Exceeded replies itself, so this works through the firewall, and ExitLag does not take ICMP over. Used when
 * TCP/UDP probes to a target are intercepted on this PC.
 */
final class IcmpEcho {
    static final int TTL_EXPIRED = 11013;
    static final int SUCCESS = 0;

    interface IcmpApi extends StdCallLibrary {
        Pointer IcmpCreateFile();

        boolean IcmpCloseHandle(Pointer h);

        int IcmpSendEcho(Pointer h, int dest, byte[] data, short size, Pointer options, Pointer reply, int replySize,
                         int timeoutMs);
    }

    private static IcmpApi api;

    /** One echo outcome: replying address (null = no reply), status, and measured RTT in ms. */
    static final class Echo {
        String from;
        int status = -1;
        double rttMs = Double.NaN;

        boolean expired() {
            return from != null && status == TTL_EXPIRED;
        }

        boolean reached() {
            return from != null && status == SUCCESS;
        }
    }

    private IcmpEcho() {
    }

    private static synchronized IcmpApi api() {
        if (api == null) {
            api = (IcmpApi) Native.loadLibrary("iphlpapi", IcmpApi.class);
        }
        return api;
    }

    /** Sends one echo with {@code ttl} and waits up to {@code timeoutMs}. Thread-safe (own handle per call). */
    static Echo send(Inet4Address dest, int ttl, int timeoutMs) {
        Echo e = new Echo();
        IcmpApi a = api();
        Pointer h = a.IcmpCreateFile();
        if (h == null || Pointer.nativeValue(h) == -1L) {
            return e;
        }
        try {
            Memory opts = new Memory(Pointer.SIZE * 2); // IP_OPTION_INFORMATION
            opts.clear();
            opts.setByte(0, (byte) ttl);
            Memory reply = new Memory(512);
            reply.clear();
            byte[] data = new byte[32];
            long t0 = System.nanoTime();
            a.IcmpSendEcho(h, addr(dest), data, (short) data.length, opts, reply, (int) reply.size(), timeoutMs);
            double rtt = (System.nanoTime() - t0) / 1e6;
            int address = reply.getInt(0);
            int status = reply.getInt(4);
            if (address != 0 && (status == SUCCESS || status == TTL_EXPIRED || (status >= 11002 && status <= 11005))) {
                e.from = (address & 0xFF) + "." + (address >>> 8 & 0xFF) + "." + (address >>> 16 & 0xFF) + "."
                        + (address >>> 24 & 0xFF);
                e.status = status;
                e.rttMs = rtt;
            }
        } finally {
            a.IcmpCloseHandle(h);
        }
        return e;
    }

    /** IPAddr: the address bytes in network order, read as a little-endian int. */
    static int addr(Inet4Address a) {
        byte[] b = a.getAddress();
        return (b[0] & 0xFF) | (b[1] & 0xFF) << 8 | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
    }
}
