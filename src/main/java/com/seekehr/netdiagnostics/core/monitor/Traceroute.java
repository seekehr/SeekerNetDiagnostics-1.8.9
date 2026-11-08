package com.seekehr.netdiagnostics.core.monitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the operating system's traceroute ({@code tracert} on Windows, {@code traceroute} elsewhere)
 * and parses hops as they arrive. This is the system's ICMP/UDP route: per-app optimizers such as
 * ExitLag do not carry it, so it shows the route your traffic takes without them.
 */
public final class Traceroute {
    private static final Pattern HOP = Pattern.compile("^\\s*(\\d+)\\s+(.*)$");
    private static final Pattern MS = Pattern.compile("(<?\\s*\\d+(?:[.,]\\d+)?)\\s*ms");
    private static final Pattern IP = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,3}){3}|[0-9a-fA-F]*:[0-9a-fA-F:]+)");

    public static final class Hop {
        public final int number;
        public final String address;
        /** Up to three round-trip times in ms; NaN for a timed-out probe. */
        public final double[] rtts;

        Hop(int number, String address, double[] rtts) {
            this.number = number;
            this.address = address;
            this.rtts = rtts;
        }

        public double best() {
            double b = Double.NaN;
            for (double v : rtts) {
                if (!Double.isNaN(v) && (Double.isNaN(b) || v < b)) {
                    b = v;
                }
            }
            return b;
        }
    }

    public final String destination;
    private final List<Hop> hops = new ArrayList<Hop>();
    private volatile boolean running;
    private volatile String error;
    private volatile Process process;

    public Traceroute(String destination) {
        this.destination = destination;
    }

    public boolean running() {
        return running;
    }

    public String error() {
        return error;
    }

    public List<Hop> hops() {
        synchronized (hops) {
            return Collections.unmodifiableList(new ArrayList<Hop>(hops));
        }
    }

    public void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                execute();
            }
        }, "SeekerNetDiagnostics-traceroute");
        t.setDaemon(true);
        t.start();
    }

    public void cancel() {
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }

    private void execute() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = new ArrayList<String>();
        if (windows) {
            Collections.addAll(cmd, "tracert", "-d", "-h", "30", "-w", "1000", destination);
        } else {
            Collections.addAll(cmd, "traceroute", "-n", "-q", "3", "-w", "1", "-m", "30", destination);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    Charset.defaultCharset()));
            String line;
            while ((line = in.readLine()) != null) {
                Hop h = parse(line);
                if (h != null) {
                    synchronized (hops) {
                        hops.add(h);
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            error = "Could not run " + cmd.get(0) + ": " + e.getMessage();
        } finally {
            running = false;
        }
    }

    /** Parses one output line of tracert/traceroute, or returns null if it is not a hop line. */
    static Hop parse(String line) {
        Matcher m = HOP.matcher(line);
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        String rest = m.group(2);
        if (n < 1 || n > 64 || !(rest.contains("ms") || rest.contains("*"))) {
            return null; // e.g. the "traceroute to x (y), 30 hops max" header
        }
        List<Double> times = new ArrayList<Double>();
        Matcher t = MS.matcher(rest);
        String withoutTimes = MS.matcher(rest).replaceAll(" ");
        while (t.find()) {
            String v = t.group(1).replace("<", "").replace(',', '.').trim();
            times.add(Double.parseDouble(v));
        }
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '*') {
                times.add(Double.NaN);
            }
        }
        String addr = null;
        Matcher ip = IP.matcher(withoutTimes);
        while (ip.find()) {
            addr = ip.group(1);
        }
        double[] arr = new double[Math.min(3, times.size())];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = times.get(i);
        }
        return new Hop(n, addr, arr);
    }
}
