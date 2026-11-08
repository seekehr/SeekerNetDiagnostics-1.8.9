package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.stats.SampleRing;
import net.minecraft.client.gui.FontRenderer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Line graph over a {@link SampleRing}. Coordinate buffers are allocated once and reused; when there
 * are more samples than horizontal pixels, each pixel column keeps its min and max so spikes are
 * never hidden by down-sampling.
 */
public final class LatencyGraph {
    public static final long[] WINDOWS_MS = {30_000L, 60_000L, 300_000L, 900_000L, 0L};
    public static final String[] WINDOW_LABELS = {"30s", "1m", "5m", "15m", "All"};

    private float[] xs = new float[512];
    private float[] ys = new float[512];
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private final Date date = new Date();
    private final List<String> tip = new ArrayList<String>(3);

    private void ensure(int n) {
        if (xs.length < n) {
            int cap = Math.max(n, xs.length * 2);
            xs = new float[cap];
            ys = new float[cap];
        }
    }

    /**
     * Draws the graph. Returns tooltip lines for the hovered sample (or null). {@code windowMs} 0 means
     * the whole retained session.
     */
    public List<String> draw(FontRenderer fr, SampleRing ring, int x, int y, int w, int h, long windowMs, long now,
                             int mouseX, int mouseY, String unitLabel) {
        int labelW = 26;
        int gx = x + labelW;
        int gw = w - labelW;
        int gy = y + 2;
        int gh = h - 12;
        if (gw < 20 || gh < 20) {
            return null;
        }
        int from = 0;
        long start;
        if (windowMs > 0) {
            start = now - windowMs;
            from = ring.firstIndexSince(start);
        } else {
            start = ring.isEmpty() ? now - 60_000L : ring.time(0);
        }
        long span = Math.max(1000L, now - start);
        int n = ring.size() - from;

        double max = 0;
        double min = Double.MAX_VALUE;
        for (int i = from; i < ring.size(); i++) {
            double v = ring.value(i);
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        if (n == 0) {
            min = 0;
            max = 100;
        }
        double top = niceCeil(Math.max(max * 1.15, min + 20));
        double bottom = Math.max(0, niceFloor(min - (top - min) * 0.15));
        if (top - bottom < 10) {
            top = bottom + 10;
        }

        // grid + labels
        for (int i = 0; i <= 3; i++) {
            float ly = gy + gh - gh * i / 3f;
            Draw.rect(gx, ly, gx + gw, ly + 1, Theme.GRID);
            double val = bottom + (top - bottom) * i / 3.0;
            Draw.textRight(fr, String.valueOf((int) Math.round(val)), gx - 3, ly - 4, Theme.TEXT_FAINT);
        }
        Draw.text(fr, unitLabel, gx + 2, y + h - 9, Theme.TEXT_FAINT);
        String spanLabel = windowMs > 0 ? labelFor(windowMs) : "session";
        Draw.textRight(fr, "last " + spanLabel, gx + gw, y + h - 9, Theme.TEXT_FAINT);

        if (n == 0) {
            Draw.textCentered(fr, "No samples in this window yet", gx + gw / 2f, gy + gh / 2f - 4, Theme.TEXT_DIM);
            return null;
        }

        // project points (min/max per pixel column when dense)
        int count = 0;
        ensure(n <= gw ? n + 2 : 2 * gw + 8);
        if (n <= gw) {
            for (int i = from; i < ring.size(); i++) {
                xs[count] = gx + (float) ((ring.time(i) - start) / (double) span) * gw;
                ys[count] = (float) (gy + gh - (ring.value(i) - bottom) / (top - bottom) * gh);
                count++;
            }
        } else {
            int col = -1;
            double cmin = 0;
            double cmax = 0;
            for (int i = from; i <= ring.size(); i++) {
                int c = i < ring.size() ? (int) ((ring.time(i) - start) * (long) gw / span) : Integer.MAX_VALUE;
                if (c != col) {
                    if (col >= 0) {
                        float px = gx + col + 0.5f;
                        xs[count] = px;
                        ys[count++] = (float) (gy + gh - (cmin - bottom) / (top - bottom) * gh);
                        xs[count] = px;
                        ys[count++] = (float) (gy + gh - (cmax - bottom) / (top - bottom) * gh);
                    }
                    if (i == ring.size()) {
                        break;
                    }
                    col = c;
                    cmin = cmax = ring.value(i);
                } else {
                    double v = ring.value(i);
                    cmin = Math.min(cmin, v);
                    cmax = Math.max(cmax, v);
                }
            }
        }
        // extend the last value to "now" as a step (the value stays current until the next update)
        xs[count] = gx + gw;
        ys[count] = ys[count - 1];
        count++;
        for (int i = 0; i < count; i++) {
            xs[i] = Math.max(gx, Math.min(gx + gw, xs[i]));
            ys[i] = Math.max(gy, Math.min(gy + gh, ys[i]));
        }
        Draw.area(xs, ys, count, gy + gh, Theme.withAlpha(Theme.ACCENT, 0.22f));
        Draw.lineStrip(xs, ys, count, 1.6f, Theme.ACCENT);

        // spike markers and hover
        int hoverIdx = -1;
        float bestDx = 6;
        boolean inside = mouseX >= gx && mouseX <= gx + gw && mouseY >= gy - 4 && mouseY <= gy + gh + 4;
        for (int i = from; i < ring.size(); i++) {
            float px = gx + (float) ((ring.time(i) - start) / (double) span) * gw;
            float py = (float) (gy + gh - (ring.value(i) - bottom) / (top - bottom) * gh);
            if (ring.flag(i) == SampleRing.FLAG_SPIKE) {
                Draw.roundRect(px - 2, py - 2, 4, 4, 2, Theme.BAD);
            }
            if (inside) {
                float dx = Math.abs(px - mouseX);
                if (dx < bestDx) {
                    bestDx = dx;
                    hoverIdx = i;
                }
            }
        }
        if (hoverIdx >= 0) {
            float px = gx + (float) ((ring.time(hoverIdx) - start) / (double) span) * gw;
            float py = (float) (gy + gh - (ring.value(hoverIdx) - bottom) / (top - bottom) * gh);
            Draw.rect(px, gy, px + 1, gy + gh, Theme.withAlpha(Theme.TEXT, 0.25f));
            Draw.roundRect(px - 2.5f, py - 2.5f, 5, 5, 2.5f, Theme.TEXT);
            tip.clear();
            date.setTime(ring.time(hoverIdx));
            tip.add(String.format(Locale.ROOT, "%.0f ms", ring.value(hoverIdx)));
            tip.add(timeFmt.format(date));
            if (ring.flag(hoverIdx) == SampleRing.FLAG_SPIKE) {
                tip.add("Spike above rolling baseline");
            }
            return tip;
        }
        return null;
    }

    public static String labelFor(long ms) {
        for (int i = 0; i < WINDOWS_MS.length; i++) {
            if (WINDOWS_MS[i] == ms) {
                return WINDOW_LABELS[i];
            }
        }
        return (ms / 1000) + "s";
    }

    private static double niceCeil(double v) {
        double step = v <= 50 ? 10 : v <= 200 ? 25 : v <= 500 ? 50 : 100;
        return Math.ceil(v / step) * step;
    }

    private static double niceFloor(double v) {
        double step = v <= 50 ? 10 : v <= 200 ? 25 : 50;
        return Math.floor(v / step) * step;
    }
}
