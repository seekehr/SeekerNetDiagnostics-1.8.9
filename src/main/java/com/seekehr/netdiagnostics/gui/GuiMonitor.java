package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.dns.AsnLookup;
import com.seekehr.netdiagnostics.core.dns.ProxyDetector;
import com.seekehr.netdiagnostics.core.mc.McProtocol;
import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.core.monitor.Traceroute;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.helper.HelperClient;
import com.seekehr.netdiagnostics.helper.Snapshot;
import com.seekehr.netdiagnostics.mod.AdvancedTracing;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The whole UI: live ping graph, statistics, the connection route and a system traceroute. */
public final class GuiMonitor extends GuiBase {
    private static final int B_PAUSE = 0, B_INTERVAL = 1, B_RESET = 2, B_TRACE = 3, B_COPY = 4, B_CLOSE = 5;
    private static final int B_TAB_ROUTE = 10, B_TAB_TRACE = 11, B_ADV = 12;
    /** Height taken by the Advanced Tracing checkbox at the top of the Traceroute tab. */
    private static final int ADV_H = 16;
    private static final int TILE_H = 22;
    private static final int ROW_H = 10;
    private static final String PROXY_PREFIX = "USING PROXY: ";
    /** A 0 ms tab-list ping is never real: not reported yet, or faked by the server/proxy. Ignored in comparisons. */
    private static final String TAB_ZERO = "0 ms (not reported or faked, ignored)";

    private static boolean traceTab;

    private FlatButton pause;
    private FlatButton interval;
    private FlatButton tabRoute;
    private FlatButton tabTrace;
    private FlatButton adv;
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
                .tip("Trace the route to the server (T): TCP probes on the game port check whether ExitLag or "
                        + "another local proxy carries it, and the system traceroute (ICMP) lists the routers of your "
                        + "normal ISP path.");
        button(B_COPY, 8 + (bw + 4) * 4, by, bw, 14, "Copy", FlatButton.Style.NORMAL).tip("Copy a text summary (C)");
        button(B_CLOSE, 8 + (bw + 4) * 5, by, bw, 14, "Close", FlatButton.Style.GHOST)
                .tip("Close. Pinging continues in the background with a HUD; /snd stop to end it.");
        int tw = (rw - 4) / 2;
        tabRoute = button(B_TAB_ROUTE, rx, top, tw, 14, "Route", FlatButton.Style.TAB);
        tabTrace = button(B_TAB_TRACE, rx + tw + 4, top, rw - tw - 4, 14, "Traceroute", FlatButton.Style.TAB);
        adv = button(B_ADV, rx + 5, top + 18 + 4, rw - 10, 12, "", FlatButton.Style.NORMAL)
                .tip("Starts a separate helper with administrator rights (Windows asks through UAC; Minecraft itself "
                        + "is not elevated). It captures packets to find the relays ExitLag really uses, traces the "
                        + "routers to them, and watches for route changes. While it runs, it adds a temporary Windows "
                        + "Firewall rule so that Java can receive ICMP error replies (types 3 and 11 only); the rule is "
                        + "removed when it stops. Hops inside ExitLag's tunnel are never shown: they cannot be seen.");
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
        adv.visible = traceTab;
        adv.enabled = AdvancedTracing.supported();
        adv.displayString = (AdvancedTracing.enabled() ? "[x] " : "[ ] ") + "Enable Advanced Tracing (Admin)";
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
            case B_ADV:
                boolean on = !AdvancedTracing.enabled();
                AdvancedTracing.setEnabled(on);
                if (on && m != null && m.address != null) {
                    AdvancedTracing.onTrace(m.address, m.connectPort);
                }
                toast(on ? "Approve the Windows prompt to start the helper" : "Advanced tracing off",
                        on ? Theme.ACCENT : Theme.TEXT_DIM);
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
        NetDiag.startTraceroute(m.address, m.connectPort);
        AdvancedTracing.onTrace(m.address, m.connectPort);
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
            drawTrace(rx + 5, py + 5 + ADV_H, rw - 10, bottom - py - 10 - ADV_H, mx, my);
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

    private static final int GRAPH_MAX_POINTS = 120;
    private static final int CURVE_STEPS = 6;

