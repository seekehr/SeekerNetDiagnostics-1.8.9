package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.monitor.PingMonitor;
import com.seekehr.netdiagnostics.mod.HudConfig;
import com.seekehr.netdiagnostics.mod.PingHud;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;

/**
 * Ping HUD settings: which extra stats to show and how the HUD looks, with a live preview. Left-click a button to
 * step forward through its options, right-click to step back. Every change is saved at once.
 */
public final class GuiHudSettings extends GuiBase {
    private static final int S_AVG = 10, S_MIN = 11, S_MAX = 12, S_JITTER = 13, S_LOSS = 14, S_CONNECT = 15, S_TAB = 16;
    private static final int L_STYLE = 20, L_COLOR = 21, L_VALUES = 22, L_SHADOW = 23, L_BG = 24, L_SCALE = 25,
            L_LAYOUT = 26, L_RESET = 27;
    private static final int B_DONE = 30;
    private static final int COL_W = 150;
    private static final int ROW = 18;

    private final GuiScreen parent;
    private int colorButtonX;
    private int colorButtonY;
    private int listTop;
    private int listBottom;

    public GuiHudSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    protected String title() {
        return "Ping HUD settings";
    }

    @Override
    protected String status() {
        return "Right-click a button to go back an option";
    }

    @Override
    protected void layout() {
        int left = width / 2 - COL_W - 5;
        int right = width / 2 + 5;
        listTop = HEADER + 16;
        int y = listTop;
        stat(S_AVG, left, y, "Average", HudConfig.showAvg, "Average ping over the samples kept.");
        stat(S_MIN, left, y += ROW, "Min", HudConfig.showMin, "Lowest ping seen.");
        stat(S_MAX, left, y += ROW, "Max", HudConfig.showMax, "Highest ping seen.");
        stat(S_JITTER, left, y += ROW, "Jitter", HudConfig.showJitter,
                "How much the ping changes from one sample to the next. High jitter feels laggy even at low ping.");
        stat(S_LOSS, left, y += ROW, "Packet loss", HudConfig.showLoss, "Share of pings that failed or timed out.");
        stat(S_CONNECT, left, y += ROW, "Connect latency", HudConfig.showConnect,
                "Time to open a TCP connection to the server on the last ping (network round trip only).");
        stat(S_TAB, left, y += ROW, "Tab-list ping", HudConfig.showTabPing,
                "The ping the server shows for you in the tab list. Updates slowly and is averaged by the server.");
        int statsBottom = y + ROW;

        y = listTop;
        button(L_STYLE, right, y, COL_W, 16, "Style: " + PingHud.STYLES[HudConfig.style], FlatButton.Style.NORMAL)
                .tip("Box, rounded box, box with a bar coloured by your ping, or plain text.");
        colorButtonX = right;
        colorButtonY = y += ROW;
        button(L_COLOR, right, y, COL_W, 16, "Text colour: " + PingHud.COLOR_NAMES[HudConfig.textColor],
                FlatButton.Style.NORMAL);
        button(L_VALUES, right, y += ROW, COL_W, 16, "Values: " + (HudConfig.latencyColors ? "By quality" : "Plain"),
                FlatButton.Style.NORMAL).tip("By quality: numbers turn green, yellow or red depending on how good they "
                + "are. Plain: numbers use the text colour.");
        button(L_SHADOW, right, y += ROW, COL_W, 16, "Text shadow: " + (HudConfig.shadow ? "On" : "Off"),
                FlatButton.Style.NORMAL);
        FlatButton bg = button(L_BG, right, y += ROW, COL_W, 16, "Background: " + HudConfig.bgOpacity + "%",
                FlatButton.Style.NORMAL);
        bg.enabled = HudConfig.style != PingHud.STYLE_TEXT;
        bg.tip("How dark the box behind the text is.");
        button(L_SCALE, right, y += ROW, COL_W, 16, "Size: " + HudConfig.scalePercent + "%", FlatButton.Style.NORMAL);
        button(L_LAYOUT, right, y += ROW, COL_W, 16, "Layout: " + (HudConfig.stacked ? "Stacked" : "One line"),
                FlatButton.Style.NORMAL).tip("One line: every stat side by side. Stacked: one stat per line.");
        button(L_RESET, right, y += ROW, COL_W, 16, "Reset style", FlatButton.Style.GHOST)
                .tip("Back to the default look. Your stats and HUD position are kept.");
        listBottom = Math.max(statsBottom, y + ROW);

        button(B_DONE, width / 2 - 50, height - 24, 100, 16, "Done", FlatButton.Style.PRIMARY);
    }

