package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.report.Conclusions;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.ResultStore;
import com.seekehr.netdiagnostics.core.test.TestRun;
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

/** Main screen opened by /snd: the three test actions and the history of results. */
public final class GuiDashboard extends GuiBase {
    private static final int ID_TEST_MINE = 1;
    private static final int ID_TEST_IP = 2;
    private static final int ID_COMPARE = 3;
    private static final int ID_SETTINGS = 4;
    private static final int ID_SORT = 30;
    private static final int ID_ORDER = 31;
    private static final int ID_RETEST = 40;
    private static final int ID_COPY_IP = 41;
    private static final int ID_COMPARE_SEL = 42;
    private static final int ID_DETAILS = 43;
    private static final int ID_EXPORT = 44;
    private static final int ID_CLEAR = 45;

    private static ResultStore.SortKey sortKey = ResultStore.SortKey.DATE;
    private static boolean ascending;
    private static String search = "";

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
    private GuiTextField searchField;
    private FlatButton clearBtn;
    private long clearArmedUntil;
    private int top;
    private int bottom;

    private List<TestRun> rows = new ArrayList<TestRun>();
    private boolean[] intercepted = new boolean[0];
    private int rowsVersion = -1;
    private String rowsQuery;
    private TestRun selected;
    private int scroll;

    public GuiDashboard(GuiScreen parent) {
        super(parent, "SeekerNetDiagnostics");
    }

    @Override
    protected String subtitle() {
        return "Minecraft ping tests from this game process";
    }

    @Override
    protected void layout() {
        int pad = 8;
        button(ID_SETTINGS, width - pad - 60, 5, 60, 14, "Settings", FlatButton.Style.NORMAL)
                .tip("Sample counts, interval and timeout");
        int aw = (width - pad * 2 - 12) / 3;
        int ay = 30;
        button(ID_TEST_MINE, pad, ay, aw, 30, "Test My Connection", FlatButton.Style.PRIMARY)
                .sub("current server").tip("Ping the server you are connected to. (1)");
        button(ID_TEST_IP, pad + aw + 6, ay, aw, 30, "Test an IP", FlatButton.Style.PRIMARY)
                .sub("any host and port").tip("Enter an IP address or host name and ping it. (2)");
        button(ID_COMPARE, pad + (aw + 6) * 2, ay, width - pad * 2 - (aw + 6) * 2, 30, "Compare ExitLag Routes",
                FlatButton.Style.PRIMARY).sub("A/B route profiles")
                .tip("Ping the same server under different ExitLag settings and compare. (3)");

        top = ay + 38;
        bottom = height - 16;
        int sw = Math.min(180, width / 3);
        searchField = field(100, pad, top, sw, search, "Search host, IP, profile...", 64);
        button(ID_SORT, pad + sw + 6, top, 74, 18, "Sort: " + sortKey.label, FlatButton.Style.NORMAL);
        button(ID_ORDER, pad + sw + 84, top, 40, 18, ascending ? "Asc" : "Desc", FlatButton.Style.NORMAL);
        String[] qa = {"Retest", "Copy IP", "Compare", "Details", "Export", "Clear History"};
        int qw = (width - pad * 2 - 5 * 4) / 6;
        for (int i = 0; i < qa.length; i++) {
            FlatButton b = button(ID_RETEST + i, pad + i * (qw + 4), bottom - 18,
                    i == 5 ? width - pad * 2 - 5 * (qw + 4) : qw, 16, qa[i],
                    i == 5 ? FlatButton.Style.DANGER : FlatButton.Style.NORMAL);
            if (i == 5) {
                clearBtn = b;
            }
        }
        updateRowButtons();
    }

    private void updateRowButtons() {
        for (GuiButton b : buttonList) {
            if (b.id >= ID_RETEST && b.id <= ID_DETAILS) {
                b.enabled = selected != null;
            }
        }
    }

    private int rowsTop() {
        return top + 36;
    }

    private int rowsBottom() {
        return bottom - 24;
    }

