package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.compare.CompareSession;
import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.stats.ProfileComparison;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Route comparison workflow. The user changes ExitLag manually between runs; this screen only
 * measures and compares. Runs are TCP-handshake-only so that every profile is measured identically.
 */
public final class GuiCompare extends GuiBase {
    private static final int ID_BASELINE = 1;
    private static final int ID_NEW_ROUTE = 2;
    private static final int ID_ABBA = 3;
    private static final int ID_CANCEL = 4;
    private static final int ID_RESET = 5;
    private static final int ID_EXPORT = 6;
    private static final int ID_COPY = 7;
    private static final int ID_SAMPLES = 8;

    /** The run in progress (kept static so leaving and reopening the screen keeps tracking it). */
    private static TestRun currentRun;
    private static String currentLabel;
    private static boolean detailed = true;

    private String hostText = "";
    private String portText = String.valueOf(Target.DEFAULT_PORT);
    private String labelText;
    private String descText = "";
    private String error;
    private GuiTextField hostField;
    private GuiTextField portField;
    private GuiTextField labelField;
    private GuiTextField descField;

    private long lastRefresh;
    private List<CompareSession.Profile> profiles = new ArrayList<CompareSession.Profile>();
    private List<StatSummary> summaries = new ArrayList<StatSummary>();
    private List<String> recommendations = new ArrayList<String>();
    private String recommendationSignature;

    public GuiCompare(GuiScreen parent) {
        super(parent, "Compare ExitLag Routes");
        CompareSession s = nd.compareSession();
        if (s != null) {
            hostText = s.target().host;
            portText = String.valueOf(s.target().port);
        }
        labelText = nextLabel();
    }

    public GuiCompare withTarget(Target t) {
        CompareSession s = nd.compareSession();
        if (s == null || s.profiles().isEmpty()) {
            hostText = t.host;
            portText = String.valueOf(t.port);
        }
        return this;
    }

    @Override
    protected String subtitle() {
        return "same target, different ExitLag settings";
    }

    private String nextLabel() {
        CompareSession s = nd.compareSession();
        int n = s == null ? 0 : s.profiles().size();
        return "Profile " + (char) ('A' + Math.min(n, 25));
    }

    private int leftW() {
        return Math.max(170, Math.min(220, (int) (width * 0.36)));
    }

    @Override
    protected void layout() {
        if (hostField != null) {
            hostText = hostField.getText();
            portText = portField.getText();
            labelText = labelField.getText();
            descText = descField.getText();
        }
        int x = 16;
        int w = leftW() - 16;
        hostField = field(1, x, 42, w - 62, hostText, "Target host or IP", 255);
        portField = field(2, x + w - 56, 42, 56, portText, "25565", 5);
        labelField = field(3, x, 74, w, labelText, "Profile label", 40);
        descField = field(4, x, 106, w, descText, "ExitLag settings, e.g. Manual Singapore, 2 routes", 120);
        button(ID_SAMPLES, x, 128, w, 14, samplesLabel(), FlatButton.Style.GHOST)
                .tip("Each run uses the same number of TCP handshakes so profiles are comparable.");
        int bw = (w - 4) / 2;
        button(ID_BASELINE, x, 146, bw, 20, "Start Baseline", FlatButton.Style.PRIMARY)
                .tip("Measure the current ExitLag configuration and save it as the first profile.");
        button(ID_NEW_ROUTE, x + bw + 4, 146, w - bw - 4, 20, "Test New Route", FlatButton.Style.PRIMARY)
                .tip("After changing ExitLag yourself, measure it as a new profile with the label above.");
        button(ID_ABBA, x, 170, w, 16, "Repeat next (ABBA)", FlatButton.Style.NORMAL)
                .tip("Re-measures profiles in A-B-B-A order so slow network drift affects each profile equally. "
                        + "Switch ExitLag to the named profile's settings first.");
        button(ID_CANCEL, x, 190, bw, 14, "Cancel Run", FlatButton.Style.DANGER);
        button(ID_RESET, x + bw + 4, 190, w - bw - 4, 14, "Reset", FlatButton.Style.NORMAL)
                .tip("Discard this comparison (test history is kept).");
        int rx = leftW() + 14;
        int rw = width - rx - 8;
        button(ID_COPY, rx + 8, height - 30, 90, 16, "Copy Result", FlatButton.Style.NORMAL);
        button(ID_EXPORT, rx + 102, height - 30, 70, 16, "Export", FlatButton.Style.NORMAL);
        updateButtons();
    }

    private String samplesLabel() {
        return "Samples per run: " + (detailed ? nd.settings().detailedSamples + " (detailed)"
                : nd.settings().quickSamples + " (quick)");
    }

