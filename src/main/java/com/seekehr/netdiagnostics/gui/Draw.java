package com.seekehr.netdiagnostics.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.util.List;

/** Immediate-mode drawing helpers for 1.8.9 (no allocations in the hot paths). */
public final class Draw {
    private static final int CORNER_SEGMENTS = 6;
    private static final float[] COS = new float[CORNER_SEGMENTS + 1];
    private static final float[] SIN = new float[CORNER_SEGMENTS + 1];

    static {
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            double a = (Math.PI / 2) * i / CORNER_SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private Draw() {
    }

    public static void rect(float x1, float y1, float x2, float y2, int color) {
        begin(color);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        wr.pos(x1, y2, 0).endVertex();
        wr.pos(x2, y2, 0).endVertex();
        wr.pos(x2, y1, 0).endVertex();
        wr.pos(x1, y1, 0).endVertex();
        Tessellator.getInstance().draw();
        end();
    }

    /** Filled rectangle with rounded corners of radius r. */
    public static void roundRect(float x, float y, float w, float h, float r, int color) {
        r = Math.min(r, Math.min(w, h) / 2f);
        if (r < 0.5f) {
            rect(x, y, x + w, y + h, color);
            return;
        }
        begin(color);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_POLYGON, DefaultVertexFormats.POSITION);
        // top-right, bottom-right, bottom-left, top-left (clockwise in screen space)
        corner(wr, x + w - r, y + r, r, 3);
        corner(wr, x + w - r, y + h - r, r, 0);
        corner(wr, x + r, y + h - r, r, 1);
        corner(wr, x + r, y + r, r, 2);
        Tessellator.getInstance().draw();
        end();
    }

    private static void corner(WorldRenderer wr, float cx, float cy, float r, int quadrant) {
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            float c = COS[i];
            float s = SIN[i];
            float dx;
            float dy;
            switch (quadrant) {
                case 0: // bottom-right: from right to bottom
                    dx = c;
                    dy = s;
                    break;
                case 1: // bottom-left: from bottom to left
                    dx = -s;
                    dy = c;
                    break;
                case 2: // top-left: from left to top
                    dx = -c;
                    dy = -s;
                    break;
                default: // top-right: from top to right
                    dx = s;
                    dy = -c;
                    break;
            }
            wr.pos(cx + dx * r, cy + dy * r, 0).endVertex();
        }
    }

    /** Rounded card with a 1px border. */
    public static void card(float x, float y, float w, float h, int fill, int border) {
        roundRect(x - 1, y - 1, w + 2, h + 2, 5, border);
        roundRect(x, y, w, h, 4, fill);
    }

    public static void outline(float x, float y, float w, float h, int color) {
        rect(x, y, x + w, y + 1, color);
        rect(x, y + h - 1, x + w, y + h, color);
        rect(x, y, x + 1, y + h, color);
        rect(x + w - 1, y, x + w, y + h, color);
    }

    /** Polyline from parallel coordinate arrays (first n points). */
    public static void lineStrip(float[] xs, float[] ys, int n, float width, int color) {
        if (n < 2) {
            return;
        }
        begin(color);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(width);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION);
        for (int i = 0; i < n; i++) {
            wr.pos(xs[i], ys[i], 0).endVertex();
        }
        Tessellator.getInstance().draw();
        GL11.glLineWidth(1f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        end();
    }

    /** Filled area under a polyline down to baseline y (vertical gradient from top colour to transparent). */
    public static void area(float[] xs, float[] ys, int n, float baseY, int topColor) {
        if (n < 2) {
            return;
        }
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
        int a = (topColor >>> 24) & 0xFF;
        int r = (topColor >>> 16) & 0xFF;
        int g = (topColor >>> 8) & 0xFF;
        int b = topColor & 0xFF;
        for (int i = 0; i < n; i++) {
            wr.pos(xs[i], ys[i], 0).color(r, g, b, a).endVertex();
            wr.pos(xs[i], baseY, 0).color(r, g, b, 0).endVertex();
        }
        Tessellator.getInstance().draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private static void begin(int color) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f,
                ((color >>> 24) & 0xFF) / 255f);
    }

    private static void end() {
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ text

    public static int text(FontRenderer fr, String s, float x, float y, int color) {
        return fr.drawString(s, x, y, color, false);
    }

    public static void textRight(FontRenderer fr, String s, float right, float y, int color) {
        fr.drawString(s, right - fr.getStringWidth(s), y, color, false);
    }

    public static void textCentered(FontRenderer fr, String s, float cx, float y, int color) {
        fr.drawString(s, cx - fr.getStringWidth(s) / 2f, y, color, false);
    }

    /** Draws text trimmed with an ellipsis to fit maxWidth. */
    public static void textClipped(FontRenderer fr, String s, float x, float y, int maxWidth, int color) {
        fr.drawString(clip(fr, s, maxWidth), x, y, color, false);
    }

    public static String clip(FontRenderer fr, String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (fr.getStringWidth(s) <= maxWidth) {
            return s;
        }
        String ell = "...";
        return fr.trimStringToWidth(s, Math.max(0, maxWidth - fr.getStringWidth(ell))) + ell;
    }

    /** Draws word-wrapped text, returns the height used. */
    public static int wrapped(FontRenderer fr, String s, int x, int y, int width, int color, int maxLines) {
        List<String> lines = fr.listFormattedStringToWidth(s, width);
        int n = Math.min(lines.size(), maxLines);
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines) {
                line = clip(fr, line + " ...", width);
            }
            fr.drawString(line, x, y + i * (fr.FONT_HEIGHT + 1), color, false);
        }
        return n * (fr.FONT_HEIGHT + 1);
    }

    public static int wrappedHeight(FontRenderer fr, String s, int width, int maxLines) {
        return Math.min(fr.listFormattedStringToWidth(s, width).size(), maxLines) * (fr.FONT_HEIGHT + 1);
    }

    /** Horizontal progress bar. */
    public static void progress(float x, float y, float w, float h, double fraction, int color) {
        roundRect(x, y, w, h, h / 2, 0xFF0F131A);
        float fw = (float) (w * Math.max(0, Math.min(1, fraction)));
        if (fw > 1) {
            roundRect(x, y, fw, h, h / 2, color);
        }
    }

    /** Small tooltip box near the mouse, kept on screen. */
    public static void tooltip(FontRenderer fr, List<String> lines, int mx, int my, int screenW, int screenH) {
        int w = 0;
        for (String l : lines) {
            w = Math.max(w, fr.getStringWidth(l));
        }
        int h = lines.size() * (fr.FONT_HEIGHT + 1) + 6;
        int x = mx + 10;
        int y = my - h - 4;
        if (x + w + 8 > screenW) {
            x = mx - w - 14;
        }
        if (y < 2) {
            y = my + 12;
        }
        if (y + h > screenH) {
            y = screenH - h - 2;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, 300);
        card(x, y, w + 8, h, 0xF5101520, Theme.ACCENT_DARK);
        for (int i = 0; i < lines.size(); i++) {
            fr.drawString(lines.get(i), x + 4, y + 4 + i * (fr.FONT_HEIGHT + 1), Theme.TEXT, false);
        }
        GlStateManager.popMatrix();
    }

    /** Draws text scaled by {@code scale} with its top-left corner at (x, y). */
    public static void textScaled(FontRenderer fr, String s, float x, float y, float scale, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1);
        fr.drawString(s, 0, 0, color, false);
        GlStateManager.popMatrix();
    }
}
