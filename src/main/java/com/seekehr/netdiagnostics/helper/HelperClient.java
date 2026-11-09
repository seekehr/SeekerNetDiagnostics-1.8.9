package com.seekehr.netdiagnostics.helper;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod side of Advanced Tracing: starts {@link HelperMain} elevated through UAC (ShellExecute "runas"), accepts
 * its connection on 127.0.0.1 with a one-time token, and keeps the latest {@link Snapshot}. The game process
 * itself stays unelevated. No Minecraft classes, so it can be tested on its own.
 */
public final class HelperClient {
    public enum State {
        STOPPED, WAITING_FOR_UAC, STARTING, CONNECTED, FAILED
    }

    interface Shell32 extends StdCallLibrary {
        Pointer ShellExecuteW(Pointer hwnd, WString op, WString file, WString params, WString dir, int show);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SW_HIDE = 0;
    private static final int SE_ERR_ACCESSDENIED = 5;

    private volatile State state = State.STOPPED;
    private volatile String message = "";
    private volatile Snapshot snapshot;
    private volatile Socket socket;
    private volatile Writer writer;
    private volatile String serverIp;
    private volatile int serverPort;
    private volatile long connectedAt;
    private volatile int proxyPid = -1;
    private int generation;

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    /** Latest helper report, or null before the first one. */
    public Snapshot snapshot() {
        return snapshot;
    }

    public long connectedAt() {
        return connectedAt;
    }

    public boolean active() {
        State s = state;
        return s == State.WAITING_FOR_UAC || s == State.STARTING || s == State.CONNECTED;
    }

    /**
     * Launches the helper (shows the UAC prompt) unless one is already running or starting.
     *
     * @param classpath classpath holding the mod's classes and JNA
     * @param javaExe   the java executable to run elevated (normally the game's own javaw.exe)
     */
    public void start(String classpath, String javaExe) {
        start(classpath, javaExe, "");
    }

    /** As {@link #start(String, String)}, with extra helper arguments (e.g. "--no-firewall"). */
    public synchronized void start(final String classpath, final String javaExe, final String extraArgs) {
        if (active()) {
            return;
        }
        final int gen = ++generation;
        state = State.WAITING_FOR_UAC;
        message = "Waiting for Windows administrator approval (UAC)...";
        snapshot = null;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                launchAndServe(gen, classpath, javaExe, extraArgs);
            }
        }, "SND-helper-client");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        generation++;
        send("QUIT");
        close();
        state = State.STOPPED;
        message = "Advanced tracing is off.";
        snapshot = null;
    }

    /** Tells the helper which game server to trace hop by hop (kept and re-sent after connecting). */
    public void setServer(String ip, int port) {
        serverIp = ip;
        serverPort = port;
        send("SERVER", ip, String.valueOf(port));
    }

    /** Tells the helper that process {@code pid} took the game connection over (kept and re-sent). */
    public void setProxyPid(int pid) {
        proxyPid = pid;
        send("PROXY", String.valueOf(pid));
    }

    public void retrace() {
        send("RETRACE");
    }

    private void send(String... fields) {
        Writer w = writer;
        if (w == null) {
            return;
        }
        try {
            synchronized (w) {
                w.write(Wire.line(fields));
                w.write('\n');
                w.flush();
            }
        } catch (Exception e) {
            close();
        }
    }

    private void close() {
        Socket s = socket;
        socket = null;
        writer = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // closing
            }
        }
    }

    private void fail(int gen, String msg) {
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            state = State.FAILED;
            message = msg;
        }
    }

    private void launchAndServe(int gen, String classpath, String javaExe, String extraArgs) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            String token = token();
            String params = "-Xmx96m -cp \"" + classpath + "\" " + HelperMain.class.getName() + " " + ss.getLocalPort()
                    + " " + token + (extraArgs.isEmpty() ? "" : " " + extraArgs);
            int rc;
            try {
                Shell32 sh = (Shell32) Native.loadLibrary("shell32", Shell32.class);
                Pointer r = sh.ShellExecuteW(null, new WString("runas"), new WString(javaExe), new WString(params),
                        new WString(new File(javaExe).getParent()), SW_HIDE);
                rc = (int) Pointer.nativeValue(r);
            } catch (Throwable t) {
                fail(gen, "Could not request administrator rights: " + t);
                return;
            }
            if (rc <= 32) {
                fail(gen, rc == SE_ERR_ACCESSDENIED ? "Administrator permission was declined (UAC)."
                        : "Could not start the helper (ShellExecute error " + rc + ").");
                return;
            }
            if (gen != generation) {
                return;
            }
            state = State.STARTING;
            message = "Starting the elevated helper...";
            ss.setSoTimeout(30000);
            long deadline = System.currentTimeMillis() + 30000;
            Socket s = null;
            BufferedReader in = null;
            while (System.currentTimeMillis() < deadline) {
                Socket c = ss.accept();
                if (!c.getInetAddress().isLoopbackAddress()) {
                    c.close();
                    continue;
                }
                c.setSoTimeout(5000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), UTF8));
                String hello;
                try {
                    hello = r.readLine();
                } catch (SocketTimeoutException e) {
                    hello = null;
                }
                String[] f = hello == null ? new String[0] : Wire.split(hello);
                if (f.length >= 2 && "HELLO".equals(f[0]) && token.equals(f[1])) {
                    s = c;
                    in = r;
                    break;
                }
                c.close();
            }
            if (s == null) {
                fail(gen, "The helper did not connect.");
                return;
            }
            s.setSoTimeout(0);
            synchronized (this) {
                if (gen != generation) {
                    s.close();
                    return;
                }
                socket = s;
                writer = new OutputStreamWriter(s.getOutputStream(), UTF8);
                state = State.CONNECTED;
                connectedAt = System.currentTimeMillis();
                message = "Helper connected.";
            }
            if (serverIp != null) {
                send("SERVER", serverIp, String.valueOf(serverPort));
            }
            if (proxyPid > 0) {
                send("PROXY", String.valueOf(proxyPid));
            }
            List<String> batch = new ArrayList<String>();
            String line;
            while ((line = in.readLine()) != null) {
                if ("END".equals(line)) {
                    Snapshot snap = Snapshot.decode(batch);
                    snapshot = snap;
                    message = snap.status;
                    batch.clear();
                } else if (batch.size() < 5000) {
                    batch.add(line);
                }
            }
            fail(gen, "The helper stopped.");
        } catch (SocketTimeoutException e) {
            fail(gen, "The helper did not connect within 30 s.");
        } catch (Exception e) {
            fail(gen, "Helper connection failed: " + e.getMessage());
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (Exception ignored) {
                    // closing
                }
            }
            synchronized (this) {
                if (gen == generation) {
                    close();
                }
            }
        }
    }

    private static String token() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