    private boolean running() {
        return currentRun != null && !currentRun.finished();
    }

    private void updateButtons() {
        CompareSession s = nd.compareSession();
        int n = s == null ? 0 : s.profiles().size();
        boolean r = running();
        for (GuiButton b : buttonList) {
            switch (b.id) {
                case ID_BASELINE:
                    b.enabled = !r && n == 0;
                    break;
                case ID_NEW_ROUTE:
                    b.enabled = !r && n >= 1 && n < CompareSession.MAX_PROFILES;
                    break;
                case ID_ABBA:
                    CompareSession.Profile next = s == null ? null : s.suggestedNext();
                    b.enabled = !r && next != null;
                    b.displayString = next == null ? "Repeat next (ABBA)" : "Repeat: " + next.label;
                    break;
                case ID_CANCEL:
                    b.enabled = r;
                    break;
                case ID_RESET:
                    b.enabled = !r && n > 0;
                    break;
                case ID_SAMPLES:
                    b.enabled = !r && n == 0;
                    break;
                case ID_COPY:
                    b.enabled = n >= 2;
                    break;
                default:
                    break;
            }
        }
        boolean locked = n > 0;
        hostField.setEnabled(!locked);
        portField.setEnabled(!locked);
    }

    private void startRun(CompareSession.Profile profile) {
        CompareSession s = nd.compareSession();
        try {
            currentRun = nd.startTest(TestRun.Kind.TCP, s.target(), s.samplesPerRun(), profile.label,
                    profile.exitLagDescription, false);
            currentLabel = profile.label;
            s.recordRun(profile, currentRun);
            lastRefresh = 0;
        } catch (IllegalStateException e) {
            error = e.getMessage();
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        error = null;
        CompareSession s = nd.compareSession();
        switch (b.id) {
            case ID_BASELINE: {
                Target t;
                try {
                    t = Target.parse(hostField.getText(), portField.getText());
                } catch (IllegalArgumentException e) {
                    error = e.getMessage();
                    break;
                }
                String label = labelField.getText().trim().isEmpty() ? "Profile A" : labelField.getText().trim();
                s = new CompareSession(t, detailed ? nd.settings().detailedSamples : nd.settings().quickSamples);
                nd.setCompareSession(s);
                startRun(s.addProfile(label, descField.getText().trim()));
                labelField.setText(nextLabel());
                descField.setText("");
                break;
            }
            case ID_NEW_ROUTE: {
                if (s == null) {
                    break;
                }
                String label = labelField.getText().trim().isEmpty() ? nextLabel() : labelField.getText().trim();
                if (s.profile(label) != null) {
                    error = "A profile named \"" + label + "\" already exists. Use a new label, or use Repeat.";
                    break;
                }
                try {
                    startRun(s.addProfile(label, descField.getText().trim()));
                } catch (IllegalStateException e) {
                    error = e.getMessage();
                    break;
                }
                labelField.setText(nextLabel());
                descField.setText("");
                break;
            }
            case ID_ABBA:
                if (s != null && s.suggestedNext() != null) {
                    startRun(s.suggestedNext());
                }
                break;
            case ID_CANCEL:
                if (currentRun != null) {
                    currentRun.cancelFlag.set(true);
                }
                break;
            case ID_RESET:
                nd.setCompareSession(null);
                currentRun = null;
                labelField.setText("Profile A");
                toast("Comparison reset", Theme.WARN);
                break;
            case ID_SAMPLES:
                detailed = !detailed;
                b.displayString = samplesLabel();
                break;
            case ID_COPY:
                copy(resultText(), "comparison");
                break;
            case ID_EXPORT:
                nd.exportAll(new NetDiag.ExportCallback() {
                    @Override
                    public void done(List<File> files, String err) {
                        toast(err != null ? "Export failed: " + err : "Exported " + files.size()
                                + " file(s) to config/netscope/reports/", err != null ? Theme.BAD : Theme.GOOD);
                    }
                });
                break;
            default:
                break;
        }
        lastRefresh = 0;
        updateButtons();
    }

    private String resultText() {
        StringBuilder sb = new StringBuilder();
        CompareSession s = nd.compareSession();
        if (s == null) {
            return "";
        }
        sb.append("SeekerNetDiagnostics route comparison - ").append(s.target().display()).append('\n');
        for (int i = 0; i < profiles.size(); i++) {
            CompareSession.Profile p = profiles.get(i);
            StatSummary st = summaries.get(i);
            sb.append(p.label);
            if (!p.exitLagDescription.isEmpty()) {
                sb.append(" (").append(p.exitLagDescription).append(')');
            }
            sb.append(": ");
            sb.append(st.isEmpty() ? "no successful samples" : String.format(Locale.ROOT,
                    "median %.1f, mean %.1f, p95 %.1f, p99 %.1f, max %.1f ms, IQR %.1f ms, n=%d, timeouts %d",
                    st.median, st.mean, st.p95, st.p99, st.max, st.iqr(), st.count, p.count(ProbeOutcome.TIMEOUT)));
            sb.append('\n');
        }
        for (String r : recommendations) {
            sb.append(r).append('\n');
        }
        sb.append(Conclusions.EXITLAG_NOT_VERIFIED);
        return sb.toString();
    }

    // ------------------------------------------------------------------ drawing

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < Math.max(250, nd.settings().dashboardRefreshMs)) {
            return;
        }
        lastRefresh = now;
        CompareSession s = nd.compareSession();
        profiles = s == null ? new ArrayList<CompareSession.Profile>() : s.profiles();
        summaries = new ArrayList<StatSummary>();
        for (CompareSession.Profile p : profiles) {
            summaries.add(p.summary());
        }
        // The bootstrap is comparatively expensive: recompute only when the underlying data changed.
        StringBuilder sig = new StringBuilder().append(running());
        for (StatSummary st : summaries) {
            sig.append('|').append(st.count).append(':').append(st.mean);
        }
        String signature = sig.toString();
        if (signature.equals(recommendationSignature)) {
            updateButtons();
            return;
        }
        recommendationSignature = signature;
        recommendations = new ArrayList<String>();
        if (profiles.size() >= 2 && !running()) {
            for (int i = 1; i < profiles.size(); i++) {
                ProfileComparison c = s.compare(profiles.get(0), profiles.get(i));
                recommendations.add(c.recommendation(profiles.get(0).label, profiles.get(i).label));
            }
        }
        updateButtons();
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        refresh();
        int lw = leftW();
        int top = 28;
        int bottom = height - 10;
        Draw.card(8, top, lw, bottom - top, Theme.CARD, Theme.BORDER);
        int x = 16;
        Draw.text(fontRendererObj, "Target (TCP port)", x, 32, Theme.TEXT_DIM);
        Draw.text(fontRendererObj, "Profile label", x, 64, Theme.TEXT_DIM);
        Draw.text(fontRendererObj, "Your ExitLag settings (description)", x, 96, Theme.TEXT_DIM);
        int y = 210;
        if (error != null) {
            y += Draw.wrapped(fontRendererObj, error, x, y, lw - 16, Theme.BAD, 3) + 3;
        }
        if (running()) {
            Draw.progress(x, y, lw - 16, 4, currentRun.progress(), Theme.ACCENT);
            Draw.textClipped(fontRendererObj, currentLabel + ": " + currentRun.phase, x, y + 7, lw - 16, Theme.TEXT_DIM);
            y += 20;
        } else {
            CompareSession cs = nd.compareSession();
            CompareSession.Profile next = cs == null ? null : cs.suggestedNext();
            if (next != null) {
                String hint = "Next in ABBA order: switch ExitLag to " + next.label
                        + (next.exitLagDescription.isEmpty() ? "" : " (" + next.exitLagDescription + ")")
                        + ", then click Repeat.";
                y += Draw.wrapped(fontRendererObj, hint, x, y, lw - 16, Theme.ACCENT, 3) + 4;
            }
        }
        String steps = "1. Set ExitLag to your baseline configuration and click Start Baseline. "
                + "2. Change ExitLag yourself (this mod cannot change it). "
                + "3. Describe the new settings, then click Test New Route. "
                + "4. Use Repeat (ABBA) a few times for a fairer comparison.";
        if (y + 20 < bottom) {
            Draw.wrapped(fontRendererObj, steps, x, y, lw - 16, Theme.TEXT_FAINT, Math.max(1, (bottom - y - 6) / 9));
        }