    /** Smooth ping curve with a soft fill, tab-list ping line, failure markers and hover tooltips. */
    private void drawGraph(PingMonitor m, PingMonitor.Snapshot s, int x, int y, int w, int h, int mx, int my) {
        Draw.card(x, y, w, h, Theme.CARD, Theme.BORDER);
        int lx = x + 22;
        int gx0 = lx;
        int gx1 = x + w - 6;
        int gy0 = y + 14;
        int gy1 = y + h - 4;
        int gw = gx1 - gx0;
        int gh = gy1 - gy0;
        int sx = Draw.text(fontRendererObj, s.samples.size() + " samples", x + 4, y + 3, Theme.TEXT_FAINT);
        ProxyDetector.Result px0 = proxy(m);
        if (px0.provider != null) {
            Draw.text(fontRendererObj, "  USING PROXY: " + px0.provider, sx, y + 3, Theme.WARN);
        }
        int lgx = x + w - 4;
        lgx = legendItem("fail", Theme.BAD, lgx, y + 3);
        boolean game = NetDiag.isGameServer(m);
        if (game) {
            lgx = legendItem("tab-list ping", Theme.ACCENT, lgx, y + 3);
        }
        legendItem("ping", Theme.GOOD, lgx, y + 3);
        if (gw < 20 || gh < 10) {
            return;
        }

        List<PingMonitor.Sample> all = s.samples;
        int cap = Math.max(2, Math.min(GRAPH_MAX_POINTS, gw / 3));
        double sp = gw / (double) (cap - 1);
        int count = Math.min(all.size(), cap);
        List<PingMonitor.Sample> vis = all.subList(all.size() - count, all.size());
        double max = 40;
        for (PingMonitor.Sample v : vis) {
            if (v.ok()) {
                max = Math.max(max, v.pingMs);
            }
            if (v.tabPingMs > 0) {
                max = Math.max(max, v.tabPingMs);
            }
        }
        double step = niceStep(max / 4);
        double yMax = Math.ceil(max * 1.1 / step) * step;

        // point positions (NaN y = failed sample)
        float[] px = new float[count];
        float[] py = new float[count];
        for (int i = 0; i < count; i++) {
            PingMonitor.Sample v = vis.get(i);
            px[i] = (float) (gx1 - (count - 1 - i) * sp);
            py[i] = v.ok() ? (float) (gy1 - v.pingMs / yMax * gh) : Float.NaN;
        }

        int hovered = -1;
        if (count > 0 && mx >= gx0 - sp / 2 && mx <= gx1 + sp / 2 && my >= gy0 && my <= gy1) {
            int idx = count - 1 - (int) Math.round((gx1 - mx) / sp);
            if (idx >= 0 && idx < count) {
                hovered = idx;
            }
        }

        // ---- pass 1: quads (grid, failures, fill under curve, median, hover guide, lone points)
        Draw.batch();
        for (double g = 0; g <= yMax + 1e-9; g += step) {
            int gy = (int) (gy1 - g / yMax * gh);
            Draw.quad(gx0, gy, gx1, gy + 1, Theme.GRID);
        }
        int failColor = Theme.withAlpha(Theme.BAD, 0.30f);
        float half = (float) Math.max(1, sp / 2);
        for (int i = 0; i < count; i++) {
            if (Float.isNaN(py[i])) {
                Draw.quad(px[i] - half, gy0, px[i] + half, gy1, failColor);
            }
        }
        for (int i = 0; i + 1 < count; i++) {
            if (Float.isNaN(py[i]) || Float.isNaN(py[i + 1])) {
                continue;
            }
            int c1 = Theme.latencyColor(vis.get(i).pingMs);
            int c2 = Theme.latencyColor(vis.get(i + 1).pingMs);
            float xa = px[i];
            float ya = py[i];
            for (int k = 1; k <= CURVE_STEPS; k++) {
                float t = k / (float) CURVE_STEPS;
                float xb = px[i] + (px[i + 1] - px[i]) * t;
                float yb = curveY(py, i, t, gy0, gy1);
                int c = Theme.lerp(c1, c2, t);
                Draw.vertex(xa, gy1, Theme.withAlpha(c, 0.02f));
                Draw.vertex(xb, gy1, Theme.withAlpha(c, 0.02f));
                Draw.vertex(xb, yb, Theme.withAlpha(c, 0.28f));
                Draw.vertex(xa, ya, Theme.withAlpha(c, 0.28f));
                xa = xb;
                ya = yb;
            }
        }
        for (int i = 0; i < count; i++) {
            boolean lone = !Float.isNaN(py[i]) && (i == 0 || Float.isNaN(py[i - 1]))
                    && (i == count - 1 || Float.isNaN(py[i + 1]));
            if (lone) {
                Draw.quad(px[i] - 1, py[i] - 1, px[i] + 1, py[i] + 1, Theme.latencyColor(vis.get(i).pingMs));
            }
        }
        if (!s.ping.isEmpty()) {
            int my0 = (int) (gy1 - s.ping.median / yMax * gh);
            for (int dx = gx0; dx < gx1; dx += 6) {
                Draw.quad(dx, my0, Math.min(gx1, dx + 3), my0 + 1, Theme.withAlpha(Theme.TEXT_FAINT, 0.6f));
            }
        }
        if (hovered >= 0) {
            Draw.quad(px[hovered], gy0, px[hovered] + 1, gy1, 0x50FFFFFF);
        }
        Draw.flush();

        // ---- pass 2: lines (ping curve, tab-list ping)
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(1.5f * new ScaledResolution(mc).getScaleFactor());
        Draw.batch(GL11.GL_LINES);
        for (int i = 0; i + 1 < count; i++) {
            if (Float.isNaN(py[i]) || Float.isNaN(py[i + 1])) {
                continue;
            }
            int c1 = Theme.latencyColor(vis.get(i).pingMs);
            int c2 = Theme.latencyColor(vis.get(i + 1).pingMs);
            float xa = px[i];
            float ya = py[i];
            int ca = c1;
            for (int k = 1; k <= CURVE_STEPS; k++) {
                float t = k / (float) CURVE_STEPS;
                float xb = px[i] + (px[i + 1] - px[i]) * t;
                float yb = curveY(py, i, t, gy0, gy1);
                int cb = Theme.lerp(c1, c2, t);
                Draw.line(xa, ya, xb, yb, ca, cb);
                xa = xb;
                ya = yb;
                ca = cb;
            }
        }
        if (game) {
            int prev = -1;
            for (int i = 0; i < count; i++) {
                int tab = vis.get(i).tabPingMs;
                if (tab <= 0) {
                    prev = -1;
                    continue;
                }
                if (prev >= 0) {
                    float ya = (float) (gy1 - vis.get(prev).tabPingMs / yMax * gh);
                    float yb = (float) (gy1 - tab / yMax * gh);
                    Draw.line(px[prev], ya, px[i], yb, Theme.ACCENT, Theme.ACCENT);
                }
                prev = i;
            }
        }
        Draw.flush();
        GL11.glLineWidth(1f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);

        // ---- text and hover
        for (double g = 0; g <= yMax + 1e-9; g += step) {
            int gy = (int) (gy1 - g / yMax * gh);
            Draw.textRight(fontRendererObj, String.valueOf((int) g), lx - 3, gy - 4, Theme.TEXT_FAINT);
        }
        if (vis.isEmpty()) {
            Draw.textCentered(fontRendererObj, m.status, (gx0 + gx1) / 2f, (gy0 + gy1) / 2f - 4, Theme.TEXT_DIM);
        }
        if (hovered >= 0) {
            PingMonitor.Sample v = vis.get(hovered);
            float hy = Float.isNaN(py[hovered]) ? (gy0 + gy1) / 2f : py[hovered];
            int dot = v.ok() ? Theme.latencyColor(v.pingMs) : Theme.BAD;
            Draw.rect(px[hovered] - 2, hy - 2, px[hovered] + 3, hy + 3, 0xFF000000);
            Draw.rect(px[hovered] - 1, hy - 1, px[hovered] + 2, hy + 2, dot);
            List<String> tip = new ArrayList<String>();
            tip.add(((System.currentTimeMillis() - v.time) / 1000) + " s ago");
            tip.add(v.ok() ? "Ping: " + ms(v.pingMs) + " ms" : "Failed: " + v.outcome.label);
            if (!Double.isNaN(v.connectMs)) {
                tip.add("TCP connect: " + String.format(Locale.ROOT, "%.2f ms", v.connectMs));
            }
            if (v.tabPingMs > 0) {
                tip.add("Tab-list ping: " + v.tabPingMs + " ms");
            } else if (v.tabPingMs == 0) {
                tip.add("Tab-list ping: " + TAB_ZERO);
            }
            if (v.error != null) {
                tip.addAll(fontRendererObj.listFormattedStringToWidth(v.error, 200));
            }
            hover.add(tip.toArray(new String[0]));
        }
    }

