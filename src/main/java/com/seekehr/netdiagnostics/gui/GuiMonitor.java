package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.core.monitor.Traceroute;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The whole UI: live ping graph, statistics, the connection route and a system traceroute. */
public final class GuiMonitor extends GuiBase {
    private static final int B_PAUSE = 0, B_INTERVAL = 1, B_RESET = 2, B_TRACE = 3, B_COPY = 4, B_CLOSE = 5;
    private static final int B_TAB_ROUTE = 10, B_TAB_TRACE = 11;
    private static final int TILE_H = 22;
    private static final int ROW_H = 10;

    private static boolean traceTab;

    private FlatButton pause;
    private FlatButton interval;
    private FlatButton tabRoute;
    private FlatButton tabTrace;
    private int lw;
    private int rx;
    private int rw;
    private int top;
    private int bottom;
    private int scroll;
    private final List<String[]> hover = new ArrayList<String[]>();

    @Override
    protected String title() {
        PingMonitor m = NetDiag.monitor();
        return "Ping Monitor" + (m == null ? "" : "  " + m.target.display());
    }

    @Override
    protected String status() {
        PingMonitor m = NetDiag.monitor();
        if (m == null) {
            return "Not running";
        }
        return (m.stopped() ? "Stopped" : m.status) + "  every " + fmtInterval(m.intervalMs);
    }

    @Override
    protected void layout() {
        top = HEADER + 4;
        bottom = height - 26;
        lw = (int) ((width - 24) * 0.6);
        rx = 8 + lw + 8;
        rw = width - rx - 8;
        int bw = (width - 16 - 5 * 4) / 6;
        int by = height - 20;
        pause = button(B_PAUSE, 8, by, bw, 14, "", FlatButton.Style.PRIMARY).tip("Pause or resume pinging (Space)");
        interval = button(B_INTERVAL, 8 + (bw + 4), by, bw, 14, "", FlatButton.Style.NORMAL)
                .tip("Time between pings. Each ping is a new connection; never faster than 0.5 s.");
        button(B_RESET, 8 + (bw + 4) * 2, by, bw, 14, "Reset", FlatButton.Style.NORMAL).tip("Clear samples and stats (R)");
        button(B_TRACE, 8 + (bw + 4) * 3, by, bw, 14, "Trace route", FlatButton.Style.NORMAL)
                .tip("Run the system traceroute to the server IP (T). It uses ICMP, which ExitLag does not route, "
                        + "so it shows your normal ISP path.");
        button(B_COPY, 8 + (bw + 4) * 4, by, bw, 14, "Copy", FlatButton.Style.NORMAL).tip("Copy a text summary (C)");
        button(B_CLOSE, 8 + (bw + 4) * 5, by, bw, 14, "Close", FlatButton.Style.GHOST)
                .tip("Close. Pinging continues in the background with a HUD; /snd stop to end it.");
        int tw = (rw - 4) / 2;
        tabRoute = button(B_TAB_ROUTE, rx, top, tw, 14, "Route", FlatButton.Style.TAB);
        tabTrace = button(B_TAB_TRACE, rx + tw + 4, top, rw - tw - 4, 14, "Traceroute", FlatButton.Style.TAB);
        refreshButtons();
    }

    private void refreshButtons() {
        PingMonitor m = NetDiag.monitor();
        boolean dead = m == null || m.stopped();
        pause.displayString = dead ? "Restart" : m.paused ? "Resume" : "Pause";
        interval.displayString = "Every " + fmtInterval(m == null ? 1000 : m.intervalMs);
        interval.enabled = !dead;
        tabRoute.style = traceTab ? FlatButton.Style.TAB : FlatButton.Style.TAB_ACTIVE;
        tabTrace.style = traceTab ? FlatButton.Style.TAB_ACTIVE : FlatButton.Style.TAB;
    }

    @Override
    public void updateScreen() {
        refreshButtons();
    }

    // ------------------------------------------------------------------ actions

