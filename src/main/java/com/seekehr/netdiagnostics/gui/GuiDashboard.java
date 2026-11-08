package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.monitor.LatencyMonitor;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.quality.Alert;
import com.seekehr.netdiagnostics.core.quality.QualityEngine;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.ResultStore;
import com.seekehr.netdiagnostics.core.test.TestRun;
import com.seekehr.netdiagnostics.mod.ClientEvents;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Main dashboard opened by /seekernetmod, /snm and /ns. */
public final class GuiDashboard extends GuiBase {
    private static final int ID_TEST_MINE = 1;
    private static final int ID_TEST_IP = 2;
    private static final int ID_COMPARE = 3;
    private static final int ID_ADVANCED = 10;
    private static final int ID_TAB = 11; // 11..13
    private static final int ID_WINDOW = 20; // 20..24
    private static final int ID_SORT = 30;
    private static final int ID_ORDER = 31;
    private static final int ID_RETEST = 40;
    private static final int ID_COPY_IP = 41;
    private static final int ID_COMPARE_SEL = 42;
    private static final int ID_DETAILS = 43;
    private static final int ID_EXPORT = 44;
    private static final int ID_CLEAR = 45;
    private static final int ID_CLEAR_ALERTS = 50;
    private static final int ID_ADV_TCP = 60;
    private static final int ID_ADV_ROUTE = 61;
    private static final int ID_ADV_MONITOR = 62;
    private static final int ID_ADV_OVERLAY = 63;
    private static final int ID_ADV_EXPORT = 64;
    private static final int ID_ADV_SETTINGS = 65;

    private static final String[] TABS = {"Overview", "Results", "Alerts"};

    // persistent UI state across re-layouts / reopenings
    private static int tab;
    private static int windowIdx = 1;
    private static boolean advancedOpen;
    private static ResultStore.SortKey sortKey = ResultStore.SortKey.DATE;
    private static boolean ascending;
    private static String search = "";

    private final LatencyGraph graph = new LatencyGraph();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private GuiTextField searchField;
    private FlatButton clearBtn;
    private long clearArmedUntil;
    private int contentTop;
    private int contentBottom;

    // cached data, refreshed every settings.dashboardRefreshMs
    private long lastRefresh;
    private StatSummary windowStats = StatSummary.EMPTY;
    private StatSummary kaSpacing = StatSummary.EMPTY;
    private StatSummary kaTurn = StatSummary.EMPTY;
    private int livePing = -1;
    private List<TestRun> rows = new ArrayList<TestRun>();
    private int rowsVersion = -1;
    private String rowsQuery = null;
    private TestRun selected;
    private int scroll;
    private int alertScroll;
    private List<Alert> alerts = new ArrayList<Alert>();
    private int alertsVersion = -1;

    public GuiDashboard(GuiScreen parent) {
        super(parent, "SeekerNetDiagnostics");
    }

    @Override
    protected String subtitle() {
        return "network diagnostics from this Minecraft process";
    }