    /** Catmull-Rom between points i and i+1 (both valid); missing neighbours are clamped; result kept in bounds. */
    private static float curveY(float[] py, int i, float t, int gy0, int gy1) {
        float p1 = py[i];
        float p2 = py[i + 1];
        float p0 = i > 0 && !Float.isNaN(py[i - 1]) ? py[i - 1] : p1;
        float p3 = i + 2 < py.length && !Float.isNaN(py[i + 2]) ? py[i + 2] : p2;
        float t2 = t * t;
        float t3 = t2 * t;
        float v = 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
        return Math.max(gy0, Math.min(gy1, v));
    }

    private int legendItem(String label, int color, int right, int y) {
        int x = right - fontRendererObj.getStringWidth(label);
        Draw.text(fontRendererObj, label, x, y, Theme.TEXT_FAINT);
        Draw.rect(x - 7, y + 2, x - 3, y + 6, color);
        return x - 12;
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
        AsnLookup.Info server = AsnLookup.get(m.address);
        if (server != null) {
            rows.add(new String[]{"Network", ownerText(server)});
        }
        ProxyDetector.Result px = proxy(m);
        if (px == ProxyDetector.PENDING) {
            rows.add(new String[]{"Proxy", m.address == null ? "-" : "checking..."});
        } else if (px.provider == null) {
            rows.add(new String[]{"Proxy", "none detected"});
        } else {
            rows.add(new String[]{"Proxy", PROXY_PREFIX + px.provider + " (ping past its edge is hidden from tracert)"});
            rows.add(new String[]{"Evidence", px.evidence});
        }
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
            String v = tab < 0 ? "-" : tab == 0 ? TAB_ZERO : tab + " ms";
            if (tab > 0 && !s.ping.isEmpty()) {
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
            int color = "Path".equals(r[0]) && r[1].startsWith("local") ? Theme.ACCENT
                    : TAB_ZERO.equals(r[1]) || r[1].startsWith(PROXY_PREFIX) ? Theme.WARN : Theme.TEXT;
            Draw.textClipped(fontRendererObj, r[0], x, ry, labelW - 4, Theme.TEXT_FAINT);
            Draw.textClipped(fontRendererObj, r[1], x + labelW, ry, w - labelW, color);
            if (mx >= x && mx < x + w && my >= ry - 1 && my < ry + ROW_H
                    && fontRendererObj.getStringWidth(r[1]) > w - labelW) {
                hover.add(fontRendererObj.listFormattedStringToWidth(r[1], 220).toArray(new String[0]));
            }
        }
    }

