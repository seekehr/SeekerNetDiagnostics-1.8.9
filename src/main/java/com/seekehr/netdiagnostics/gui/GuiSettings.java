package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.config.Settings;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** /ns settings: every option with safe bounds. Changes apply when Save is pressed. */
public final class GuiSettings extends GuiBase {
    private static final int ID_SAVE = 1;
    private static final int ID_DEFAULTS = 2;
    private static final int ID_CANCEL = 3;
    private static final int ID_ROW = 100; // 100 + row*2 (+0 minus / toggle, +1 plus)

    private interface Row {
        String label();

        String value();

        String help();

        /** delta: -1, +1 for numbers; 0 toggles. */
        void change(int delta);

        boolean isToggle();
    }

    private final Settings edit;
    private boolean monitorOnStartup;
    private final List<Row> rows = new ArrayList<Row>();
    private int scroll;

    public GuiSettings(GuiScreen parent) {
        super(parent, "Settings");
        edit = nd.settings().copy();
        monitorOnStartup = nd.settingsStore.monitorOnStartup;
        buildRows();
    }

    @Override
    protected String subtitle() {
        return "conservative defaults; limits prevent flooding any server";
    }

    private abstract static class IntRow implements Row {
        final String label;
        final String unit;
        final int step;
        final String help;

        IntRow(String label, String unit, int step, String help) {
            this.label = label;
            this.unit = unit;
            this.step = step;
            this.help = help;
        }

        abstract int get();

        abstract void set(int v);

        public String label() {
            return label;
        }

        public String value() {
            return get() + unit;
        }

        public String help() {
            return help;
        }

        public void change(int d) {
            set(get() + d * step);
        }

        public boolean isToggle() {
            return false;
        }
    }

    private abstract static class BoolRow implements Row {
        final String label;
        final String help;

        BoolRow(String label, String help) {
            this.label = label;
            this.help = help;
        }

        abstract boolean get();

        abstract void set(boolean v);

        public String label() {
            return label;
        }

        public String value() {
            return get() ? "On" : "Off";
        }

        public String help() {
            return help;
        }

        public void change(int d) {
            set(!get());
        }

        public boolean isToggle() {
            return true;
        }
    }

    private void buildRows() {
        rows.add(new IntRow("Quick test samples", "", 1, "TCP handshakes in a quick test (3-100).") {
            int get() {
                return edit.quickSamples;
            }

            void set(int v) {
                edit.quickSamples = v;
            }
        });
        rows.add(new IntRow("Detailed test samples", "", 5, "TCP handshakes in a detailed test / comparison run.") {
            int get() {
                return edit.detailedSamples;
            }

            void set(int v) {
                edit.detailedSamples = v;
            }
        });
        rows.add(new IntRow("Warm-up connections", "", 1, "Excluded from statistics; primes DNS/ARP/route caches.") {
            int get() {
                return edit.warmupSamples;
            }

            void set(int v) {
                edit.warmupSamples = v;
            }
        });
        rows.add(new IntRow("Interval between attempts", " ms", 100,
                "Delay between attempts to the same destination. Never below 500 ms.") {
            int get() {
                return edit.intervalMs;
            }

            void set(int v) {
                edit.intervalMs = v;
            }
        });
        rows.add(new IntRow("TCP connect timeout", " ms", 250, "How long to wait for a handshake reply.") {
            int get() {
                return edit.tcpTimeoutMs;
            }

            void set(int v) {
                edit.tcpTimeoutMs = v;
            }
        });
        rows.add(new IntRow("Minecraft status timeout", " ms", 500, "Time limit for the status + ping exchange.") {
            int get() {
                return edit.statusTimeoutMs;
            }

            void set(int v) {
                edit.statusTimeoutMs = v;
            }
        });
        rows.add(new IntRow("Dashboard refresh", " ms", 50, "How often on-screen statistics are recomputed.") {
            int get() {
                return edit.dashboardRefreshMs;
            }

            void set(int v) {
                edit.dashboardRefreshMs = v;
            }
        });
        rows.add(new IntRow("Monitor interval", " ms", 250, "How often the live monitor reads the reported latency.") {
            int get() {
                return edit.monitorIntervalMs;
            }

            void set(int v) {
                edit.monitorIntervalMs = v;
            }
        });
        rows.add(new IntRow("Graph history", " min", 5, "Live-monitor history kept in memory (changing it clears "
                + "the graph).") {
            int get() {
                return edit.graphHistoryMinutes;
            }

            void set(int v) {
                edit.graphHistoryMinutes = v;
            }
        });
        rows.add(new BoolRow("Latency overlay", "Small in-game overlay with live ping and jitter.") {
            boolean get() {
                return edit.overlayVisible;
            }

            void set(boolean v) {
                edit.overlayVisible = v;
            }
        });
        rows.add(new BoolRow("Monitor on startup", "Start the passive live monitor automatically.") {
            boolean get() {
                return monitorOnStartup;
            }

            void set(boolean v) {
                monitorOnStartup = v;
            }
        });
        rows.add(new BoolRow("Packet observer", "Passive read-only Netty handler that timestamps keepalive and "
                + "latency-update packets. Never modifies, delays or sends packets.") {
            boolean get() {
                return edit.packetInstrumentation;
            }

            void set(boolean v) {
                edit.packetInstrumentation = v;
            }
        });
        rows.add(new BoolRow("Prefer IPv4", "Use the IPv4 address when a host has both IPv4 and IPv6.") {
            boolean get() {
                return edit.preferIpv4;
            }

            void set(boolean v) {
                edit.preferIpv4 = v;
            }
        });
        rows.add(new BoolRow("Export JSON", "Write JSON reports.") {
            boolean get() {
                return edit.exportJson;
            }

            void set(boolean v) {
                edit.exportJson = v;
            }
        });
        rows.add(new BoolRow("Export CSV", "Write CSV reports (samples and summary).") {
            boolean get() {
                return edit.exportCsv;
            }

            void set(boolean v) {
                edit.exportCsv = v;
            }
        });
        rows.add(new BoolRow("Include local addresses", "Include your local interface IP and ephemeral ports in "
                + "exports. Off by default for privacy.") {
            boolean get() {
                return edit.exportIncludeLocalAddresses;
            }

            void set(boolean v) {
                edit.exportIncludeLocalAddresses = v;
            }
        });
        rows.add(new Row() {
            public String label() {
                return "Diagnostic verbosity";
            }

            public String value() {
                return edit.verbosity.name().charAt(0) + edit.verbosity.name().substring(1).toLowerCase();
            }

            public String help() {
                return "Quiet: no chat notices for alerts. Normal: warnings only. Verbose: all alerts.";
            }

            public void change(int d) {
                Settings.Verbosity[] v = Settings.Verbosity.values();
                edit.verbosity = v[(edit.verbosity.ordinal() + 1) % v.length];
            }

            public boolean isToggle() {
                return true;
            }
        });
    }

