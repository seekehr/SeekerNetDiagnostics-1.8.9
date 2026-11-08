package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.config.Settings;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** /snd settings. Values are clamped to safe ranges; changes apply when Save is pressed. */
public final class GuiSettings extends GuiBase {
    private static final int ID_SAVE = 1;
    private static final int ID_DEFAULTS = 2;
    private static final int ID_CANCEL = 3;
    private static final int ID_ROW = 100; // 100 + row*2 (+0 minus / toggle, +1 plus)

    private abstract static class Row {
        final String label;
        final String help;
        final int step; // 0 = toggle
        final String unit;

        Row(String label, String help, int step, String unit) {
            this.label = label;
            this.help = help;
            this.step = step;
            this.unit = unit;
        }

        abstract int get();

        abstract void set(int v);

        String value() {
            return step == 0 ? (get() != 0 ? "On" : "Off") : get() + unit;
        }
    }

    private Settings edit;
    private final List<Row> rows = new ArrayList<Row>();

    public GuiSettings(GuiScreen parent) {
        super(parent, "Settings");
        edit = nd.settings().copy();
        rows.add(new Row("Quick test pings", "Pings in a quick test.", 1, "") {
            int get() {
                return edit.quickSamples;
            }

            void set(int v) {
                edit.quickSamples = v;
            }
        });
        rows.add(new Row("Detailed test pings", "Pings in a detailed test and in each comparison run.", 5, "") {
            int get() {
                return edit.detailedSamples;
            }

            void set(int v) {
                edit.detailedSamples = v;
            }
        });
        rows.add(new Row("Warm-up pings", "Run first and excluded from statistics (the first connection can be "
                + "slower while routes and caches warm up).", 1, "") {
            int get() {
                return edit.warmupSamples;
            }

            void set(int v) {
                edit.warmupSamples = v;
            }
        });
        rows.add(new Row("Interval between pings", "Delay between connections to the same server. Never below "
                + "500 ms.", 100, " ms") {
            int get() {
                return edit.intervalMs;
            }

            void set(int v) {
                edit.intervalMs = v;
            }
        });
        rows.add(new Row("Timeout", "How long to wait for each ping before counting it as failed.", 500, " ms") {
            int get() {
                return edit.timeoutMs;
            }

            void set(int v) {
                edit.timeoutMs = v;
            }
        });
        rows.add(new Row("Prefer IPv4", "Use the IPv4 address when a host has both IPv4 and IPv6.", 0, "") {
            int get() {
                return edit.preferIpv4 ? 1 : 0;
            }

            void set(int v) {
                edit.preferIpv4 = v != 0;
            }
        });
        rows.add(new Row("Export JSON", "Write a JSON report on export.", 0, "") {
            int get() {
                return edit.exportJson ? 1 : 0;
            }

            void set(int v) {
                edit.exportJson = v != 0;
            }
        });
        rows.add(new Row("Export CSV", "Write CSV reports (samples and summary) on export.", 0, "") {
            int get() {
                return edit.exportCsv ? 1 : 0;
            }

            void set(int v) {
                edit.exportCsv = v != 0;
            }
        });
    }

    @Override
    protected void layout() {
        int w = Math.min(380, width - 16);
        int x = (width - w) / 2;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = 34 + i * 18;
            if (r.step == 0) {
                button(ID_ROW + i * 2, x + w - 84, y, 80, 14, r.value(), FlatButton.Style.NORMAL).tip(r.help);
            } else {
                button(ID_ROW + i * 2, x + w - 84, y, 18, 14, "-", FlatButton.Style.NORMAL).tip(r.help);
                button(ID_ROW + i * 2 + 1, x + w - 22, y, 18, 14, "+", FlatButton.Style.NORMAL).tip(r.help);
            }
        }
        int by = 34 + rows.size() * 18 + 12;
        button(ID_SAVE, x + w - 70, by, 70, 18, "Save", FlatButton.Style.PRIMARY);
        button(ID_CANCEL, x + w - 144, by, 70, 18, "Cancel", FlatButton.Style.NORMAL);
        button(ID_DEFAULTS, x, by, 90, 18, "Restore defaults", FlatButton.Style.NORMAL);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int w = Math.min(380, width - 16);
        int x = (width - w) / 2;
        Draw.card(x - 4, 30, w + 8, rows.size() * 18 + 4, Theme.CARD, Theme.BORDER);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = 34 + i * 18;
            Draw.text(fontRendererObj, r.label, x + 4, y + 3, Theme.TEXT);
            if (r.step != 0) {
                Draw.textCentered(fontRendererObj, r.value(), x + w - 44, y + 3, Theme.ACCENT);
            }
        }
        Draw.text(fontRendererObj, "Hover a control for an explanation.", x, 34 + rows.size() * 18 + 34,
                Theme.TEXT_FAINT);
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == ID_SAVE) {
            nd.applySettings(edit.copy());
            back();
        } else if (b.id == ID_CANCEL) {
            back();
        } else if (b.id == ID_DEFAULTS) {
            edit = new Settings();
            initGui();
        } else if (b.id >= ID_ROW) {
            Row r = rows.get((b.id - ID_ROW) / 2);
            if (r.step == 0) {
                r.set(r.get() == 0 ? 1 : 0);
                b.displayString = r.value();
            } else {
                r.set(r.get() + ((b.id - ID_ROW) % 2 == 1 ? r.step : -r.step));
            }
            edit.sanitize();
        }
    }
}