        int rx = lw + 14;
        int rw = width - rx - 8;
        Draw.card(rx, top, rw, bottom - top, Theme.CARD, Theme.BORDER);
        drawTable(rx + 8, top + 7, rw - 16, bottom - 26 - (top + 7));
    }

    private static final String[] ROWS = {"Median RTT", "Mean RTT", "p95", "p99", "Maximum", "Variability (IQR)",
            "Std deviation", "Successful handshakes", "Timeouts", "Other failures", "Test duration", "Runs"};

    private void drawTable(int x, int y, int w, int h) {
        CompareSession s = nd.compareSession();
        Draw.text(fontRendererObj, s == null ? "Side-by-side comparison" : "Side-by-side: " + s.target().display()
                + " (TCP handshake)", x, y, Theme.TEXT);
        if (profiles.isEmpty()) {
            Draw.wrapped(fontRendererObj, "No profiles yet. Results for each ExitLag configuration appear here "
                    + "side by side, with a recommendation that accounts for sample size and overlap.",
                    x, y + 16, w, Theme.TEXT_DIM, 5);
            Draw.textClipped(fontRendererObj, Conclusions.EXITLAG_NOT_VERIFIED, x, y + h - 2, w, Theme.WARN);
            return;
        }
        int labelW = Math.min(118, w / 3);
        int colW = (w - labelW) / Math.max(1, profiles.size());
        int ty = y + 14;
        for (int i = 0; i < profiles.size(); i++) {
            CompareSession.Profile p = profiles.get(i);
            int cx = x + labelW + i * colW;
            Draw.textClipped(fontRendererObj, p.label + (i == 0 ? " (base)" : ""), cx, ty, colW - 4,
                    i == 0 ? Theme.ACCENT : Theme.TEXT);
            Draw.textClipped(fontRendererObj, p.exitLagDescription.isEmpty() ? "-" : p.exitLagDescription, cx,
                    ty + 10, colW - 4, Theme.TEXT_FAINT);
        }
        ty += 24;
        Draw.rect(x, ty - 3, x + w, ty - 2, Theme.BORDER);
        double bestMedian = Double.MAX_VALUE;
        for (StatSummary st : summaries) {
            if (!st.isEmpty() && st.count >= ProfileComparison.MIN_SAMPLES) {
                bestMedian = Math.min(bestMedian, st.median);
            }
        }
        for (int r = 0; r < ROWS.length; r++) {
            int ry = ty + r * 11;
            if (ry > y + h - 40) {
                break;
            }
            Draw.text(fontRendererObj, ROWS[r], x, ry, Theme.TEXT_DIM);
            for (int i = 0; i < profiles.size(); i++) {
                CompareSession.Profile p = profiles.get(i);
                StatSummary st = summaries.get(i);
                String v;
                int color = Theme.TEXT;
                switch (r) {
                    case 0:
                        v = ms(st, st.median);
                        if (!st.isEmpty() && st.median == bestMedian && profiles.size() > 1) {
                            color = Theme.GOOD;
                        }
                        break;
                    case 1:
                        v = ms(st, st.mean);
                        break;
                    case 2:
                        v = ms(st, st.p95);
                        break;
                    case 3:
                        v = ms(st, st.p99);
                        break;
                    case 4:
                        v = ms(st, st.max);
                        break;
                    case 5:
                        v = ms(st, st.iqr());
                        break;
                    case 6:
                        v = ms(st, st.stdDev);
                        break;
                    case 7:
                        v = st.count + " / " + p.attempts();
                        break;
                    case 8:
                        int to = p.count(ProbeOutcome.TIMEOUT);
                        v = String.valueOf(to);
                        color = to > 0 ? Theme.WARN : Theme.TEXT;
                        break;
                    case 9:
                        int other = p.failures() - p.count(ProbeOutcome.TIMEOUT);
                        v = String.valueOf(other);
                        color = other > 0 ? Theme.WARN : Theme.TEXT;
                        break;
                    case 10:
                        v = String.format(Locale.ROOT, "%.0f s", p.durationMs() / 1000.0);
                        break;
                    default:
                        v = String.valueOf(p.runs().size());
                        break;
                }
                Draw.textClipped(fontRendererObj, v, x + labelW + i * colW, ry, colW - 4, color);
            }
        }
        int ry = ty + ROWS.length * 11 + 6;
        if (running()) {
            Draw.text(fontRendererObj, "Measuring " + currentLabel + "... the recommendation appears when the run "
                    + "finishes.", x, Math.min(ry, y + h - 30), Theme.ACCENT);
        }
        for (String rec : recommendations) {
            int lh = Draw.wrappedHeight(fontRendererObj, rec, w, 4);
            if (ry + lh > y + h - 12) {
                break;
            }
            Draw.wrapped(fontRendererObj, rec, x, ry, w, Theme.TEXT, 4);
            ry += lh + 4;
        }
        Draw.textClipped(fontRendererObj, Conclusions.EXITLAG_NOT_VERIFIED, x + 172, y + h + 9, w - 172,
                Theme.WARN);
    }

    private static String ms(StatSummary s, double v) {
        return s.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f ms", v);
    }

    @Override
    protected boolean onKey(char c, int key) {
        return false;
    }
}
