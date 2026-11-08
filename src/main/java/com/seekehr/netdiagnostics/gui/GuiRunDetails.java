package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.dns.DnsInfo;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Details of one test: DNS, timing breakdown and every sample. */
public final class GuiRunDetails extends GuiBase {
    private static final int ID_BACK = 1;
    private static final int ID_COPY_IP = 2;
    private static final int ID_COPY_SUMMARY = 3;

    private final TestRun run;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private int scroll;
    private List<TestRun.Attempt> attempts = new ArrayList<TestRun.Attempt>();
    private StatSummary connect = StatSummary.EMPTY;
    private StatSummary status = StatSummary.EMPTY;
    private String intercept = "";
    private long lastRefresh;

    public GuiRunDetails(GuiScreen parent, TestRun run) {
        super(parent, "Test Details");
        this.run = run;
    }

    @Override
    protected String subtitle() {
        return run == null ? null : run.target.display();
    }

    @Override
    protected void layout() {
        int by = height - 24;
        button(ID_BACK, 8, by, 60, 16, "Back", FlatButton.Style.NORMAL);
        if (run != null) {
            button(ID_COPY_IP, 72, by, 70, 16, "Copy IP", FlatButton.Style.NORMAL).enabled = run.resolvedAddress != null;
            button(ID_COPY_SUMMARY, 146, by, 96, 16, "Copy Summary", FlatButton.Style.NORMAL);
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == ID_BACK) {
            back();
        } else if (b.id == ID_COPY_IP && run != null && run.resolvedAddress != null) {
            copy(run.resolvedAddress, run.resolvedAddress);
        } else if (b.id == ID_COPY_SUMMARY && run != null) {
            copy(Conclusions.clipboardSummary(run), "summary");
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        if (run == null) {
            Draw.textCentered(fontRendererObj, "No test selected.", width / 2f, height / 2f - 10, Theme.TEXT_DIM);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefresh > 250) {
            lastRefresh = now;
            attempts = run.attempts();
            connect = run.connectSummary();
            status = run.statusSummary();
            intercept = Conclusions.interceptLine(run);
        }
        int top = 28;
        int bottom = height - 30;
        int half = (width - 22) / 2;
        int lx = 8;
        int rx = lx + half + 6;
        Draw.card(lx, top, half, bottom - top, Theme.CARD, Theme.BORDER);
        Draw.card(rx, top, width - 8 - rx, bottom - top, Theme.CARD, Theme.BORDER);

        int x = lx + 8;
        int w = half - 16;
        int y = top + 7;
        Draw.text(fontRendererObj, "TIMING (medians)", x, y, Theme.TEXT_FAINT);
        y += 12;
        StatSummary s = run.summary();
        y = kv("Ping (round trip)", s.isEmpty() ? "-" : Conclusions.fmt(s.median), x, y, w);
        y = kv("TCP connect", connect.isEmpty() ? "-" : String.format(Locale.ROOT, "%.2f ms", connect.median), x, y, w);
        y = kv("Status response", status.isEmpty() ? "-" : Conclusions.fmt(status.median), x, y, w);
        y = kv("Successful pings", s.count + " / " + run.measuredAttempts(), x, y, w);
        y = kv("Timeout / interval", run.timeoutMs + " / " + run.intervalMs + " ms", x, y, w);
        y += 3;
        y += Draw.wrapped(fontRendererObj, intercept, x, y, w,
                run.localInterceptDetected() ? Theme.ACCENT : Theme.TEXT_DIM, 5) + 6;
        Draw.text(fontRendererObj, "ADDRESS", x, y, Theme.TEXT_FAINT);
        y += 12;
        y = kv("Connected to", run.resolvedAddress == null ? "-" : Conclusions.endpoint(run), x, y, w);
        DnsInfo d = run.dns;
        if (d != null) {
            y = kv("DNS lookup", d.ipLiteral ? "not needed (IP)" : Double.isNaN(d.lookupMs) ? "-"
                    : String.format(Locale.ROOT, "%.1f ms", d.lookupMs), x, y, w);
            y = kv("Answers", d.addresses.isEmpty() ? (d.error != null ? d.error : "-") : join(d.addresses), x, y, w);
            if (d.srvRecord != null) {
                y = kv("SRV", d.srvRecord, x, y, w);
            }
        }
        if (y + 30 < bottom) {
            Draw.wrapped(fontRendererObj, "Ping = ping packet sent until pong received. TCP connect and status times "
                    + "are shown for diagnosis only; behind a local proxy the connect time does not reflect "
                    + "distance to the server.", x, y + 4, w, Theme.TEXT_FAINT, Math.max(1, (bottom - y - 8) / 9));
        }

        // right: outcomes and samples
        x = rx + 8;
        w = width - 8 - rx - 16;
        y = top + 7;
        Draw.text(fontRendererObj, "OUTCOMES", x, y, Theme.TEXT_FAINT);
        y += 12;
        Map<ProbeOutcome, Integer> counts = run.outcomeCounts();
        for (Map.Entry<ProbeOutcome, Integer> e : counts.entrySet()) {
            Draw.text(fontRendererObj, e.getValue() + " × " + e.getKey().label, x, y,
                    e.getKey() == ProbeOutcome.SUCCESS ? Theme.GOOD : Theme.WARN);
            y += 10;
            if (e.getKey() != ProbeOutcome.SUCCESS) {
                y += Draw.wrapped(fontRendererObj, e.getKey().explanation, x + 8, y, w - 8, Theme.TEXT_FAINT, 3) + 2;
            }
        }
        y += 4;
        int cPing = x + w;
        int cStatus = x + w - 52;
        int cConn = x + w - 104;
        Draw.text(fontRendererObj, "#", x, y, Theme.TEXT_FAINT);
        Draw.text(fontRendererObj, "Time", x + 20, y, Theme.TEXT_FAINT);
        Draw.textRight(fontRendererObj, "connect", cConn, y, Theme.TEXT_FAINT);
        Draw.textRight(fontRendererObj, "status", cStatus, y, Theme.TEXT_FAINT);
        Draw.textRight(fontRendererObj, "ping", cPing, y, Theme.TEXT_FAINT);
        y += 11;
        int vis = Math.max(1, (bottom - 6 - y) / 10);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, attempts.size() - vis)));
        for (int i = 0; i < vis && i + scroll < attempts.size(); i++) {
            TestRun.Attempt a = attempts.get(i + scroll);
            int ry = y + i * 10;
            Draw.text(fontRendererObj, a.warmup ? "w" : String.valueOf(a.index), x, ry, Theme.TEXT_DIM);
            Draw.text(fontRendererObj, timeFmt.format(new Date(a.timestamp)), x + 20, ry, Theme.TEXT_DIM);
            Draw.textRight(fontRendererObj, ms(a.connectMs, 2), cConn, ry, Theme.TEXT_DIM);
            Draw.textRight(fontRendererObj, ms(a.statusMs, 1), cStatus, ry, Theme.TEXT_DIM);
            if (a.outcome == ProbeOutcome.SUCCESS) {
                Draw.textRight(fontRendererObj, ms(a.pingMs, 1), cPing, ry, Theme.latencyColor(a.pingMs));
            } else {
                Draw.textRight(fontRendererObj, a.outcome.label, cPing, ry, Theme.BAD);
            }
        }
    }

    private static String ms(double v, int decimals) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private int kv(String k, String v, int x, int y, int w) {
        Draw.text(fontRendererObj, k, x, y, Theme.TEXT_DIM);
        int kw = fontRendererObj.getStringWidth(k) + 8;
        Draw.textRight(fontRendererObj, Draw.clip(fontRendererObj, v, w - kw), x + w, y, Theme.TEXT);
        return y + 10;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll = Math.max(0, scroll + (wheel > 0 ? -3 : 3));
        }
    }
}