    // ------------------------------------------------------------------ traceroute tab (scrollable rows)

    /** One line of the Traceroute tab: text (plain or header) or a hop with columns. */
    private static final class Row {
        String text = "";
        int color = Theme.TEXT;
        String[] tip;
        boolean hop;
        String num;
        String addr;
        String owner;
        boolean boundary;
        String loss;
        double lossPct = Double.NaN;
        double ms = Double.NaN;
    }

    private void drawTrace(int x, int y, int w, int h, int mx, int my) {
        List<Row> rows = traceRows(w);
        int fit = Math.max(1, h / ROW_H);
        scroll = Math.max(0, Math.min(scroll, rows.size() - fit));
        int addrW = Math.min(w - 90, fontRendererObj.getStringWidth("255.255.255.255") + 6);
        for (int i = scroll; i < rows.size() && i < scroll + fit; i++) {
            Row r = rows.get(i);
            int yy = y + (i - scroll) * ROW_H;
            if (r.hop) {
                int msW = 36;
                int lossW = r.loss == null ? 0 : 28;
                int ownerW = w - 18 - addrW - msW - lossW;
                Draw.textRight(fontRendererObj, r.num, x + 12, yy, Theme.TEXT_FAINT);
                Draw.textClipped(fontRendererObj, r.addr == null ? "* no reply" : r.addr, x + 18, yy, addrW - 4,
                        r.addr == null ? Theme.TEXT_FAINT : r.color);
                if (r.owner != null && ownerW > 20) {
                    Draw.textClipped(fontRendererObj, r.owner, x + 18 + addrW, yy, ownerW,
                            r.boundary ? Theme.ACCENT : Theme.TEXT_FAINT);
                }
                if (r.loss != null) {
                    int lc = Double.isNaN(r.lossPct) || r.lossPct == 0 ? Theme.TEXT_FAINT
                            : r.lossPct >= 50 ? Theme.BAD : Theme.WARN;
                    Draw.textRight(fontRendererObj, r.loss, x + w - msW, yy, lc);
                }
                Draw.textRight(fontRendererObj, Double.isNaN(r.ms) ? "*" : ms(r.ms) + " ms", x + w, yy,
                        Theme.latencyColor(r.ms));
            } else {
                Draw.textClipped(fontRendererObj, r.text, x, yy, w, r.color);
            }
            if (mx >= x && mx < x + w && my >= yy - 1 && my < yy + ROW_H - 1) {
                if (r.tip != null) {
                    hover.add(r.tip);
                } else if (!r.hop && fontRendererObj.getStringWidth(r.text) > w) {
                    hover.add(fontRendererObj.listFormattedStringToWidth(r.text, 240).toArray(new String[0]));
                }
            }
        }
    }