    @Override
    protected void actionPerformed(GuiButton b) {
        PingMonitor m = NetDiag.monitor();
        switch (b.id) {
            case B_PAUSE:
                if (m == null || m.stopped()) {
                    if (m != null && NetDiag.currentServer() != null) {
                        NetDiag.monitor(m.target);
                    } else {
                        toast("Join a server and use /snd", Theme.WARN);
                    }
                } else {
                    m.paused = !m.paused;
                }
                break;
            case B_INTERVAL:
                if (m != null) {
                    m.cycleInterval();
                }
                break;
            case B_RESET:
                if (m != null) {
                    m.reset();
                    toast("Stats reset", Theme.ACCENT);
                }
                break;
            case B_TRACE:
                trace(m);
                break;
            case B_COPY:
                if (m != null) {
                    GuiScreen.setClipboardString(summary(m));
                    toast("Copied summary to clipboard", Theme.GOOD);
                }
                break;
            case B_CLOSE:
                mc.displayGuiScreen(null);
                break;
            case B_TAB_ROUTE:
                traceTab = false;
                break;
            case B_TAB_TRACE:
                traceTab = true;
                break;
            default:
                break;
        }
        refreshButtons();
    }

    private void trace(PingMonitor m) {
        if (m == null || m.address == null) {
            toast("No server address yet", Theme.WARN);
            return;
        }
        NetDiag.startTraceroute(m.address);
        traceTab = true;
        scroll = 0;
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_SPACE) {
            actionPerformed(pause);
        } else if (key == Keyboard.KEY_R) {
            actionPerformed(buttonList.get(B_RESET));
        } else if (key == Keyboard.KEY_T) {
            actionPerformed(buttonList.get(B_TRACE));
        } else if (key == Keyboard.KEY_C) {
            actionPerformed(buttonList.get(B_COPY));
        } else {
            super.keyTyped(c, key);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) {
            scroll = Math.max(0, scroll + (d > 0 ? -2 : 2));
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawContent(int mx, int my) {
        hover.clear();
        PingMonitor m = NetDiag.monitor();
        if (m == null) {
            Draw.textCentered(fontRendererObj, "Nothing is being monitored. Use /snd on a server.", width / 2f,
                    height / 2f, Theme.TEXT_DIM);
            return;
        }
        PingMonitor.Snapshot s = m.snapshot();
        drawTiles(s);
        int gy = top + TILE_H * 2 + 8;
        drawGraph(m, s, 8, gy, lw, bottom - gy, mx, my);
        int py = top + 18;
        Draw.card(rx, py, rw, bottom - py, Theme.CARD, Theme.BORDER);
        if (traceTab) {
            drawTrace(rx + 5, py + 5, rw - 10, bottom - py - 10, mx, my);
        } else {
            drawRoute(m, s, rx + 5, py + 5, rw - 10, mx, my);
        }
    }

    private void drawTiles(PingMonitor.Snapshot s) {
        PingMonitor.Sample last = s.last();
        StatSummary p = s.ping;
        String[] labels = {"Now", "Average", "Median", "Min", "Max", "P95", "P99", "Jitter", "Loss", "Spikes"};
        String[] values = {
                last == null ? "-" : last.ok() ? ms(last.pingMs) : "FAIL",
                ms(p.mean), ms(p.median), ms(p.min), ms(p.max), ms(p.p95), ms(p.p99),
                p.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f", p.jitter),
                s.samples.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f%%", s.lossPercent()),
                String.valueOf(s.spikes)};
        int[] colors = {
                last == null ? Theme.TEXT_DIM : last.ok() ? Theme.latencyColor(last.pingMs) : Theme.BAD,
                Theme.latencyColor(p.mean), Theme.latencyColor(p.median), Theme.latencyColor(p.min),
                Theme.latencyColor(p.max), Theme.latencyColor(p.p95), Theme.latencyColor(p.p99),
                p.isEmpty() ? Theme.TEXT_DIM : p.jitter < 5 ? Theme.GOOD : p.jitter < 15 ? Theme.WARN : Theme.BAD,
                s.failures == 0 ? Theme.GOOD : s.lossPercent() < 2 ? Theme.WARN : Theme.BAD,
                s.spikes == 0 ? Theme.GOOD : Theme.WARN};
        int tw = (lw - 4 * 4) / 5;
        for (int i = 0; i < labels.length; i++) {
            int x = 8 + (i % 5) * (tw + 4);
            int y = top + (i / 5) * (TILE_H + 4);
            Draw.card(x, y, tw, TILE_H, Theme.CARD, Theme.BORDER);
            Draw.textClipped(fontRendererObj, labels[i], x + 4, y + 3, tw - 6, Theme.TEXT_FAINT);
            Draw.textClipped(fontRendererObj, values[i], x + 4, y + 12, tw - 6, colors[i]);
        }
    }

    private void drawGraph(PingMonitor m, PingMonitor.Snapshot s, int x, int y, int w, int h, int mx, int my) {
        Draw.card(x, y, w, h, Theme.CARD, Theme.BORDER);
        int lx = x + 22;
        int gx0 = lx;
        int gx1 = x + w - 4;
        int gy0 = y + 14;
        int gy1 = y + h - 4;
        int gw = gx1 - gx0;
        int gh = gy1 - gy0;
        String legend = s.samples.size() + " samples";
        Draw.text(fontRendererObj, legend, x + 4, y + 3, Theme.TEXT_FAINT);
        int lgx = x + w - 4;
        lgx = legendItem("fail", Theme.BAD, lgx, y + 3);
        if (NetDiag.isGameServer(m)) {
            lgx = legendItem("tab-list ping", Theme.ACCENT, lgx, y + 3);
        }
        legendItem("ping", Theme.GOOD, lgx, y + 3);
        if (gw < 10 || gh < 10) {
            return;
        }
        List<PingMonitor.Sample> all = s.samples;
        int barW = all.size() * 3 <= gw ? 3 : 2;
        int count = Math.min(all.size(), gw / barW);
        List<PingMonitor.Sample> vis = all.subList(all.size() - count, all.size());
        double max = 40;
        for (PingMonitor.Sample v : vis) {
            if (v.ok()) {
                max = Math.max(max, v.pingMs);
            }
            if (v.tabPingMs >= 0) {
                max = Math.max(max, v.tabPingMs);
            }
        }
        double step = niceStep(max / 4);
        double yMax = Math.ceil(max * 1.1 / step) * step;
        for (double g = 0; g <= yMax + 1e-9; g += step) {
            int gy = (int) (gy1 - g / yMax * gh);
            Draw.rect(gx0, gy, gx1, gy + 1, Theme.GRID);
            Draw.textRight(fontRendererObj, String.valueOf((int) g), lx - 3, gy - 4, Theme.TEXT_FAINT);
        }
        if (!s.ping.isEmpty()) {
            int my0 = (int) (gy1 - s.ping.median / yMax * gh);
            for (int dx = gx0; dx < gx1; dx += 6) {
                Draw.rect(dx, my0, Math.min(gx1, dx + 3), my0 + 1, Theme.TEXT_FAINT);
            }
        }
        int hovered = -1;
        for (int i = 0; i < vis.size(); i++) {
            PingMonitor.Sample v = vis.get(i);
            int bx = gx1 - (vis.size() - i) * barW;
            if (mx >= bx && mx < bx + barW && my >= gy0 && my <= gy1) {
                hovered = i;
            }
            if (v.ok()) {
                int by = (int) (gy1 - v.pingMs / yMax * gh);
                Draw.rect(bx, Math.min(by, gy1 - 1), bx + barW - 1, gy1, Theme.latencyColor(v.pingMs));
            } else {
                Draw.rect(bx, gy0, bx + barW - 1, gy1, Theme.withAlpha(Theme.BAD, 0.45f));
            }
            if (v.tabPingMs >= 0) {
                int ty = (int) (gy1 - v.tabPingMs / yMax * gh);
                Draw.rect(bx, ty - 1, bx + barW, ty + 1, Theme.ACCENT);
            }
        }
        if (vis.isEmpty()) {
            Draw.textCentered(fontRendererObj, m.status, (gx0 + gx1) / 2f, (gy0 + gy1) / 2f - 4, Theme.TEXT_DIM);
        }
        if (hovered >= 0) {
            PingMonitor.Sample v = vis.get(hovered);
            int bx = gx1 - (vis.size() - hovered) * barW;
            Draw.rect(bx, gy0, bx + barW - 1, gy1, 0x40FFFFFF);
            List<String> tip = new ArrayList<String>();
            tip.add(((System.currentTimeMillis() - v.time) / 1000) + " s ago");
            tip.add(v.ok() ? "Ping: " + ms(v.pingMs) + " ms" : "Failed: " + v.outcome.label);
            if (!Double.isNaN(v.connectMs)) {
                tip.add("TCP connect: " + String.format(Locale.ROOT, "%.2f ms", v.connectMs));
            }
            if (v.tabPingMs >= 0) {
                tip.add("Tab-list ping: " + v.tabPingMs + " ms");
            }
            if (v.error != null) {
                tip.addAll(fontRendererObj.listFormattedStringToWidth(v.error, 200));
            }
            hover.add(tip.toArray(new String[0]));
        }
    }

    private int legendItem(String label, int color, int right, int y) {
        int x = right - fontRendererObj.getStringWidth(label);
        Draw.text(fontRendererObj, label, x, y, Theme.TEXT_FAINT);
        Draw.rect(x - 7, y + 2, x - 3, y + 6, color);
        return x - 12;
    }

    private void drawRoute(PingMonitor m, PingMonitor.Snapshot s, int x, int y, int w, int mx, int my) {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[]{"You typed", m.target.display()});
        rows.add(new String[]{"SRV record", m.srvRecord == null ? "none" : m.srvRecord});
        if (m.dns != null) {
            String extra = m.dns.addresses.size() > 1 ? " (+" + (m.dns.addresses.size() - 1) + " more)" : "";
            rows.add(new String[]{"DNS", m.dns.resolved() ? m.dns.addresses.get(0) + extra
                    + String.format(Locale.ROOT, " in %.0f ms", m.dns.lookupMs) : "failed"});
        }
        rows.add(new String[]{"Pinging", m.address == null ? "..." : m.address + ":" + m.connectPort});
        boolean game = NetDiag.isGameServer(m);
        if (game) {
            NetDiag.GameSocket g = NetDiag.gameSocket();
            rows.add(new String[]{"Game socket", g.remote == null ? "unknown" : g.remote});
            rows.add(new String[]{"Game local", g.local == null ? "unknown"
                    : g.local + (g.localInterface == null ? "" : " (" + g.localInterface + ")")});
        }
        rows.add(new String[]{"Probe local", m.probeLocalAddress == null ? "..."
                : m.probeLocalAddress + (m.probeInterface == null ? "" : " (" + m.probeInterface + ")")});
        StatSummary c = s.connect;
        rows.add(new String[]{"TCP connect", c.isEmpty() ? "-" : String.format(Locale.ROOT, "%.2f ms median", c.median)});
        rows.add(new String[]{"Path", c.isEmpty() ? "-" : m.localProxyDetected(s)
                ? "local proxy (ExitLag/VPN) intercepts" : "direct to server"});
        if (game) {
            int tab = NetDiag.tabPing();
            String v = tab < 0 ? "-" : tab + " ms";
            if (tab >= 0 && !s.ping.isEmpty()) {
                v += String.format(Locale.ROOT, " (probe median %s, diff %+.0f)", ms(s.ping.median), tab - s.ping.median);
            }
            rows.add(new String[]{"Tab ping", v});
        }
        McProtocol.StatusInfo si = m.serverInfo;
        if (si != null) {
            rows.add(new String[]{"Server", (si.versionName == null ? "?" : si.versionName) + " (protocol " + si.protocol
                    + ")"});
            rows.add(new String[]{"Players", si.onlinePlayers + " / " + si.maxPlayers});
            if (si.motd != null && !si.motd.isEmpty()) {
                rows.add(new String[]{"MOTD", si.motd});
            }
        }
        int labelW = 62;
        for (int i = 0; i < rows.size(); i++) {
            int ry = y + i * (ROW_H + 1);
            if (ry + ROW_H > bottom - 4) {
                break;
            }
            String[] r = rows.get(i);
            int color = "Path".equals(r[0]) && r[1].startsWith("local") ? Theme.ACCENT : Theme.TEXT;
            Draw.textClipped(fontRendererObj, r[0], x, ry, labelW - 4, Theme.TEXT_FAINT);
            Draw.textClipped(fontRendererObj, r[1], x + labelW, ry, w - labelW, color);
            if (mx >= x && mx < x + w && my >= ry - 1 && my < ry + ROW_H
                    && fontRendererObj.getStringWidth(r[1]) > w - labelW) {
                hover.add(fontRendererObj.listFormattedStringToWidth(r[1], 220).toArray(new String[0]));
            }
        }
    }