    private int rowH() {
        return 18;
    }

    private int visibleRows() {
        return Math.max(1, (height - 34 - 34) / rowH());
    }

    @Override
    protected void layout() {
        int w = Math.min(420, width - 16);
        int x = (width - w) / 2;
        int vis = visibleRows();
        scroll = Math.max(0, Math.min(scroll, rows.size() - vis));
        for (int i = 0; i < vis && i + scroll < rows.size(); i++) {
            Row r = rows.get(i + scroll);
            int y = 32 + i * rowH();
            int idx = i + scroll;
            if (r.isToggle()) {
                button(ID_ROW + idx * 2, x + w - 84, y, 80, 14, r.value(), FlatButton.Style.NORMAL).tip(r.help());
            } else {
                button(ID_ROW + idx * 2, x + w - 84, y, 18, 14, "-", FlatButton.Style.NORMAL).tip(r.help());
                button(ID_ROW + idx * 2 + 1, x + w - 22, y, 18, 14, "+", FlatButton.Style.NORMAL).tip(r.help());
            }
        }
        int by = height - 26;
        button(ID_SAVE, x + w - 70, by, 70, 18, "Save", FlatButton.Style.PRIMARY);
        button(ID_CANCEL, x + w - 144, by, 70, 18, "Cancel", FlatButton.Style.NORMAL);
        button(ID_DEFAULTS, x, by, 90, 18, "Restore defaults", FlatButton.Style.NORMAL);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int w = Math.min(420, width - 16);
        int x = (width - w) / 2;
        Draw.card(x - 4, 28, w + 8, visibleRows() * rowH() + 4, Theme.CARD, Theme.BORDER);
        int vis = visibleRows();
        for (int i = 0; i < vis && i + scroll < rows.size(); i++) {
            Row r = rows.get(i + scroll);
            int y = 32 + i * rowH();
            Draw.text(fontRendererObj, r.label(), x + 4, y + 3, Theme.TEXT);
            if (!r.isToggle()) {
                Draw.textCentered(fontRendererObj, r.value(), x + w - 44, y + 3, Theme.ACCENT);
            }
        }
        if (rows.size() > vis) {
            Draw.textRight(fontRendererObj, "scroll for more (" + (scroll + 1) + "-" + Math.min(rows.size(), scroll + vis)
                    + " of " + rows.size() + ")", x + w, height - 38, Theme.TEXT_FAINT);
        }
        Draw.text(fontRendererObj, "Hover a control for an explanation.", x, height - 38, Theme.TEXT_FAINT);
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == ID_SAVE) {
            nd.settingsStore.monitorOnStartup = monitorOnStartup;
            nd.applySettings(edit.copy());
            back();
            return;
        }
        if (b.id == ID_CANCEL) {
            back();
            return;
        }
        if (b.id == ID_DEFAULTS) {
            Settings d = new Settings();
            copyInto(d, edit);
            monitorOnStartup = true;
            initGui();
            return;
        }
        if (b.id >= ID_ROW) {
            int idx = (b.id - ID_ROW) / 2;
            boolean plus = (b.id - ID_ROW) % 2 == 1;
            Row r = rows.get(idx);
            r.change(r.isToggle() ? 0 : (plus ? 1 : -1));
            edit.sanitize();
            if (r.isToggle()) {
                b.displayString = r.value();
            }
        }
    }

    private static void copyInto(Settings from, Settings to) {
        Settings c = from.copy();
        to.quickSamples = c.quickSamples;
        to.detailedSamples = c.detailedSamples;
        to.warmupSamples = c.warmupSamples;
        to.intervalMs = c.intervalMs;
        to.tcpTimeoutMs = c.tcpTimeoutMs;
        to.statusTimeoutMs = c.statusTimeoutMs;
        to.dashboardRefreshMs = c.dashboardRefreshMs;
        to.monitorIntervalMs = c.monitorIntervalMs;
        to.graphHistoryMinutes = c.graphHistoryMinutes;
        to.overlayVisible = c.overlayVisible;
        to.packetInstrumentation = c.packetInstrumentation;
        to.exportJson = c.exportJson;
        to.exportCsv = c.exportCsv;
        to.exportIncludeLocalAddresses = c.exportIncludeLocalAddresses;
        to.preferIpv4 = c.preferIpv4;
        to.verbosity = c.verbosity;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int old = scroll;
            scroll = Math.max(0, Math.min(rows.size() - visibleRows(), scroll + (wheel > 0 ? -1 : 1)));
            if (scroll != old) {
                initGui();
            }
        }
    }
}