    private List<Row> traceRows(int w) {
        List<Row> rows = new ArrayList<Row>();
        Traceroute tr = NetDiag.traceroute();
        if (tr == null) {
            wrap(rows, "Press Trace route (T). TCP probes on the game port check whether ExitLag or another local "
                    + "proxy carries the connection; the system traceroute (ICMP) then lists the routers of your normal "
                    + "path. The ICMP hops are never ExitLag's route.", Theme.TEXT_DIM, w);
        } else {
            text(rows, (tr.running() ? "Tracing " : "Route to ") + tr.destination + ":" + tr.port,
                    tr.running() ? Theme.ACCENT : Theme.TEXT);
            tcpRows(rows, tr, w);
        }
        advancedRows(rows, tr, w);
        if (tr != null) {
            icmpRows(rows, tr, w);
        }
        return rows;
    }

    private void tcpRows(List<Row> rows, Traceroute tr, int w) {
        Traceroute.TcpResult t = tr.tcp();
        if (t == null) {
            text(rows, tr.running() ? "TCP check: " + tr.tcpStatus() + "..." : "TCP check: cancelled", Theme.TEXT_DIM);
            return;
        }
        double best = t.best();
        String at = t.hopDistance > 0 ? ", server at hop " + t.hopDistance : "";
        String rtt = Double.isNaN(best) ? "" : ", " + ms(best) + " ms";
        String title;
        int color;
        switch (t.verdict) {
            case EXITLAG_VERIFIED:
                title = "TCP: ExitLag-routed (verified)" + at + rtt;
                color = Theme.ACCENT;
                break;
            case LOCAL_PROXY:
                title = "TCP: local proxy, ExitLag not verified" + rtt;
                color = Theme.WARN;
                break;
            case DIRECT:
                title = "TCP: direct, not via ExitLag" + at + rtt;
                color = Theme.GOOD;
                break;
            default:
                title = "TCP: ExitLag routing not verified";
                color = Theme.TEXT_DIM;
                break;
        }
        text(rows, title, color);
        wrap(rows, t.detail, Theme.TEXT_DIM, w);
    }

