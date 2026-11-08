package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "Test an IP" / "Test My Connection": repeated Minecraft pings to one target. */
public final class GuiTestTarget extends GuiBase {
    private static final int ID_START = 1;
    private static final int ID_MODE = 3;
    private static final int ID_CURRENT = 4;
    private static final int ID_COPY_SUMMARY = 5;
    private static final int ID_DETAILS = 6;
    private static final int ID_COMPARE = 7;
    private static final int ID_COPY_IP = 8;
    private static final String DEFAULT_PORT = String.valueOf(Target.DEFAULT_PORT);
    private static final long REFRESH_MS = 250;

    private String hostText;
    private String portText;
    private boolean autoStart;
    private boolean detailed;
    private String notice;
    private String error;
    private TestRun run;

    private GuiTextField hostField;
    private GuiTextField portField;
    private FlatButton startBtn;

    private long lastRefresh;
    private List<TestRun.Attempt> attempts = new ArrayList<TestRun.Attempt>();
    private List<String> conclusions = new ArrayList<String>();
    private int tabPing = -1;
    private List<String> pendingTip;

    public GuiTestTarget(GuiScreen parent, String host, String port, boolean autoStart) {
        super(parent, "Test an IP");
        this.hostText = host == null ? "" : host;
        this.portText = port == null || port.isEmpty() ? DEFAULT_PORT : port;
        this.autoStart = autoStart && !this.hostText.isEmpty();
    }

    public GuiTestTarget withNotice(String n) {
        notice = n;
        return this;
    }

    @Override
    protected String subtitle() {
        return "repeated Minecraft ping from this game process";
    }

    private int leftW() {
        return Math.max(170, Math.min(230, (int) (width * 0.38)));
    }

    @Override
    protected void layout() {
        if (hostField != null) {
            hostText = hostField.getText();
            portText = portField.getText();
        }
        int x = 16;
        int w = leftW() - 16;
        int y = 34;
        hostField = field(1, x, y + 10, w, hostText, "e.g. 203.27.106.53 or play.example.net", 255);
        portField = field(2, x, y + 40, 70, portText, DEFAULT_PORT, 5);
        if (!autoStart && hostText.isEmpty()) {
            hostField.setFocused(true);
        }
        button(ID_MODE, x, y + 64, w, 16, modeLabel(), FlatButton.Style.NORMAL)
                .tip("Quick uses fewer pings; Detailed gives more reliable percentiles. Counts are in Settings.");
        startBtn = button(ID_START, x, y + 86, w, 22, "Start Test", FlatButton.Style.PRIMARY);
        button(ID_CURRENT, x, y + 112, w, 14, "Use current server", FlatButton.Style.GHOST).enabled =
                NetDiag.currentServerTarget() != null;

        int rx = leftW() + 14;
        int rw = width - rx - 8;
        int bw = (rw - 16 - 12) / 4;
        String[] labels = {"Copy Summary", "Details", "Compare Routes", "Copy IP"};
        int[] ids = {ID_COPY_SUMMARY, ID_DETAILS, ID_COMPARE, ID_COPY_IP};
        for (int i = 0; i < 4; i++) {
            button(ids[i], rx + 8 + i * (bw + 4), height - 32, i == 3 ? rw - 16 - 3 * (bw + 4) : bw, 16, labels[i],
                    FlatButton.Style.NORMAL);
        }
        updateButtons();
        if (autoStart) {
            autoStart = false;
            start();
        }
    }

    private String modeLabel() {
        return detailed ? "Samples: Detailed (" + nd.settings().detailedSamples + ")"
                : "Samples: Quick (" + nd.settings().quickSamples + ")";
    }

    private boolean running() {
        return run != null && !run.finished();
    }

    private void updateButtons() {
        if (startBtn == null) {
            return;
        }
        boolean r = running();
        startBtn.displayString = r ? "Cancel Test" : (run != null ? "Run Again" : "Start Test");
        startBtn.style = r ? FlatButton.Style.DANGER : FlatButton.Style.PRIMARY;
        boolean has = run != null && run.finished();
        for (GuiButton b : buttonList) {
            if (b.id == ID_COPY_SUMMARY || b.id == ID_DETAILS || b.id == ID_COMPARE) {
                b.enabled = has;
            } else if (b.id == ID_COPY_IP) {
                b.enabled = run != null && run.resolvedAddress != null;
            } else if (b.id == ID_MODE) {
                b.enabled = !r;
            }
        }
    }