    @Override
    protected void layout() {
        int pad = 8;
        button(ID_ADVANCED, width - pad - 92, 5, 92, 14, advancedOpen ? "Advanced Tools ▴" : "Advanced Tools ▾",
                advancedOpen ? FlatButton.Style.TAB_ACTIVE : FlatButton.Style.NORMAL)
                .tip("Show or hide advanced tools (A)");

        int aw = (width - pad * 2 - 12) / 3;
        int ay = 30;
        button(ID_TEST_MINE, pad, ay, aw, 30, "Test My Connection", FlatButton.Style.PRIMARY)
                .sub("current server").tip("Measures TCP handshakes and a Minecraft status ping to the server you "
                        + "are connected to, and starts the live monitor. (1)");
        button(ID_TEST_IP, pad + aw + 6, ay, aw, 30, "Test an IP", FlatButton.Style.PRIMARY)
                .sub("any host and port").tip("Enter an IP address or host name and measure it. (2)");
        button(ID_COMPARE, pad + (aw + 6) * 2, ay, width - pad * 2 - (aw + 6) * 2, 30, "Compare ExitLag Routes",
                FlatButton.Style.PRIMARY).sub("A/B route profiles")
                .tip("Measure the same target under different ExitLag settings and compare them. (3)");

        int y = ay + 36;
        if (advancedOpen) {
            String[] labels = {"Advanced TCP", "Route Analysis",
                    "Monitor: " + (nd.monitor.active() ? "On" : "Off"),
                    "Overlay: " + (nd.settings().overlayVisible ? "On" : "Off"), "Export Report", "Settings"};
            String[] tips = {"DNS, local/remote socket details and per-attempt results of the latest test.",
                    "Traceroute limitations and target export for external tools.",
                    "Start or stop the passive live monitor (M). It sends no network traffic.",
                    "Show a small latency overlay while playing.",
                    "Write JSON and CSV reports to config/netscope/reports/ (E).",
                    "Samples, intervals, timeouts and display options."};
            int bw = (width - pad * 2 - 5 * 4) / 6;
            for (int i = 0; i < labels.length; i++) {
                button(ID_ADV_TCP + i, pad + i * (bw + 4), y, i == 5 ? width - pad * 2 - 5 * (bw + 4) : bw, 16,
                        labels[i], FlatButton.Style.NORMAL).tip(tips[i]);
            }
            y += 22;
        }
        for (int i = 0; i < TABS.length; i++) {
            button(ID_TAB + i, pad + i * 66, y, 64, 15, TABS[i], i == tab ? FlatButton.Style.TAB_ACTIVE
                    : FlatButton.Style.TAB).tip("Switch tab (Left/Right arrow)");
        }
        contentTop = y + 19;
        contentBottom = height - 16;

        if (tab == 0) {
            int lw = leftWidth();
            int gx = pad + lw + 6;
            int gw = width - pad - gx;
            int bx = gx + gw - 5 * 26 - 4;
            for (int i = 0; i < LatencyGraph.WINDOW_LABELS.length; i++) {
                button(ID_WINDOW + i, bx + i * 26, contentTop + 4, 24, 12, LatencyGraph.WINDOW_LABELS[i],
                        i == windowIdx ? FlatButton.Style.TAB_ACTIVE : FlatButton.Style.GHOST);
            }
        } else if (tab == 1) {
            searchField = field(100, pad, contentTop, Math.min(180, width / 3), search, "Search host, IP, profile...", 64);
            int sx = pad + Math.min(180, width / 3) + 6;
            button(ID_SORT, sx, contentTop, 74, 18, "Sort: " + sortKey.label, FlatButton.Style.NORMAL)
                    .tip("Change the sort column");
            button(ID_ORDER, sx + 78, contentTop, 40, 18, ascending ? "Asc" : "Desc", FlatButton.Style.NORMAL)
                    .tip("Toggle ascending / descending");
            String[] qa = {"Retest", "Copy IP", "Compare", "Details", "Export", "Clear History"};
            int qw = (width - pad * 2 - 5 * 4) / 6;
            int qy = contentBottom - 18;
            for (int i = 0; i < qa.length; i++) {
                FlatButton b = button(ID_RETEST + i, pad + i * (qw + 4), qy, i == 5 ? width - pad * 2 - 5 * (qw + 4) : qw,
                        16, qa[i], i == 5 ? FlatButton.Style.DANGER : FlatButton.Style.NORMAL);
                if (i == 5) {
                    clearBtn = b;
                }
            }
            updateRowButtons();
        } else {
            button(ID_CLEAR_ALERTS, width - pad - 80, contentTop, 80, 14, "Clear alerts", FlatButton.Style.NORMAL);
        }
    }

    private int leftWidth() {
        return Math.max(150, Math.min(220, (int) ((width - 16) * 0.36)));
    }

    private void updateRowButtons() {
        for (GuiButton b : buttonList) {
            if (b.id >= ID_RETEST && b.id <= ID_DETAILS) {
                b.enabled = selected != null;
            }
            if (b.id == ID_COMPARE_SEL) {
                b.enabled = selected != null;
            }
        }
    }

    // ------------------------------------------------------------------ data refresh