    private void advancedRows(List<Row> rows, Traceroute tr, int w) {
        HelperClient c = AdvancedTracing.client();
        if (!AdvancedTracing.enabled() && !c.active()) {
            return;
        }
        text(rows, "", Theme.TEXT);
        text(rows, "Advanced tracing (admin helper)", Theme.TEXT);
        Snapshot s = c.snapshot();
        HelperClient.State st = c.state();
        int stColor = st == HelperClient.State.FAILED ? Theme.BAD : st == HelperClient.State.CONNECTED
                ? (s != null && !s.captureOk ? Theme.WARN : Theme.GOOD) : Theme.TEXT_DIM;
        String msg = c.message();
        if (st == HelperClient.State.FAILED) {
            msg += " Turn the checkbox off and on, or press T, to try again.";
        }
        wrap(rows, msg, stColor, w);
        if (s == null) {
            return;
        }
        if (!s.exitLagRunning) {
            text(rows, "No ExitLag process found on this PC.", Theme.TEXT_DIM);
        } else {
            List<Snapshot.Relay> inUse = new ArrayList<Snapshot.Relay>();
            List<String> probing = new ArrayList<String>();
            List<String> idle = new ArrayList<String>();
            for (Snapshot.Relay r : s.relays) {
                if (r.inUse) {
                    inUse.add(r);
                } else if (r.verified) {
                    probing.add(r.protocol + " " + r.endpoint() + (r.service ? " (ExitLag web/API)" : ""));
                } else {
                    idle.add(r.protocol + " " + r.endpoint());
                }
            }
            text(rows, "ExitLag relays in use (verified: captured traffic on ExitLag's own sockets)", Theme.TEXT_DIM);
            if (inUse.isEmpty()) {
                wrap(rows, "None right now: ExitLag isn't tunnelling anything. Join the server with ExitLag on.",
                        Theme.TEXT_FAINT, w);
            }
            for (Snapshot.Relay r : inUse) {
                Row row = text(rows, "IN USE  " + r.protocol + " " + r.endpoint() + String.format(Locale.ROOT,
                        "  %.0f pkt/s", r.packetsOutPerSec + r.packetsInPerSec) + (r.primary ? "  (primary)" : ""),
                        Theme.ACCENT);
                row.tip = relayTip(r);
            }
            if (!probing.isEmpty()) {
                Row row = text(rows, "ExitLag is also measuring " + probing.size()
                        + " other relay(s) with short probes (not routes)", Theme.TEXT_FAINT);
                row.tip = listTip("Measured, not in use:", probing);
            }
            if (!idle.isEmpty()) {
                Row row = text(rows, "ExitLag holds " + idle.size() + " idle TCP connection(s) (no traffic captured)",
                        Theme.TEXT_FAINT);
                row.tip = listTip("Idle ExitLag connections (unverified):", idle);
            }
            for (Snapshot.Relay r : inUse) {
                Snapshot.Path p = s.path(r.pathKey);
                if (p == null) {
                    text(rows, "Path to relay " + r.endpoint() + ": waiting to trace...", Theme.TEXT_DIM);
                } else {
                    pathRows(rows, p, w);
                }
            }
        }
        for (Snapshot.Path p : s.paths) {
            if ("server".equals(p.kind) && (tr == null || p.target.equals(tr.destination))) {
                pathRows(rows, p, w);
            }
        }
        if (!s.events.isEmpty()) {
            text(rows, "", Theme.TEXT);
            text(rows, "Route changes", Theme.TEXT);
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
            for (int i = s.events.size() - 1, n = 0; i >= 0 && n < 8; i--, n++) {
                Snapshot.Event e = s.events.get(i);
                text(rows, f.format(new Date(e.time)) + "  " + e.text, Theme.TEXT_DIM);
            }
        }
    }