    private void refreshRows() {
        String q = search + "|" + sortKey + "|" + ascending;
        if (rowsVersion != nd.results.version() || !q.equals(rowsQuery)) {
            rows = nd.results.query(search, sortKey, ascending);
            intercepted = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                intercepted[i] = rows.get(i).localInterceptDetected(); // computed once, not per frame
            }
            rowsVersion = nd.results.version();
            rowsQuery = q;
            if (selected != null && !rows.contains(selected)) {
                selected = null;
                updateRowButtons();
            }
        }
    }

    private static final String[] COLS = {"Host / IP", "Port", "OK", "Fail", "Median", "p95", "Proxy", "Last test"};

    private int[] columns(int x, int w) {
        int[] fixed = {0, 34, 24, 26, 46, 46, 38, 60};
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

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        search = searchField.getText();
        refreshRows();
        int pad = 8;
        int cardTop = top + 22;
        Draw.card(pad, cardTop, width - pad * 2, bottom - 22 - cardTop, Theme.CARD, Theme.BORDER);
        int[] cx = columns(pad + 6, width - pad * 2 - 12);
        for (int i = 0; i < COLS.length; i++) {
            Draw.text(fontRendererObj, COLS[i], cx[i], cardTop + 4, Theme.TEXT_FAINT);
        }
        Draw.rect(pad + 4, cardTop + 13, width - pad - 4, cardTop + 14, Theme.BORDER);
        int ry = rowsTop();
        int visible = Math.max(1, (rowsBottom() - ry) / 12);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        if (rows.isEmpty()) {
            Draw.textCentered(fontRendererObj, search.isEmpty() ? "No tests yet. Click \"Test My Connection\" or "
                    + "\"Test an IP\"." : "No results match \"" + search + "\".", width / 2f, ry + 10, Theme.TEXT_DIM);
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
            Draw.text(fontRendererObj, String.valueOf(s.count), cx[2], y, s.count > 0 ? Theme.GOOD : Theme.TEXT_DIM);
            int fails = r.failedAttempts();
            Draw.text(fontRendererObj, String.valueOf(fails), cx[3], y, fails > 0 ? Theme.BAD : Theme.TEXT_DIM);
            Draw.text(fontRendererObj, s.isEmpty() ? "-" : Conclusions.fmt(s.median), cx[4], y,
                    Theme.latencyColor(s.median));
            Draw.text(fontRendererObj, s.isEmpty() ? "-" : Conclusions.fmt(s.p95), cx[5], y, Theme.TEXT);
            boolean local = intercepted[i + scroll];
            Draw.text(fontRendererObj, local ? "local" : "-", cx[6], y, local ? Theme.ACCENT : Theme.TEXT_FAINT);
            Draw.text(fontRendererObj, r.startedAt > 0 ? dateFmt.format(new Date(r.startedAt)) : "-", cx[7], y,
                    Theme.TEXT_DIM);
        }
        if (rows.size() > visible) {
            Draw.textRight(fontRendererObj, (scroll + 1) + "-" + Math.min(rows.size(), scroll + visible) + " of "
                    + rows.size(), width - pad - 6, top + 5, Theme.TEXT_FAINT);
        }
        clearBtn.displayString = System.currentTimeMillis() < clearArmedUntil ? "Click to confirm" : "Clear History";
        Draw.text(fontRendererObj, "1/2/3 actions · ↑/↓ select · Ctrl+F search · E export "
                + "· \"Proxy: local\" = connection accepted by a local proxy such as ExitLag", 8, height - 11,
                Theme.TEXT_FAINT);
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
        } else if (id == ID_SETTINGS) {
            mc.displayGuiScreen(new GuiSettings(this));
        } else if (id == ID_SORT) {
            ResultStore.SortKey[] keys = ResultStore.SortKey.values();
            sortKey = keys[(sortKey.ordinal() + 1) % keys.length];
            b.displayString = "Sort: " + sortKey.label;
        } else if (id == ID_ORDER) {
            ascending = !ascending;
            b.displayString = ascending ? "Asc" : "Desc";
        } else if (id == ID_RETEST && selected != null) {
            mc.displayGuiScreen(new GuiTestTarget(this, selected.target.host,
                    selected.target.portExplicit ? String.valueOf(selected.target.port) : "", true));
        } else if (id == ID_COPY_IP && selected != null) {
            String ip = selected.resolvedAddress != null ? selected.resolvedAddress : selected.target.host;
            copy(ip, ip);
        } else if (id == ID_COMPARE_SEL && selected != null) {
            mc.displayGuiScreen(new GuiCompare(this).withTarget(selected.target));
        } else if (id == ID_DETAILS && selected != null) {
            mc.displayGuiScreen(new GuiRunDetails(this, selected));
        } else if (id == ID_EXPORT) {
            export();
        } else if (id == ID_CLEAR) {
            if (System.currentTimeMillis() < clearArmedUntil) {
                nd.clearHistory();
                selected = null;
                clearArmedUntil = 0;
                updateRowButtons();
                toast("Test history cleared", Theme.WARN);
            } else {
                clearArmedUntil = System.currentTimeMillis() + 3000;
            }
        }
    }

    private void testMyConnection() {
        Target t = NetDiag.currentServerTarget();
        if (t == null) {
            mc.displayGuiScreen(new GuiTestTarget(this, "", "", false).withNotice(mc.isIntegratedServerRunning()
                    ? "You are in singleplayer. Join a multiplayer server to test your connection, or enter an IP."
                    : "You are not connected to a multiplayer server. Enter an IP or host to test."));
            return;
        }
        mc.displayGuiScreen(new GuiTestTarget(this, t.host, t.portExplicit ? String.valueOf(t.port) : "", true)
                .withNotice("Testing the server you are connected to."));
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
                    testMyConnection();
                    return true;
                case Keyboard.KEY_2:
                    actionPerformed(new GuiButton(ID_TEST_IP, 0, 0, ""));
                    return true;
                case Keyboard.KEY_3:
                    actionPerformed(new GuiButton(ID_COMPARE, 0, 0, ""));
                    return true;
                case Keyboard.KEY_E:
                    export();
                    return true;
                case Keyboard.KEY_F:
                    if (isCtrlKeyDown()) {
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
        if (rows.isEmpty()) {
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

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        if (mouseY >= rowsTop() - 2 && mouseY < rowsBottom() && mouseX > 8 && mouseX < width - 8) {
            int i = (mouseY - rowsTop() + 2) / 12 + scroll;
            if (i >= 0 && i < rows.size()) {
                TestRun r = rows.get(i);
                if (r == selected && button == 0) {
                    mc.displayGuiScreen(new GuiRunDetails(this, r));
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
            scroll = Math.max(0, scroll + (wheel > 0 ? -1 : 1));
        }
    }
}
