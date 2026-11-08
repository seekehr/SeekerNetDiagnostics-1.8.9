package com.seekehr.netdiagnostics.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/** Common screen behaviour: dark backdrop, header, flat buttons with tooltips, and toasts. */
public abstract class GuiBase extends GuiScreen {
    protected static final int HEADER = 22;
    private String toast;
    private int toastColor;
    private long toastUntil;

    @Override
    public final void initGui() {
        buttonList.clear();
        layout();
    }

    /** Create buttons for the current width/height. */
    protected abstract void layout();

    protected abstract String title();

    /** Right-aligned header text, or null. */
    protected String status() {
        return null;
    }

    protected abstract void drawContent(int mouseX, int mouseY);

    /** Drawn above the buttons (tooltips). */
    protected void drawOverlay(int mouseX, int mouseY) {
    }

    protected FlatButton button(int id, int x, int y, int w, int h, String text, FlatButton.Style style) {
        FlatButton b = new FlatButton(id, x, y, w, h, text, style);
        buttonList.add(b);
        return b;
    }

    protected void toast(String msg, int color) {
        toast = msg;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 3000;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, Theme.BACKDROP);
        Draw.text(fontRendererObj, title(), 8, 7, Theme.TEXT);
        String st = status();
        if (st != null) {
            Draw.textRight(fontRendererObj, st, width - 8, 7, Theme.TEXT_DIM);
        }
        Draw.rect(6, HEADER - 3, width - 6, HEADER - 2, Theme.BORDER);
        drawContent(mouseX, mouseY);
        for (GuiButton b : buttonList) {
            b.drawButton(mc, mouseX, mouseY);
        }
        drawOverlay(mouseX, mouseY);
        for (GuiButton b : buttonList) {
            if (b instanceof FlatButton && ((FlatButton) b).tooltip != null && ((FlatButton) b).isHovered(mouseX, mouseY)) {
                Draw.tooltip(fontRendererObj, fontRendererObj.listFormattedStringToWidth(((FlatButton) b).tooltip, 220),
                        mouseX, mouseY, width, height);
                break;
            }
        }
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = fontRendererObj.getStringWidth(toast) + 16;
            int ty = height - 44;
            Draw.card((width - tw) / 2f, ty, tw, 16, Theme.CARD_RAISED, toastColor);
            Draw.textCentered(fontRendererObj, toast, width / 2f, ty + 4, Theme.TEXT);
        }
    }
}