    private void refresh(long now) {
        if (now - lastRefresh < nd.settings().dashboardRefreshMs) {
            return;
        }
        lastRefresh = now;
        LatencyMonitor m = nd.monitor;
        long w = LatencyGraph.WINDOWS_MS[windowIdx];
        windowStats = m.summary(w > 0 ? now - w : 0);
        kaSpacing = m.keepAliveSpacingSummary();
        kaTurn = m.keepAliveTurnaroundSummary();
        livePing = ClientEvents.ownReportedLatency(mc);
        String q = search + "|" + sortKey + "|" + ascending;
        if (rowsVersion != nd.results.version() || !q.equals(rowsQuery)) {
            rows = nd.results.query(search, sortKey, ascending);
            rowsVersion = nd.results.version();
            rowsQuery = q;
            if (selected != null && !rows.contains(selected)) {
                selected = null;
                updateRowButtons();
            }
        }
        if (alertsVersion != nd.quality.version()) {
            alerts = nd.quality.alerts();
            alertsVersion = nd.quality.version();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        long now = System.currentTimeMillis();
        refresh(now);
        // monitor chip
        boolean active = nd.monitor.active();
        String chip = active ? "● Monitoring" : "○ Monitor off";
        Draw.textRight(fontRendererObj, chip, width - 8 - 92 - 8, 8, active ? Theme.GOOD : Theme.TEXT_FAINT);

        if (tab == 0) {
            drawOverview(mouseX, mouseY, now);
        } else if (tab == 1) {
            drawResults(mouseX, mouseY);
        } else {
            drawAlerts();
        }
        Draw.text(fontRendererObj, "1/2/3 actions · ←/→ tabs · Tab focus · A advanced · "
                + "M monitor · E export · Esc close", 8, height - 11, Theme.TEXT_FAINT);
    }

    private List<String> pendingTip;

    private void drawOverview(int mouseX, int mouseY, long now) {
        int pad = 8;
        int lw = leftWidth();
        int top = contentTop;
        int h = contentBottom - top;
        LatencyMonitor m = nd.monitor;
        Draw.card(pad, top, lw, h, Theme.CARD, Theme.BORDER);
        int x = pad + 8;
        int y = top + 7;
        Draw.text(fontRendererObj, "CONNECTION", x, y, Theme.TEXT_FAINT);
        y += 13;
        boolean connected = NetDiag.currentServer() != null;
        int vw = lw - 16;
        y = kv("Server", connected ? NetDiag.currentServer().serverIP : (mc.isIntegratedServerRunning()
                ? "Singleplayer (not monitored)" : "Not connected"), x, y, vw, Theme.TEXT);
        String remote = connected ? NetDiag.currentRemoteAddress() : null;
        y = kv("Endpoint", remote == null ? "-" : remote, x, y, vw, Theme.TEXT);
        y = kv("TCP state", connected ? (ClientEvents.channelOpen() ? "Established" : "Closed") : "-", x, y, vw,
                connected && ClientEvents.channelOpen() ? Theme.GOOD : Theme.TEXT_DIM);
        y = kv("Session", m.sessionStart() > 0 && connected ? QualityEngine.duration(now - m.sessionStart()) : "-", x, y,
                vw, Theme.TEXT);
        y += 3;
        Draw.text(fontRendererObj, "Live ping", x, y + 2, Theme.TEXT_DIM);
        String live = livePing >= 0 && connected ? livePing + " ms" : "n/a";
        Draw.textScaled(fontRendererObj, live, x + vw - fontRendererObj.getStringWidth(live) * 1.5f, y, 1.5f,
                Theme.latencyColor(livePing >= 0 && connected ? livePing : Double.NaN));
        y += 16;
        StatSummary s = windowStats;
        y = kv("Min / Avg / Max", s.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f / %.0f / %.0f ms", s.min, s.mean,
                s.max), x, y, vw, Theme.TEXT);
        y = kv("p50 / p95 / p99", s.isEmpty() ? "-" : String.format(Locale.ROOT, "%.0f / %.0f / %.0f ms", s.median,
                s.p95, s.p99), x, y, vw, Theme.TEXT);
        y = kv("Jitter", s.count < 2 ? "-" : String.format(Locale.ROOT, "%.1f ms", s.jitter), x, y, vw, Theme.TEXT);
        y = kv("Samples", String.valueOf(s.count), x, y, vw, Theme.TEXT);
        y = kv("Disconnects", String.valueOf(m.disconnects()), x, y, vw, m.disconnects() > 0 ? Theme.WARN : Theme.TEXT);
        y = kv("Monitoring", (m.active() ? "Active" : "Stopped") + (nd.settings().packetInstrumentation
                ? " + packet observer" : ""), x, y, vw, m.active() ? Theme.GOOD : Theme.TEXT_DIM);
        int noteY = Math.max(y + 4, contentBottom - 6 - Draw.wrappedHeight(fontRendererObj, NOTE, vw, 4));
        if (noteY + 10 < contentBottom) {
            Draw.wrapped(fontRendererObj, NOTE, x, noteY, vw, Theme.TEXT_FAINT, (contentBottom - noteY - 4) / 9);
        }

        // graph card
        int gx = pad + lw + 6;
        int gw = width - pad - gx;
        Draw.card(gx, top, gw, h, Theme.CARD, Theme.BORDER);
        Draw.text(fontRendererObj, "Minecraft-reported latency", gx + 8, top + 7, Theme.TEXT);
        int footer = nd.settings().packetInstrumentation ? 24 : 14;
        pendingTip = graph.draw(fontRendererObj, m.reported(), gx + 6, top + 22, gw - 14, h - 26 - footer,
                LatencyGraph.WINDOWS_MS[windowIdx], now, mouseX, mouseY, "ms");
        int fy = top + h - footer;
        drawQualityStrip(gx + 8, fy, gw - 16);
        if (nd.settings().packetInstrumentation) {
            String ka = kaSpacing.isEmpty() ? "Keepalive observer: waiting for keepalives"
                    : String.format(Locale.ROOT, "Keepalive spacing: median %.2f s, variation %.0f ms · client "
                    + "turnaround %s · gaps %d", kaSpacing.median / 1000.0, kaSpacing.jitter,
                    kaTurn.isEmpty() ? "n/a" : String.format(Locale.ROOT, "%.2f ms", kaTurn.median), m.keepAliveGaps());
            Draw.textClipped(fontRendererObj, ka, gx + 8, fy + 10, gw - 16, Theme.TEXT_DIM);
        }
    }

