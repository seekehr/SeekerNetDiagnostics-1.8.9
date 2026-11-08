package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.mod.HudConfig;
import com.seekehr.netdiagnostics.mod.NetDiag;
import com.seekehr.netdiagnostics.mod.PingHud;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;

/** Drag the ping HUD to a new spot, or turn it off. Saves on close. */
public final class GuiHudEditor extends GuiScreen {
    private static final int B_TOGGLE = 0, B_RESET = 1, B_DONE = 2;

    private FlatButton toggle;
    private boolean dragging;
    private int grabDx;
    private int grabDy;

    @Override
    public void initGui() {
        buttonList.clear();
        int bw = 90;
        int x = width / 2 - (bw * 3 + 8) / 2;
        int y = height / 2 + 14;
        toggle = new FlatButton(B_TOGGLE, x, y, bw, 16, "", FlatButton.Style.NORMAL);
        buttonList.add(toggle);
        buttonList.add(new FlatButton(B_RESET, x + bw + 4, y, bw, 16, "Reset position", FlatButton.Style.NORMAL));
        buttonList.add(new FlatButton(B_DONE, x + (bw + 4) * 2, y, bw, 16, "Done", FlatButton.Style.PRIMARY));
        clampPosition();
        refresh();
    }

    private void refresh() {
        toggle.displayString = "HUD: " + (HudConfig.enabled ? "On" : "Off");
        toggle.style = HudConfig.enabled ? FlatButton.Style.NORMAL : FlatButton.Style.DANGER;
    }

    private void clampPosition() {
        HudConfig.x = PingHud.clampX(HudConfig.x, width, fontRendererObj);
        HudConfig.y = PingHud.clampY(HudConfig.y, height, fontRendererObj);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0x60000000);
        Draw.textCentered(fontRendererObj, "Drag the ping HUD to move it", width / 2f, height / 2f - 14, Theme.TEXT);
        Draw.textCentered(fontRendererObj, "Esc or Done to save", width / 2f, height / 2f - 3, Theme.TEXT_FAINT);

        int w = PingHud.previewWidth(fontRendererObj);
        int h = fontRendererObj.FONT_HEIGHT;
        boolean over = inside(mouseX, mouseY);
        int border = dragging ? Theme.ACCENT : over ? Theme.FOCUS : Theme.BORDER;
        Draw.rect(HudConfig.x - 2, HudConfig.y - 2, HudConfig.x + w + 2, HudConfig.y + h + 1, 0x80000000);
        Draw.outline(HudConfig.x - 2, HudConfig.y - 2, w + 4, h + 3, border);
        PingMonitor m = NetDiag.monitor();
        PingHud.draw(fontRendererObj, HudConfig.x, HudConfig.y,
                m != null && !m.stopped() && !m.snapshot().samples.isEmpty() ? m.snapshot() : null);
        if (!HudConfig.enabled) {
            Draw.rect(HudConfig.x - 2, HudConfig.y - 2, HudConfig.x + w + 2, HudConfig.y + h + 1, 0xA0000000);
            Draw.textCentered(fontRendererObj, "hidden", HudConfig.x + w / 2f, HudConfig.y, Theme.BAD);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private boolean inside(int mx, int my) {
        int w = PingHud.previewWidth(fontRendererObj);
        return mx >= HudConfig.x - 2 && mx < HudConfig.x + w + 2
                && my >= HudConfig.y - 2 && my < HudConfig.y + fontRendererObj.FONT_HEIGHT + 1;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 0 && inside(mouseX, mouseY)) {
            dragging = true;
            grabDx = mouseX - HudConfig.x;
            grabDy = mouseY - HudConfig.y;
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (dragging) {
            HudConfig.x = mouseX - grabDx;
            HudConfig.y = mouseY - grabDy;
            clampPosition();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == B_TOGGLE) {
            HudConfig.enabled = !HudConfig.enabled;
            refresh();
        } else if (b.id == B_RESET) {
            HudConfig.x = HudConfig.DEFAULT_X;
            HudConfig.y = HudConfig.DEFAULT_Y;
        } else if (b.id == B_DONE) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void onGuiClosed() {
        HudConfig.save();
    }
}
