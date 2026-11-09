package com.seekehr.netdiagnostics.helper;

import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Entry point of the elevated helper process ({@code javaw -cp <mod jar;jna> ...HelperMain <port> <token>}),
 * started through UAC by the mod. Minecraft itself is never elevated.
 *
 * <p>Pass {@code --no-firewall} as a third argument to skip the temporary ICMP firewall rule ({@link Firewall}).
 *
 * <p>The helper connects to the mod on 127.0.0.1:{@code port}, proves itself with {@code token}, and then sends a
 * {@link Snapshot} every 2 s. It accepts only three commands (SERVER ip port, RETRACE, QUIT), validates them, and
 * exits as soon as the connection closes, so it never outlives the game.
 */
public final class HelperMain {
    public static final String VERSION = "1";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HelperMain() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        String token = args[1];
        boolean firewall = !(args.length >= 3 && "--no-firewall".equals(args[2]));
        final Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 10000);
            sock.setTcpNoDelay(true);
        } catch (Exception e) {
            System.exit(3);
        }
        run(sock, token, firewall);
        System.exit(0);
    }

    private static void run(final Socket sock, String token, boolean firewall) {
        final Writer out;
        try {
            out = new OutputStreamWriter(sock.getOutputStream(), UTF8);
            write(out, Wire.line("HELLO", token, VERSION));
        } catch (Exception e) {
            return;
        }
        String status;
        boolean captureOk = false;
        Capture cap = null;
        if (!WinTcpTrace.available()) {
            status = "Winsock could not be loaded.";
        } else {
            String fw = firewall ? Firewall.allowIcmpErrors(javaExecutable()) : "disabled by --no-firewall";
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    Firewall.remove();
                }
            }));
            try {
                cap = Capture.start();
                captureOk = true;
                status = "Capturing on " + cap.interfaces() + (fw == null ? ""
                        : "; could not add the ICMP firewall rule (" + fw + "), so routers may stay hidden");
            } catch (RawSock.SockException e) {
                status = "Packet capture unavailable: " + e.getMessage();
            }
        }
        final RelayMonitor mon = captureOk ? new RelayMonitor(cap) : null;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), UTF8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        String[] f = Wire.split(line);
                        if ("QUIT".equals(f[0])) {
                            break;
                        } else if ("SERVER".equals(f[0]) && f.length >= 3 && mon != null) {
                            try {
                                mon.setServer(f[1], Integer.parseInt(f[2]));
                            } catch (NumberFormatException ignored) {
                                // invalid command
                            }
                        } else if ("RETRACE".equals(f[0]) && mon != null) {
                            mon.retraceAll();
                        }
                    }
                } catch (Exception ignored) {
                    // connection gone
                }
                closeQuietly(sock);
            }
        }, "SND-helper-reader");
        reader.setDaemon(true);
        reader.start();
        int ticks = 0;
        try {
            while (!sock.isClosed()) {
                Snapshot s;
                if (mon != null) {
                    mon.tick();
                    if (++ticks % 30 == 0) {
                        WinTcpTrace.clearProcessNames();
                    }
                    s = mon.snapshot(true, status + (mon.tracing() == null ? "" : "; tracing "
                            + mon.tracing().replaceFirst("^[a-z]+:", "")));
                } else {
                    s = new Snapshot();
                    s.captureOk = false;
                    s.status = status;
                }
                StringBuilder sb = new StringBuilder();
                for (String l : s.encode()) {
                    sb.append(l).append('\n');
                }
                write(out, sb.toString().trim());
                Thread.sleep(2000);
            }
        } catch (Exception ignored) {
            // mod closed the connection
        } finally {
            if (mon != null) {
                mon.stop();
            }
            if (cap != null) {
                cap.close();
            }
            Firewall.remove();
            closeQuietly(sock);
        }
    }

    static String javaExecutable() {
        File f = new File(System.getProperty("java.home"), "bin" + File.separator + "javaw.exe");
        return f.getAbsolutePath();
    }

    private static void write(Writer out, String text) throws java.io.IOException {
        out.write(text);
        out.write('\n');
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
            // closing
        }
    }
}