    private void pathRows(List<Row> rows, Snapshot.Path p, int w) {
        boolean relay = "relay".equals(p.kind);
        String state = "running".equals(p.state) ? " (tracing...)" : p.reached ? ", reached" : "";
        text(rows, "", Theme.TEXT);
        Row head = text(rows, (relay ? "Path to ExitLag relay " : "Path to server ") + p.target + ":" + p.port + " via "
                + p.protocol + state, relay ? Theme.ACCENT : Theme.TEXT);
        head.tip = fontRendererObj.listFormattedStringToWidth("Measured " + p.traces + " time(s), last at "
                + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(p.updatedAt)) + ". " + p.protocol
                + " probes with rising TTL; loss is over 3 probes per hop. Loss at one router while later hops are "
                + "fine is ICMP rate limiting, not real loss.", 240).toArray(new String[0]);
        String prevAsn = null;
        for (Snapshot.Hop hop : p.hops) {
            AsnLookup.Info owner = AsnLookup.get(hop.address);
            Row r = new Row();
            r.hop = true;
            r.num = String.valueOf(hop.ttl);
            r.addr = hop.address;
            r.color = hop.destination ? Theme.ACCENT : Theme.TEXT;
            r.ms = hop.avg;
            r.lossPct = hop.lossPercent();
            r.loss = hop.sent == 0 ? null : String.format(Locale.ROOT, "%.0f%%", r.lossPct);
            if (owner != null) {
                r.owner = owner.label;
                r.boundary = owner.asn != null && !owner.asn.equals(prevAsn);
                if (owner.asn != null) {
                    prevAsn = owner.asn;
                }
            }
            List<String> tip = new ArrayList<String>();
            tip.add("Hop " + hop.ttl + (hop.destination ? " (target)" : "") + ": " + hop.received + "/" + hop.sent
                    + " replies" + (hop.received == 0 ? "" : String.format(Locale.ROOT, ", %s / %s / %s ms min/avg/max",
                    ms(hop.min), ms(hop.avg), ms(hop.max))));
            if (hop.others != null && !hop.others.isEmpty()) {
                tip.addAll(fontRendererObj.listFormattedStringToWidth("Also replied (load balancing): " + hop.others,
                        220));
            }
            if (owner != null) {
                tip.addAll(ownerLines(owner));
            }
            r.tip = tip.toArray(new String[0]);
            rows.add(r);
        }
        if (relay) {
            text(rows, "    beyond the relay: inside ExitLag's tunnel, not visible", Theme.TEXT_FAINT);
        }
        if (p.note != null && !p.note.isEmpty()) {
            wrap(rows, p.note, Theme.TEXT_DIM, w);
        }
    }

    private void icmpRows(List<Row> rows, Traceroute tr, int w) {
        text(rows, "", Theme.TEXT);
        String cmp = tr.pathComparison();
        text(rows, "ICMP hops to the server (system traceroute, not ExitLag's route)", Theme.TEXT);
        if (cmp != null) {
            wrap(rows, cmp, Theme.TEXT_DIM, w);
        }
        if (tr.error() != null) {
            wrap(rows, tr.error(), Theme.BAD, w);
            return;
        }
        List<Traceroute.Hop> hops = tr.hops();
        String prevAsn = null;
        for (Traceroute.Hop hop : hops) {
            AsnLookup.Info owner = AsnLookup.get(hop.address);
            Row r = new Row();
            r.hop = true;
            r.num = String.valueOf(hop.number);
            r.addr = hop.address;
            r.ms = hop.best();
            if (owner != null) {
                r.owner = owner.label;
                r.boundary = owner.asn != null && !owner.asn.equals(prevAsn);
                if (owner.asn != null) {
                    prevAsn = owner.asn;
                }
            }
            StringBuilder sb = new StringBuilder("Hop " + hop.number + ":");
            for (double v : hop.rtts) {
                sb.append(Double.isNaN(v) ? "  *" : "  " + ms(v) + " ms");
            }
            List<String> tip = new ArrayList<String>();
            tip.add(sb.toString());
            if (owner != null) {
                tip.addAll(ownerLines(owner));
            }
            r.tip = tip.toArray(new String[0]);
            rows.add(r);
        }
        if (hops.isEmpty() && tr.icmpRunning()) {
            text(rows, "waiting for first hop...", Theme.TEXT_FAINT);
        }
    }

    private static String[] relayTip(Snapshot.Relay r) {
        List<String> t = new ArrayList<String>();
        t.add(r.protocol + " " + r.endpoint() + (r.primary ? " (primary)" : ""));
        t.add("Owner: " + r.process + " (pid " + r.pid + ")");
        t.add(String.format(Locale.ROOT, "Out %.1f pkt/s, in %.1f pkt/s, %.1f KB/s", r.packetsOutPerSec,
                r.packetsInPerSec, r.bytesPerSec / 1024));
        t.add("Traffic for " + r.activeSeconds + " s, last packet " + (r.lastSeenAgoMs < 0 ? "never"
                : (r.lastSeenAgoMs / 1000) + " s ago"));
        t.add("Verified: traffic was captured on a socket owned by ExitLag.");
        return t.toArray(new String[0]);
    }

    private static String[] listTip(String title, List<String> items) {
        List<String> t = new ArrayList<String>();
        t.add(title);
        for (int i = 0; i < items.size() && i < 15; i++) {
            t.add("  " + items.get(i));
        }
        if (items.size() > 15) {
            t.add("  (+" + (items.size() - 15) + " more)");
        }
        return t.toArray(new String[0]);
    }

    private static Row text(List<Row> rows, String text, int color) {
        Row r = new Row();
        r.text = text;
        r.color = color;
        rows.add(r);
        return r;
    }

    private void wrap(List<Row> rows, String text, int color, int w) {
        for (String line : fontRendererObj.listFormattedStringToWidth(text, w)) {
            text(rows, line, color);
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
        AsnLookup.Info server = AsnLookup.get(m.address);
        if (server != null) {
            sb.append("Server network: ").append(ownerText(server)).append('\n');
        }
        ProxyDetector.Result px = proxy(m);
        if (px.provider != null) {
            sb.append("Using proxy: ").append(px.provider).append(" (").append(px.evidence).append(")\n");
        }
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
            int tab = NetDiag.tabPing();
            sb.append("Tab-list ping: ").append(tab == 0 ? TAB_ZERO : tab + " ms").append('\n');
        }
        Traceroute tr = NetDiag.traceroute();
        if (tr != null && tr.tcp() != null) {
            Traceroute.TcpResult t = tr.tcp();
            sb.append("TCP check to ").append(tr.destination).append(':').append(tr.port).append(": ")
                    .append(t.verdict).append(" - ").append(t.detail).append('\n');
        }
        Snapshot adv = AdvancedTracing.client().snapshot();
        if (adv != null) {
            sb.append("Advanced tracing: ").append(adv.status).append('\n');
            for (Snapshot.Relay r : adv.relays) {
                if (r.inUse) {
                    sb.append(String.format(Locale.ROOT, "ExitLag relay in use: %s %s, %.0f pkt/s%s%n", r.protocol,
                            r.endpoint(), r.packetsOutPerSec + r.packetsInPerSec, r.primary ? " (primary)" : ""));
                }
            }
            for (Snapshot.Path ap : adv.paths) {
                sb.append("Path to ").append(ap.kind).append(' ').append(ap.target).append(':').append(ap.port)
                        .append(" via ").append(ap.protocol).append(": ").append(ap.note).append('\n');
                for (Snapshot.Hop h : ap.hops) {
                    sb.append(String.format(Locale.ROOT, "%3d  %-40s %-8s loss %3.0f%%%n", h.ttl,
                            h.address == null ? "*" : h.address, Double.isNaN(h.avg) ? "*" : ms(h.avg) + " ms",
                            h.lossPercent()));
                }
                if ("relay".equals(ap.kind)) {
                    sb.append("     (beyond the relay: inside ExitLag's tunnel, not visible)\n");
                }
            }
            for (Snapshot.Event e : adv.events) {
                sb.append("Event: ").append(e.text).append('\n');
            }
        }
        if (tr != null && !tr.hops().isEmpty()) {
            String cmp = tr.pathComparison();
            sb.append("ICMP traceroute to ").append(tr.destination).append(" (system route, not ExitLag's)")
                    .append(cmp == null ? "" : ". " + cmp).append(":\n");
            for (Traceroute.Hop h : tr.hops()) {
                double b = h.best();
                AsnLookup.Info o = AsnLookup.get(h.address);
                sb.append(String.format(Locale.ROOT, "%3d  %-40s %-8s %s%n", h.number,
                        h.address == null ? "*" : h.address, Double.isNaN(b) ? "*" : ms(b) + " ms",
                        o == null ? "" : ownerText(o)));
            }
        }
        return sb.toString();
    }

    /** One line: "AS13335 CLOUDFLARENET - Cloudflare, Inc., US (104.16.0.0/13)" or the status text. */
    private static ProxyDetector.Result proxy(PingMonitor m) {
        return ProxyDetector.get(m.target.host, m.connectHost, m.address);
    }

    private static String ownerText(AsnLookup.Info o) {
        if (AsnLookup.pending(o)) {
            return "looking up...";
        }
        if (o.asn == null) {
            return o.name;
        }
        return o.asn + (o.name == null ? "" : " " + o.name) + (o.prefix == null ? "" : " (" + o.prefix + ")");
    }

    private List<String> ownerLines(AsnLookup.Info o) {
        List<String> out = new ArrayList<String>();
        if (AsnLookup.pending(o)) {
            out.add("Owner: looking up...");
        } else if (o.asn == null) {
            out.add("Owner: " + o.name);
        } else {
            out.addAll(fontRendererObj.listFormattedStringToWidth("Owner: " + o.asn
                    + (o.name == null ? "" : " " + o.name), 220));
            if (o.prefix != null) {
                out.add("Prefix: " + o.prefix + (o.country == null ? "" : "  (" + o.country + ")"));
            }
        }
        return out;
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
}