    private void drawTrace(int x, int y, int w, int h, int mx, int my) {
        Traceroute tr = NetDiag.traceroute();
        if (tr == null) {
            Draw.wrapped(fontRendererObj, "Press Trace route (T) to see each hop between you and the server. "
                    + "This is the system route (ICMP); ExitLag and similar tools do not carry it.", x, y, w,
                    Theme.TEXT_DIM, 8);
            return;
        }
        String head = (tr.running() ? "Tracing " : "Route to ") + tr.destination;
        Draw.textClipped(fontRendererObj, head, x, y, w, tr.running() ? Theme.ACCENT : Theme.TEXT);
        int ry = y + ROW_H + 3;
        if (tr.error() != null) {
            Draw.wrapped(fontRendererObj, tr.error(), x, ry, w, Theme.BAD, 4);
            return;
        }
        List<Traceroute.Hop> hops = tr.hops();
        int fit = Math.max(1, (y + h - ry) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, hops.size() - fit));
        for (int i = scroll; i < hops.size() && i < scroll + fit; i++) {
            Traceroute.Hop hop = hops.get(i);
            int yy = ry + (i - scroll) * ROW_H;
            double best = hop.best();
            Draw.textRight(fontRendererObj, String.valueOf(hop.number), x + 12, yy, Theme.TEXT_FAINT);
            Draw.textClipped(fontRendererObj, hop.address == null ? "* no reply" : hop.address, x + 18, yy, w - 60,
                    hop.address == null ? Theme.TEXT_FAINT : Theme.TEXT);
            Draw.textRight(fontRendererObj, Double.isNaN(best) ? "*" : ms(best) + " ms", x + w, yy,
                    Theme.latencyColor(best));
            if (mx >= x && mx < x + w && my >= yy - 1 && my < yy + ROW_H - 1) {
                StringBuilder sb = new StringBuilder("Hop " + hop.number + ":");
                for (double r : hop.rtts) {
                    sb.append(Double.isNaN(r) ? "  *" : "  " + ms(r) + " ms");
                }
                hover.add(new String[]{sb.toString()});
            }
        }
        if (hops.isEmpty() && tr.running()) {
            Draw.text(fontRendererObj, "waiting for first hop...", x, ry, Theme.TEXT_FAINT);
        }
    }

    @Override
    protected void drawOverlay(int mx, int my) {
        if (!hover.isEmpty()) {
            List<String> lines = new ArrayList<String>();
            for (String l : hover.get(0)) {
                lines.add(l);
            }
            Draw.tooltip(fontRendererObj, lines, mx, my, width, height);
        }
    }

    // ------------------------------------------------------------------ text

    private static String summary(PingMonitor m) {
        PingMonitor.Snapshot s = m.snapshot();
        StatSummary p = s.ping;
        StringBuilder sb = new StringBuilder();
        sb.append("SeekerNetDiagnostics ping monitor: ").append(m.target.display()).append('\n');
        sb.append("Pinging ").append(m.address).append(':').append(m.connectPort);
        if (m.srvRecord != null) {
            sb.append(" via SRV ").append(m.srvRecord);
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "Samples %d over %ds, every %s%n", s.samples.size(),
                s.samples.isEmpty() ? 0 : (s.last().time - s.samples.get(0).time) / 1000, fmtInterval(m.intervalMs)));
        if (!p.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Ping avg %.1f, median %.1f, min %.1f, max %.1f, p95 %.1f, p99 %.1f, "
                    + "jitter %.1f ms%n", p.mean, p.median, p.min, p.max, p.p95, p.p99, p.jitter));
        }
        sb.append(String.format(Locale.ROOT, "Loss %.1f%% (%d failed), spikes %d%n", s.lossPercent(), s.failures,
                s.spikes));
        if (!s.connect.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "TCP connect median %.2f ms: %s%n", s.connect.median,
                    m.localProxyDetected(s) ? "local proxy intercepts" : "direct"));
        }
        if (m.probeLocalAddress != null) {
            sb.append("Probe adapter: ").append(m.probeInterface == null ? "?" : m.probeInterface).append('\n');
        }
        if (NetDiag.isGameServer(m) && NetDiag.tabPing() >= 0) {
            sb.append("Tab-list ping: ").append(NetDiag.tabPing()).append(" ms\n");
        }
        Traceroute tr = NetDiag.traceroute();
        if (tr != null && !tr.hops().isEmpty()) {
            sb.append("Traceroute to ").append(tr.destination).append(":\n");
            for (Traceroute.Hop h : tr.hops()) {
                double b = h.best();
                sb.append(String.format(Locale.ROOT, "%3d  %-40s %s%n", h.number,
                        h.address == null ? "*" : h.address, Double.isNaN(b) ? "*" : ms(b) + " ms"));
            }
        }
        return sb.toString();
    }

    private static String ms(double v) {
        if (Double.isNaN(v)) {
            return "-";
        }
        return v < 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.0f", v);
    }

    private static String fmtInterval(int ms) {
        return ms % 1000 == 0 ? (ms / 1000) + "s" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    private static double niceStep(double raw) {
        double[] steps = {5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000};
        for (double st : steps) {
            if (st >= raw) {
                return st;
            }
        }
        return 5000;
    }
}
