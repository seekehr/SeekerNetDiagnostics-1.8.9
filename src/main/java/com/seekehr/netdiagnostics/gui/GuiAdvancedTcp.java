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

/** Advanced TCP diagnostics for one test run: DNS, socket endpoints, per-attempt outcomes. */
public final class GuiAdvancedTcp extends GuiBase {
    private static final int ID_BACK = 1;
    private static final int ID_COPY_IP = 2;
    private static final int ID_COPY_SUMMARY = 3;
    private static final int ID_TEST = 4;

    private final TestRun run;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private int scroll;
    private List<TestRun.Attempt> attempts = new ArrayList<TestRun.Attempt>();
    private long lastRefresh;

    public GuiAdvancedTcp(GuiScreen parent, TestRun run) {
        super(parent, "Advanced TCP Diagnostics");
        this.run = run;
    }

    @Override
    protected String subtitle() {
        return run == null ? null : run.target.display() + " · " + run.kind.label;
    }

    @Override
    protected void layout() {
        int by = height - 24;
        button(ID_BACK, 8, by, 60, 16, "Back", FlatButton.Style.NORMAL);
        if (run == null) {
            button(ID_TEST, 72, by, 90, 16, "Test an IP", FlatButton.Style.PRIMARY);
            return;
        }
        button(ID_COPY_IP, 72, by, 70, 16, "Copy IP", FlatButton.Style.NORMAL).enabled = run.resolvedAddress != null;
        button(ID_COPY_SUMMARY, 146, by, 96, 16, "Copy Summary", FlatButton.Style.NORMAL);
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == ID_BACK) {
            back();
        } else if (b.id == ID_TEST) {
            mc.displayGuiScreen(new GuiTestTarget(parent, "", "", false));
        } else if (b.id == ID_COPY_IP && run != null && run.resolvedAddress != null) {
            copy(run.resolvedAddress, run.resolvedAddress);
        } else if (b.id == ID_COPY_SUMMARY && run != null) {
            copy(Conclusions.clipboardSummary(run), "summary");
        }
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        if (run == null) {
            Draw.textCentered(fontRendererObj, "No test results yet. Run a test first.", width / 2f, height / 2f - 10,
                    Theme.TEXT_DIM);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefresh > 250) {
            lastRefresh = now;
            attempts = run.attempts();
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
        Draw.text(fontRendererObj, "CONNECTION", x, y, Theme.TEXT_FAINT);
        y += 12;
        TestRun.Attempt lastOk = null;
        for (TestRun.Attempt a : attempts) {
            if (a.outcome == ProbeOutcome.SUCCESS) {
                lastOk = a;
            }
        }
        y = kv("Remote IP and port", run.resolvedAddress == null ? "-" : run.resolvedAddress + ":" + run.connectPort,
                x, y, w);
        y = kv("Local interface", lastOk == null || lastOk.localAddress == null ? "-" : lastOk.localAddress, x, y, w);
        y = kv("Local ephemeral port", lastOk == null || lastOk.localPort == 0 ? "-" : String.valueOf(lastOk.localPort)
                + " (last attempt)", x, y, w);
        y = kv("Socket origin", "This Minecraft JVM (NIO)", x, y, w);
        y = kv("Connect timeout", run.tcpTimeoutMs + " ms", x, y, w);
        y = kv("Attempt interval", run.intervalMs + " ms (min 500)", x, y, w);
        StatSummary s = run.summary();
        int measured = run.measuredAttempts();
        y = kv("Success rate", measured == 0 ? "-" : String.format(Locale.ROOT, "%d / %d (%.0f%%)", s.count, measured,
                100.0 * s.count / measured), x, y, w);
        y = kv("Establishment time", s.isEmpty() ? "-" : String.format(Locale.ROOT, "median %.2f, min %.2f, max %.2f ms",
                s.median, s.min, s.max), x, y, w);
        y += 4;
        Draw.text(fontRendererObj, "DNS", x, y, Theme.TEXT_FAINT);
        y += 12;
        DnsInfo d = run.dns;
        if (d == null) {
            y = kv("Lookup", "not performed yet", x, y, w);
        } else {
            y = kv("Query", d.query + (d.ipLiteral ? " (IP literal)" : ""), x, y, w);
            y = kv("Lookup duration", Double.isNaN(d.systemLookupMs) ? "-" : String.format(Locale.ROOT, "%.2f ms (OS "
                    + "resolver)", d.systemLookupMs), x, y, w);
            y = kv("Answers", d.addresses.isEmpty() ? (d.error != null ? d.error : "-") : join(d.addresses), x, y, w);
            y = kv("TTL", d.minTtlSeconds >= 0 ? d.minTtlSeconds + " s" : (d.ipLiteral ? "n/a" : "not obtainable"), x,
                    y, w);
            if (d.srvRecord != null) {
                y = kv("SRV", d.srvRecord, x, y, w);
            }
            for (String n : d.notes) {
                if (y + 18 > bottom) {
                    break;
                }
                y += Draw.wrapped(fontRendererObj, n, x, y, w, Theme.TEXT_FAINT, 2) + 1;
            }
        }
        y += 4;
        if (y + 30 < bottom) {
            Draw.text(fontRendererObj, "OS TCP STATISTICS", x, y, Theme.TEXT_FAINT);
            y += 11;
            Draw.wrapped(fontRendererObj, "Not available. Java's standard socket API does not expose the operating "
                    + "system's TCP RTT estimate or retransmission counters, and this mod ships no native code "
                    + "(no elevated permissions needed). Values above are measured in the JVM.", x, y, w,
                    Theme.TEXT_DIM, Math.max(1, (bottom - y - 4) / 9));
        }

        // right: failure breakdown + attempts
        x = rx + 8;
        w = width - 8 - rx - 16;
        y = top + 7;
        Draw.text(fontRendererObj, "OUTCOMES", x, y, Theme.TEXT_FAINT);
        y += 12;
        Map<ProbeOutcome, Integer> counts = run.outcomeCounts();
        if (counts.isEmpty()) {
            Draw.text(fontRendererObj, "No attempts yet.", x, y, Theme.TEXT_DIM);
            y += 11;
        }
        for (Map.Entry<ProbeOutcome, Integer> e : counts.entrySet()) {
            Draw.text(fontRendererObj, e.getValue() + " × " + e.getKey().label, x, y,
                    e.getKey() == ProbeOutcome.SUCCESS ? Theme.GOOD : Theme.WARN);
            y += 10;
            if (e.getKey() != ProbeOutcome.SUCCESS) {
                y += Draw.wrapped(fontRendererObj, e.getKey().explanation, x + 8, y, w - 8, Theme.TEXT_FAINT, 3) + 2;
            }
        }
        y += 4;
        Draw.text(fontRendererObj, "#", x, y, Theme.TEXT_FAINT);
        Draw.text(fontRendererObj, "Time", x + 22, y, Theme.TEXT_FAINT);
        Draw.text(fontRendererObj, "Result", x + 92, y, Theme.TEXT_FAINT);
        Draw.textRight(fontRendererObj, "ms", x + w, y, Theme.TEXT_FAINT);
        y += 11;
        int listTop = y;
        int vis = Math.max(1, (bottom - 6 - listTop) / 10);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, attempts.size() - vis)));
        for (int i = 0; i < vis && i + scroll < attempts.size(); i++) {
            TestRun.Attempt a = attempts.get(i + scroll);
            int ry = listTop + i * 10;
            Draw.text(fontRendererObj, a.warmup ? "w" : String.valueOf(a.index), x, ry, Theme.TEXT_DIM);
            Draw.text(fontRendererObj, timeFmt.format(new Date(a.timestamp)), x + 22, ry, Theme.TEXT_DIM);
            Draw.textClipped(fontRendererObj, a.outcome == ProbeOutcome.SUCCESS ? a.outcome.label
                    : a.outcome.label + ": " + a.detail, x + 92, ry, w - 92 - 44,
                    a.outcome == ProbeOutcome.SUCCESS ? Theme.TEXT : Theme.BAD);
            Draw.textRight(fontRendererObj, a.outcome == ProbeOutcome.SUCCESS
                    ? String.format(Locale.ROOT, "%.2f", a.rttMs) : "-", x + w, ry, Theme.latencyColor(a.rttMs));
        }
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