    private void start() {
        error = null;
        hostText = hostField.getText().trim();
        portText = portField.getText().trim();
        Target t;
        try {
            // The default port allows a Minecraft SRV lookup for host names, like the vanilla client with no port.
            t = Target.parse(hostText, DEFAULT_PORT.equals(portText) || portText.isEmpty() ? null : portText);
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            return;
        }
        int samples = detailed ? nd.settings().detailedSamples : nd.settings().quickSamples;
        try {
            run = nd.startTest(t, samples, null, null, false);
        } catch (IllegalStateException e) {
            error = e.getMessage();
            return;
        }
        attempts = new ArrayList<TestRun.Attempt>();
        conclusions = new ArrayList<String>();
        lastRefresh = 0;
        updateButtons();
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case ID_START:
                if (running()) {
                    run.cancelFlag.set(true);
                } else {
                    start();
                }
                break;
            case ID_MODE:
                detailed = !detailed;
                b.displayString = modeLabel();
                break;
            case ID_CURRENT:
                Target t = NetDiag.currentServerTarget();
                if (t != null) {
                    hostField.setText(t.host);
                    portField.setText(t.portExplicit ? String.valueOf(t.port) : DEFAULT_PORT);
                }
                break;
            case ID_COPY_SUMMARY:
                if (run != null) {
                    copy(Conclusions.clipboardSummary(run), "summary");
                }
                break;
            case ID_DETAILS:
                mc.displayGuiScreen(new GuiRunDetails(this, run));
                break;
            case ID_COMPARE:
                if (run != null) {
                    mc.displayGuiScreen(new GuiCompare(this).withTarget(run.target));
                }
                break;
            case ID_COPY_IP:
                if (run != null && run.resolvedAddress != null) {
                    copy(run.resolvedAddress, run.resolvedAddress);
                }
                break;
            default:
                break;
        }
        updateButtons();
    }

    @Override
    protected boolean onKey(char c, int key) {
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && !running()) {
            start();
            updateButtons();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing

    private boolean testingCurrentServer() {
        Target cur = NetDiag.currentServerTarget();
        return cur != null && run != null && cur.host.equalsIgnoreCase(run.target.host);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        long now = System.currentTimeMillis();
        if (run != null && now - lastRefresh >= REFRESH_MS) {
            lastRefresh = now;
            attempts = run.attempts();
            conclusions = Conclusions.describe(run);
            tabPing = testingCurrentServer() ? NetDiag.tabListPing() : -1;
            updateButtons();
        }
        int lw = leftW();
        int top = 28;
        int bottom = height - 10;
        Draw.card(8, top, lw, bottom - top, Theme.CARD, Theme.BORDER);
        int x = 16;
        Draw.text(fontRendererObj, "Host or IP address", x, 34, Theme.TEXT_DIM);
        Draw.text(fontRendererObj, "Port", x, 64, Theme.TEXT_DIM);
        Draw.text(fontRendererObj, "default 25565", x + 76, 78, Theme.TEXT_FAINT);
        int y = 34 + 132;
        if (error != null) {
            y += Draw.wrapped(fontRendererObj, error, x, y, lw - 16, Theme.BAD, 3) + 3;
        }
        if (run != null) {
            Draw.progress(x, y, lw - 16, 4, run.progress(), running() ? Theme.ACCENT : Theme.GOOD);
            y += 8;
            Draw.textClipped(fontRendererObj, run.phase, x, y, lw - 16, Theme.TEXT_DIM);
            y += 12;
        }
        if (notice != null) {
            y += Draw.wrapped(fontRendererObj, notice, x, y, lw - 16, Theme.ACCENT, 4) + 4;
        }
        String info = "Each sample opens a connection from this Minecraft process and does a server-list ping "
                + "(status + ping/pong), one every " + nd.settings().intervalMs + " ms. The server is never joined.";
        if (y + 20 < bottom) {
            Draw.wrapped(fontRendererObj, info, x, y, lw - 16, Theme.TEXT_FAINT, Math.max(1, (bottom - y - 6) / 9));
        }

        int rx = lw + 14;
        int rw = width - rx - 8;
        Draw.card(rx, top, rw, bottom - top, Theme.CARD, Theme.BORDER);
        drawResults(rx + 8, top + 7, rw - 16, height - 38 - (top + 7), mouseX, mouseY);
    }

    private void drawResults(int x, int y, int w, int h, int mouseX, int mouseY) {
        Draw.text(fontRendererObj, "Results", x, y, Theme.TEXT);
        if (run == null) {
            pendingTip = null;
            Draw.wrapped(fontRendererObj, "Enter a host or IP address and press Start Test (or Enter). The result "
                    + "is the round trip of a Minecraft ping packet, measured from this game process, so it takes the "
                    + "same route as your game connection (including ExitLag if it handles Minecraft).",
                    x, y + 16, w, Theme.TEXT_DIM, 6);
            drawFooter(x, y + h - 20, w);
            return;
        }
        String state = run.state == TestRun.State.RUNNING ? "Running" : run.state == TestRun.State.QUEUED ? "Queued"
                : run.state == TestRun.State.DONE ? "Finished" : run.state == TestRun.State.CANCELLED ? "Cancelled"
                : "Failed";
        Draw.textRight(fontRendererObj, Draw.clip(fontRendererObj, state + " · " + run.target.display()
                + (run.resolvedAddress != null ? " → " + Conclusions.endpoint(run) : ""), w - 50), x + w, y,
                Theme.TEXT_FAINT);
        int cy = drawSampleStrip(x, y + 14, w, mouseX, mouseY) + 6;

        StatSummary s = run.summary();
        String[] names = {"Median", "Mean", "Min", "Max", "p95", "p99", "Std dev", "Jitter"};
        double[] vals = {s.median, s.mean, s.min, s.max, s.p95, s.p99, s.stdDev, s.jitter};
        int cw = w / 4;
        for (int i = 0; i < 8; i++) {
            int gx = x + (i % 4) * cw;
            int gy = cy + (i / 4) * 22;
            Draw.text(fontRendererObj, names[i], gx, gy, Theme.TEXT_FAINT);
            String v = s.isEmpty() || (i >= 6 && s.count < 2) ? "-" : Conclusions.fmt(vals[i]);
            Draw.text(fontRendererObj, v, gx, gy + 10, i < 6 ? Theme.latencyColor(vals[i]) : Theme.TEXT);
        }
        cy += 46;
        String line = "Successful pings: " + s.count + " / " + run.measuredAttempts();
        if (run.failedAttempts() > 0) {
            line += "   Failed: " + Conclusions.failureBreakdown(run);
        }
        if (tabPing >= 0) {
            line += "   Tab-list ping now: " + tabPing + " ms";
        }
        Draw.textClipped(fontRendererObj, line, x, cy, w, run.failedAttempts() > 0 ? Theme.WARN : Theme.TEXT_DIM);
        cy += 13;
        int limit = y + h - 24;
        for (String c : conclusions) {
            int lh = Draw.wrappedHeight(fontRendererObj, c, w, 4);
            if (cy + lh > limit) {
                break;
            }
            Draw.wrapped(fontRendererObj, c, x, cy, w, Theme.TEXT, 4);
            cy += lh + 3;
        }
        drawFooter(x, y + h - 20, w);
    }

    private void drawFooter(int x, int y, int w) {
        Draw.textClipped(fontRendererObj, "Origin: this Minecraft process (in-process socket)", x, y, w, Theme.TEXT_FAINT);
        Draw.textClipped(fontRendererObj, Conclusions.EXITLAG_NOT_VERIFIED, x, y + 10, w, Theme.WARN);
    }

    /** One bar per sample: height ∝ ping for successes, red bar for failures. */
    private int drawSampleStrip(int x, int y, int w, int mouseX, int mouseY) {
        int h = 24;
        Draw.roundRect(x, y, w, h, 3, Theme.FIELD);
        int total = Math.max(1, run.samplesRequested + run.warmupRequested);
        float bw = Math.max(1.5f, (w - 4) / (float) total);
        double max = 1;
        for (TestRun.Attempt a : attempts) {
            if (a.outcome == ProbeOutcome.SUCCESS) {
                max = Math.max(max, a.pingMs);
            }
        }
        TestRun.Attempt hover = null;
        for (int i = 0; i < attempts.size(); i++) {
            TestRun.Attempt a = attempts.get(i);
            float bx = x + 2 + i * bw;
            if (bx + bw > x + w - 1) {
                break;
            }
            if (mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + h) {
                hover = a;
            }
            if (a.outcome == ProbeOutcome.SUCCESS) {
                float bh = (float) Math.max(2, (h - 4) * a.pingMs / max);
                Draw.rect(bx + 0.5f, y + h - 2 - bh, bx + bw - 0.5f, y + h - 2,
                        a.warmup ? Theme.TEXT_FAINT : Theme.latencyColor(a.pingMs));
            } else {
                Draw.rect(bx + 0.5f, y + 2, bx + bw - 0.5f, y + h - 2, Theme.withAlpha(Theme.BAD, 0.75f));
            }
        }
        if (hover != null) {
            List<String> tip = new ArrayList<String>();
            tip.add((hover.warmup ? "Warm-up" : "Sample " + hover.index) + ": " + hover.outcome.label);
            if (hover.outcome == ProbeOutcome.SUCCESS) {
                tip.add(String.format(Locale.ROOT, "Ping %.1f ms", hover.pingMs));
            } else if (hover.detail != null) {
                tip.add(hover.detail);
            }
            if (!Double.isNaN(hover.connectMs)) {
                tip.add(String.format(Locale.ROOT, "TCP connect %.2f ms", hover.connectMs));
            }
            pendingTip = tip;
        } else {
            pendingTip = null;
        }
        return y + h;
    }

    @Override
    protected void drawOverlay(int mouseX, int mouseY) {
        if (pendingTip != null) {
            Draw.tooltip(fontRendererObj, pendingTip, mouseX, mouseY, width, height);
        }
    }
}