    private static final String NOTE = "Live ping is the latency Minecraft shows in the tab list. The server "
            + "computes and smooths it from keepalives and refreshes it periodically, so it is application-level "
            + "and is not instantaneous network RTT.";

    private void drawQualityStrip(int x, int y, int w) {
        List<LatencyMonitor.QualitySnapshot> q = nd.monitor.qualityHistory();
        Draw.text(fontRendererObj, "Quality/min", x, y, Theme.TEXT_FAINT);
        int sx = x + fontRendererObj.getStringWidth("Quality/min") + 6;
        int cells = Math.max(1, (x + w - sx) / 5);
        int start = Math.max(0, q.size() - cells);
        if (q.isEmpty()) {
            Draw.text(fontRendererObj, "first rating after 1 minute of monitoring", sx, y, Theme.TEXT_FAINT);
        }
        for (int i = start; i < q.size(); i++) {
            int g = q.get(i).grade;
            int c = g < 0 ? Theme.BORDER : g == 0 ? Theme.GOOD : g == 1 ? Theme.WARN : Theme.BAD;
            Draw.roundRect(sx + (i - start) * 5, y, 4, 7, 1, c);
        }
    }

    private int kv(String k, String v, int x, int y, int w, int color) {
        Draw.text(fontRendererObj, k, x, y, Theme.TEXT_DIM);
        int kw = fontRendererObj.getStringWidth(k) + 6;
        Draw.textRight(fontRendererObj, Draw.clip(fontRendererObj, v, w - kw), x + w, y, color);
        return y + 11;
    }

    // ------------------------------------------------------------------ results tab

    private static final String[] COLS = {"Host / IP", "Port", "Type", "OK", "Fail", "Median", "p95", "Last test"};

    private int[] columns(int x, int w) {
        int[] fixed = {0, 34, 64, 24, 26, 42, 42, 60};
        int sum = 0;
        for (int i = 1; i < fixed.length; i++) {
            sum += fixed[i];
        }
        int[] cx = new int[COLS.length + 1];
        cx[0] = x;
        cx[1] = x + Math.max(60, w - sum);
        for (int i = 2; i <= COLS.length; i++) {
            cx[i] = cx[i - 1] + fixed[i - 1];
        }
        return cx;
    }

    private int rowsTop() {
        return contentTop + 36;
    }

    private int rowsBottom() {
        return contentBottom - 24;
    }

