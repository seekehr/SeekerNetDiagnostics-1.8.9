package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Common screen behaviour: dark backdrop, header, subtle slide-in animation, toasts, tooltips and
 * keyboard navigation (Tab / Shift+Tab cycles fields and buttons, Enter activates, Esc goes back).
 */
public abstract class GuiBase extends GuiScreen {
    protected final GuiScreen parent;
    protected final String title;
    protected final List<GuiTextField> fields = new ArrayList<GuiTextField>();
    protected final List<String> placeholders = new ArrayList<String>();
    protected final NetDiag nd = NetDiag.get();
    private long openedAt;
    private int focusIndex = -1;
    private String toast;
    private int toastColor;
    private long toastUntil;

    protected GuiBase(GuiScreen parent, String title) {
        this.parent = parent;
        this.title = title;
    }

    @Override
    public final void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        fields.clear();
        placeholders.clear();
        focusIndex = -1;
        if (openedAt == 0) {
            openedAt = System.currentTimeMillis();
        }
        layout();
    }

    /** Create buttons and fields for the current width/height. */
    protected abstract void layout();

    /** Draw content behind the buttons. */
    protected abstract void drawContent(int mouseX, int mouseY, float partialTicks);

    /** Draw content above the buttons (tooltips, popups). */
    protected void drawOverlay(int mouseX, int mouseY) {
    }

    protected String subtitle() {
        return null;
    }

    protected int headerHeight() {
        return 26;
    }

    protected FlatButton button(int id, int x, int y, int w, int h, String text, FlatButton.Style style) {
        FlatButton b = new FlatButton(id, x, y, w, h, text, style);
        buttonList.add(b);
        return b;
    }

    protected GuiTextField field(int id, int x, int y, int w, String value, String placeholder, int maxLen) {
        GuiTextField f = new GuiTextField(id, fontRendererObj, x + 4, y + 4, w - 8, 12);
        f.setEnableBackgroundDrawing(false);
        f.setMaxStringLength(maxLen);
        f.setText(value == null ? "" : value);
        f.setTextColor(Theme.TEXT);
        fields.add(f);
        placeholders.add(placeholder);
        return f;
    }

    protected void toast(String msg, int color) {
        toast = msg;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 3500;
    }

    protected void copy(String text, String what) {
        GuiScreen.setClipboardString(text);
        toast("Copied " + what + " to clipboard", Theme.GOOD);
    }

    protected void back() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        for (GuiTextField f : fields) {
            f.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, Theme.BACKDROP);
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, (1f - ease) * 6f, 0);

        Draw.text(fontRendererObj, title, 10, 9, Theme.TEXT);
        String sub = subtitle();
        if (sub != null) {
            Draw.textClipped(fontRendererObj, sub, 14 + fontRendererObj.getStringWidth(title), 9,
                    width - 30 - fontRendererObj.getStringWidth(title) - 140, Theme.TEXT_FAINT);
        }
        Draw.rect(8, headerHeight() - 4, width - 8, headerHeight() - 3, Theme.BORDER);

        drawContent(mouseX, mouseY, partialTicks);
        for (GuiButton b : buttonList) {
            b.drawButton(mc, mouseX, mouseY);
        }
        for (int i = 0; i < fields.size(); i++) {
            GuiTextField f = fields.get(i);
            if (!f.getVisible()) {
                continue;
            }
            int bx = f.xPosition - 4;
            int by = f.yPosition - 4;
            Draw.roundRect(bx - 1, by - 1, f.width + 10, 20, 4, f.isFocused() ? Theme.ACCENT : Theme.BORDER);
            Draw.roundRect(bx, by, f.width + 8, 18, 3, Theme.FIELD);
            if (f.getText().isEmpty() && !f.isFocused() && placeholders.get(i) != null) {
                Draw.textClipped(fontRendererObj, placeholders.get(i), f.xPosition, f.yPosition + 1, f.width,
                        Theme.TEXT_FAINT);
            }
            f.drawTextBox();
        }
        GlStateManager.popMatrix();

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
            int tx = (width - tw) / 2;
            int ty = height - 44;
            Draw.card(tx, ty, tw, 16, Theme.CARD_RAISED, toastColor);
            Draw.textCentered(fontRendererObj, toast, width / 2f, ty + 4, Theme.TEXT);
        }
    }

    // ------------------------------------------------------------------ input

    /** Return true if the key was consumed. */
    protected boolean onKey(char c, int key) {
        return false;
    }

    protected boolean anyFieldFocused() {
        for (GuiTextField f : fields) {
            if (f.isFocused() && f.getVisible()) {
                return true;
            }
        }
        return false;
    }

    private List<Object> focusables() {
        List<Object> out = new ArrayList<Object>();
        for (GuiTextField f : fields) {
            if (f.getVisible()) {
                out.add(f);
            }
        }
        for (GuiButton b : buttonList) {
            if (b.visible && b.enabled && b instanceof FlatButton) {
                out.add(b);
            }
        }
        return out;
    }

    private void setFocus(int idx) {
        List<Object> list = focusables();
        for (GuiTextField f : fields) {
            f.setFocused(false);
        }
        for (GuiButton b : buttonList) {
            if (b instanceof FlatButton) {
                ((FlatButton) b).focused = false;
            }
        }
        if (list.isEmpty()) {
            focusIndex = -1;
            return;
        }
        focusIndex = ((idx % list.size()) + list.size()) % list.size();
        Object o = list.get(focusIndex);
        if (o instanceof GuiTextField) {
            ((GuiTextField) o).setFocused(true);
        } else {
            ((FlatButton) o).focused = true;
        }
    }

    protected void clearKeyboardFocus() {
        for (GuiButton b : buttonList) {
            if (b instanceof FlatButton) {
                ((FlatButton) b).focused = false;
            }
        }
        focusIndex = -1;
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) {
            back();
            return;
        }
        if (key == Keyboard.KEY_TAB) {
            setFocus(focusIndex + (isShiftKeyDown() ? -1 : 1));
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            List<Object> list = focusables();
            if (focusIndex >= 0 && focusIndex < list.size() && list.get(focusIndex) instanceof FlatButton) {
                FlatButton b = (FlatButton) list.get(focusIndex);
                b.playPressSound(mc.getSoundHandler());
                actionPerformed(b);
                return;
            }
        }
        for (GuiTextField f : fields) {
            if (f.isFocused() && f.getVisible()) {
                if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                    if (onKey(c, key)) {
                        return;
                    }
                }
                f.textboxKeyTyped(c, key);
                return;
            }
        }
        onKey(c, key);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        clearKeyboardFocus();
        for (GuiTextField f : fields) {
            if (f.getVisible()) {
                // enlarge the hit box to the drawn background
                boolean inside = mouseX >= f.xPosition - 4 && mouseX < f.xPosition + f.width + 4
                        && mouseY >= f.yPosition - 4 && mouseY < f.yPosition + 14;
                f.setFocused(inside);
                if (inside) {
                    f.mouseClicked(Math.max(f.xPosition, mouseX), f.yPosition + 2, button);
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    protected static List<String> lines(String... s) {
        return Arrays.asList(s);
    }
}