    private void stat(int id, int x, int y, String name, boolean on, String tip) {
        button(id, x, y, COL_W, 16, name + ": " + (on ? "Shown" : "Hidden"),
                on ? FlatButton.Style.PRIMARY : FlatButton.Style.NORMAL).tip(tip);
    }

    @Override
    protected void drawContent(int mouseX, int mouseY) {
        Draw.text(fontRendererObj, "STATS", width / 2f - COL_W - 5, listTop - 11, Theme.TEXT_FAINT);
        Draw.text(fontRendererObj, "Ping is always shown", width / 2f - COL_W - 5 + 36, listTop - 11,
                Theme.withAlpha(Theme.TEXT_FAINT, 0.7f));
        Draw.text(fontRendererObj, "STYLE", width / 2f + 5, listTop - 11, Theme.TEXT_FAINT);

        // Preview on a mid-tone card, roughly like the game world behind the real HUD.
        int top = listBottom + 6;
        int bottom = height - 30;
        int pw = COL_W * 2 + 10;
        int px = width / 2 - pw / 2;
        if (bottom - top < 20) {
            return;
        }
        Draw.roundRect(px, top, pw, bottom - top, 4, 0xFF56677A);
        Draw.text(fontRendererObj, "Preview", px + 4, top + 3, 0xFFDDE4EC);
        PingMonitor.Snapshot s = GuiHudEditor.snap();
        int[] size = PingHud.size(fontRendererObj, s);
        int hx = width / 2 - size[0] / 2;
        int hy = top + 12 + Math.max(0, (bottom - top - 12 - size[1]) / 2);
        PingHud.draw(fontRendererObj, hx, hy, s);
    }

    @Override
    protected void drawOverlay(int mouseX, int mouseY) {
        // swatch showing the current text colour
        int sx = colorButtonX + COL_W - 12;
        int sy = colorButtonY + 4;
        Draw.rect(sx - 1, sy - 1, sx + 9, sy + 9, Theme.BORDER);
        Draw.rect(sx, sy, sx + 8, sy + 8, PingHud.COLORS[HudConfig.textColor]);
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == B_DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        change(b.id, 1);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (button == 1) {
            for (GuiButton b : buttonList) {
                if (b.enabled && b instanceof FlatButton && ((FlatButton) b).isHovered(mouseX, mouseY)
                        && b.id != B_DONE) {
                    mc.getSoundHandler().playSound(
                            PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
                    change(b.id, -1);
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // Escape goes back to the HUD editor
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** Applies one step of change to the setting behind button {@code id}; dir is +1 or -1. */
    private void change(int id, int dir) {
        switch (id) {
            case S_AVG: HudConfig.showAvg = !HudConfig.showAvg; break;
            case S_MIN: HudConfig.showMin = !HudConfig.showMin; break;
            case S_MAX: HudConfig.showMax = !HudConfig.showMax; break;
            case S_JITTER: HudConfig.showJitter = !HudConfig.showJitter; break;
            case S_LOSS: HudConfig.showLoss = !HudConfig.showLoss; break;
            case S_CONNECT: HudConfig.showConnect = !HudConfig.showConnect; break;
            case S_TAB: HudConfig.showTabPing = !HudConfig.showTabPing; break;
            case L_STYLE: HudConfig.style = step(HudConfig.style, PingHud.STYLES.length, dir); break;
            case L_COLOR: HudConfig.textColor = step(HudConfig.textColor, PingHud.COLORS.length, dir); break;
            case L_VALUES: HudConfig.latencyColors = !HudConfig.latencyColors; break;
            case L_SHADOW: HudConfig.shadow = !HudConfig.shadow; break;
            case L_BG: HudConfig.bgOpacity = stepValue(HudConfig.bgOpacity, PingHud.OPACITIES, dir); break;
            case L_SCALE: HudConfig.scalePercent = stepValue(HudConfig.scalePercent, PingHud.SCALES, dir); break;
            case L_LAYOUT: HudConfig.stacked = !HudConfig.stacked; break;
            case L_RESET: HudConfig.resetStyle(); break;
            default: return;
        }
        HudConfig.save();
        initGui(); // relabel buttons and re-measure the preview
    }

    private static int step(int i, int count, int dir) {
        return ((i + dir) % count + count) % count;
    }

    private static int stepValue(int v, int[] values, int dir) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) {
                return values[step(i, values.length, dir)];
            }
        }
        return values[0];
    }
}