    private void drawResults(int mouseX, int mouseY) {
        int pad = 8;
        search = searchField.getText();
        int top = contentTop + 22;
        Draw.card(pad, top, width - pad * 2, contentBottom - 22 - top, Theme.CARD, Theme.BORDER);
        int[] cx = columns(pad + 6, width - pad * 2 - 12);
        for (int i = 0; i < COLS.length; i++) {
            Draw.text(fontRendererObj, COLS[i], cx[i], top + 4, Theme.TEXT_FAINT);
        }
        Draw.rect(pad + 4, top + 13, width - pad - 4, top + 14, Theme.BORDER);
        int ry = rowsTop();
        int visible = Math.max(1, (rowsBottom() - ry) / 12);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        if (rows.isEmpty()) {
            Draw.textCentered(fontRendererObj, search.isEmpty() ? "No tests yet. Click \"Test an IP\" to run one."
                    : "No results match \"" + search + "\".", width / 2f, ry + 10, Theme.TEXT_DIM);
        }
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            TestRun r = rows.get(i + scroll);
            int y = ry + i * 12;
            boolean hover = mouseX >= pad + 4 && mouseX < width - pad - 4 && mouseY >= y - 2 && mouseY < y + 10;
            if (r == selected) {
                Draw.roundRect(pad + 3, y - 2, width - pad * 2 - 6, 12, 2, Theme.withAlpha(Theme.ACCENT_DARK, 0.6f));
            } else if (hover) {
                Draw.roundRect(pad + 3, y - 2, width - pad * 2 - 6, 12, 2, Theme.BUTTON_HOVER);
            }
            StatSummary s = r.summary();
            String host = r.target.host + (r.profileLabel != null ? "  [" + r.profileLabel + "]" : "");
            Draw.textClipped(fontRendererObj, host, cx[0], y, cx[1] - cx[0] - 4, Theme.TEXT);
            Draw.text(fontRendererObj, String.valueOf(r.target.port), cx[1], y, Theme.TEXT_DIM);
            Draw.textClipped(fontRendererObj, r.kind == TestRun.Kind.FULL ? "TCP+Status" : "TCP", cx[2], y,
                    cx[3] - cx[2] - 4, Theme.TEXT_DIM);
            Draw.text(fontRendererObj, String.valueOf(s.count), cx[3], y, s.count > 0 ? Theme.GOOD : Theme.TEXT_DIM);
            int fails = r.failedAttempts();
            Draw.text(fontRendererObj, String.valueOf(fails), cx[4], y, fails > 0 ? Theme.BAD : Theme.TEXT_DIM);
            Draw.text(fontRendererObj, s.isEmpty() ? "-" : Conclusions.fmt(s.median), cx[5], y,
                    Theme.latencyColor(s.median));
            Draw.text(fontRendererObj, s.isEmpty() ? "-" : Conclusions.fmt(s.p95), cx[6], y, Theme.TEXT);
            Draw.text(fontRendererObj, r.startedAt > 0 ? dateFmt.format(new Date(r.startedAt)) : "-", cx[7], y,
                    Theme.TEXT_DIM);
        }
        if (rows.size() > visible) {
            Draw.textRight(fontRendererObj, (scroll + 1) + "-" + Math.min(rows.size(), scroll + visible) + " of "
                    + rows.size(), width - pad - 6, contentTop + 5, Theme.TEXT_FAINT);
        }
        if (clearBtn != null) {
            boolean armed = System.currentTimeMillis() < clearArmedUntil;
            clearBtn.displayString = armed ? "Click to confirm" : "Clear History";
        }
    }

    // ------------------------------------------------------------------ alerts tab

    private void drawAlerts() {
        int pad = 8;
        int top = contentTop + 18;
        Draw.card(pad, top, width - pad * 2, contentBottom - top, Theme.CARD, Theme.BORDER);
        Draw.text(fontRendererObj, "Stability alerts identify the metric that triggered them. They describe "
                + "measurements only.", pad, contentTop + 3, Theme.TEXT_DIM);
        if (alerts.isEmpty()) {
            Draw.textCentered(fontRendererObj, "No alerts. Alerts appear when monitored latency or test results "
                    + "change notably.", width / 2f, top + 14, Theme.TEXT_DIM);
            return;
        }
        int y = top + 6;
        int w = width - pad * 2 - 16;
        alertScroll = Math.max(0, Math.min(alertScroll, alerts.size() - 1));
        for (int i = alertScroll; i < alerts.size(); i++) {
            Alert a = alerts.get(i);
            int h = 10 + Draw.wrappedHeight(fontRendererObj, a.message, w - 4, 3);
            if (y + h > contentBottom - 4) {
                break;
            }
            Draw.text(fontRendererObj, timeFmt.format(new Date(a.timestamp)), pad + 8, y, Theme.TEXT_FAINT);
            Draw.text(fontRendererObj, a.metric, pad + 58, y, a.severity == Alert.Severity.WARNING ? Theme.WARN
                    : Theme.ACCENT);
            Draw.wrapped(fontRendererObj, a.message, pad + 12, y + 10, w - 4, Theme.TEXT, 3);
            y += h + 5;
        }
    }

    @Override
    protected void drawOverlay(int mouseX, int mouseY) {
        if (tab == 0 && pendingTip != null) {
            Draw.tooltip(fontRendererObj, pendingTip, mouseX, mouseY, width, height);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        int id = b.id;
        if (id == ID_TEST_MINE) {
            testMyConnection();
        } else if (id == ID_TEST_IP) {
            mc.displayGuiScreen(new GuiTestTarget(this, "", "", false));
        } else if (id == ID_COMPARE) {
            mc.displayGuiScreen(new GuiCompare(this));
        } else if (id == ID_ADVANCED) {
            advancedOpen = !advancedOpen;
            initGui();
        } else if (id >= ID_TAB && id < ID_TAB + TABS.length) {
            tab = id - ID_TAB;
            initGui();
        } else if (id >= ID_WINDOW && id < ID_WINDOW + 5) {
            windowIdx = id - ID_WINDOW;
            lastRefresh = 0;
            initGui();
        } else if (id == ID_SORT) {
            ResultStore.SortKey[] keys = ResultStore.SortKey.values();
            sortKey = keys[(sortKey.ordinal() + 1) % keys.length];
            b.displayString = "Sort: " + sortKey.label;
            lastRefresh = 0;
        } else if (id == ID_ORDER) {
            ascending = !ascending;
            b.displayString = ascending ? "Asc" : "Desc";
            lastRefresh = 0;
        } else if (id == ID_RETEST && selected != null) {
            mc.displayGuiScreen(new GuiTestTarget(this, selected.target.host,
                    selected.target.portExplicit ? String.valueOf(selected.target.port) : "", true)
                    .withKind(selected.kind));
        } else if (id == ID_COPY_IP && selected != null) {
            String ip = selected.resolvedAddress != null ? selected.resolvedAddress : selected.target.host;
            copy(ip, ip);
        } else if (id == ID_COMPARE_SEL && selected != null) {
            mc.displayGuiScreen(new GuiCompare(this).withTarget(selected.target));
        } else if (id == ID_DETAILS && selected != null) {
            mc.displayGuiScreen(new GuiAdvancedTcp(this, selected));
        } else if (id == ID_EXPORT || id == ID_ADV_EXPORT) {
            export();
        } else if (id == ID_CLEAR) {
            if (System.currentTimeMillis() < clearArmedUntil) {
                nd.clearHistory();
                selected = null;
                clearArmedUntil = 0;
                lastRefresh = 0;
                updateRowButtons();
                toast("Test history cleared", Theme.WARN);
            } else {
                clearArmedUntil = System.currentTimeMillis() + 3000;
            }
        } else if (id == ID_CLEAR_ALERTS) {
            nd.quality.clear();
            lastRefresh = 0;
        } else if (id == ID_ADV_TCP) {
            TestRun latest = selected != null ? selected : nd.results.latest();
            mc.displayGuiScreen(new GuiAdvancedTcp(this, latest));
        } else if (id == ID_ADV_ROUTE) {
            mc.displayGuiScreen(new GuiRouteAnalysis(this));
        } else if (id == ID_ADV_MONITOR) {
            toggleMonitor();
        } else if (id == ID_ADV_OVERLAY) {
            com.seekehr.netdiagnostics.core.config.Settings s = nd.settings().copy();
            s.overlayVisible = !s.overlayVisible;
            nd.applySettings(s);
            initGui();
        } else if (id == ID_ADV_SETTINGS) {
            mc.displayGuiScreen(new GuiSettings(this));
        }
    }

    private void toggleMonitor() {
        if (nd.monitor.active()) {
            nd.monitor.stop();
            toast("Live monitor stopped", Theme.WARN);
        } else {
            nd.monitor.start(System.currentTimeMillis());
            toast("Live monitor started (passive, no extra traffic)", Theme.GOOD);
        }
        initGui();
    }

    private void testMyConnection() {
        Target t = NetDiag.currentServerTarget();
        if (t == null) {
            mc.displayGuiScreen(new GuiTestTarget(this, "", "", false).withNotice(mc.isIntegratedServerRunning()
                    ? "You are in singleplayer. Join a multiplayer server to test your connection, or enter an IP."
                    : "You are not connected to a multiplayer server. Enter an IP or host to test."));
            return;
        }
        nd.monitor.start(System.currentTimeMillis());
        mc.displayGuiScreen(new GuiTestTarget(this, t.host, t.portExplicit ? String.valueOf(t.port) : "", true)
                .withNotice("Testing the server you are connected to. The live monitor is running."));
    }

    private void export() {
        toast("Exporting...", Theme.ACCENT);
        nd.exportAll(new NetDiag.ExportCallback() {
            @Override
            public void done(List<File> files, String error) {
                if (error != null) {
                    toast("Export failed: " + error, Theme.BAD);
                } else {
                    toast("Exported " + files.size() + " file(s) to config/netscope/reports/", Theme.GOOD);
                }
            }
        });
    }

    @Override
    protected boolean onKey(char c, int key) {
        if (anyFieldFocused()) {
            return false;
        }
        try {
            switch (key) {
                case Keyboard.KEY_1:
                    actionPerformed(byId(ID_TEST_MINE));
                    return true;
                case Keyboard.KEY_2:
                    actionPerformed(byId(ID_TEST_IP));
                    return true;
                case Keyboard.KEY_3:
                    actionPerformed(byId(ID_COMPARE));
                    return true;
                case Keyboard.KEY_A:
                    advancedOpen = !advancedOpen;
                    initGui();
                    return true;
                case Keyboard.KEY_M:
                    toggleMonitor();
                    return true;
                case Keyboard.KEY_E:
                    export();
                    return true;
                case Keyboard.KEY_LEFT:
                    tab = (tab + TABS.length - 1) % TABS.length;
                    initGui();
                    return true;
                case Keyboard.KEY_RIGHT:
                    tab = (tab + 1) % TABS.length;
                    initGui();
                    return true;
                case Keyboard.KEY_F:
                    if (isCtrlKeyDown() && tab == 1 && searchField != null) {
                        searchField.setFocused(true);
                        return true;
                    }
                    return false;
                case Keyboard.KEY_UP:
                    moveSelection(-1);
                    return true;
                case Keyboard.KEY_DOWN:
                    moveSelection(1);
                    return true;
                default:
                    return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private void moveSelection(int d) {
        if (tab != 1 || rows.isEmpty()) {
            return;
        }
        int idx = selected == null ? (d > 0 ? -1 : rows.size()) : rows.indexOf(selected);
        idx = Math.max(0, Math.min(rows.size() - 1, idx + d));
        selected = rows.get(idx);
        int visible = Math.max(1, (rowsBottom() - rowsTop()) / 12);
        if (idx < scroll) {
            scroll = idx;
        } else if (idx >= scroll + visible) {
            scroll = idx - visible + 1;
        }
        updateRowButtons();
    }

    private GuiButton byId(int id) {
        for (GuiButton b : buttonList) {
            if (b.id == id) {
                return b;
            }
        }
        return new GuiButton(id, 0, 0, "");
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        if (tab == 1 && mouseY >= rowsTop() - 2 && mouseY < rowsBottom() && mouseX > 8 && mouseX < width - 8) {
            int i = (mouseY - rowsTop() + 2) / 12 + scroll;
            if (i >= 0 && i < rows.size()) {
                TestRun r = rows.get(i);
                if (r == selected && button == 0) {
                    mc.displayGuiScreen(new GuiAdvancedTcp(this, r));
                    return;
                }
                selected = r;
                updateRowButtons();
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int d = wheel > 0 ? -1 : 1;
            if (tab == 1) {
                scroll = Math.max(0, scroll + d);
            } else if (tab == 2) {
                alertScroll = Math.max(0, alertScroll + d);
            }
        }
    }
}
