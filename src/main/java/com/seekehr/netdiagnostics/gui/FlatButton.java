package com.seekehr.netdiagnostics.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** Flat, rounded button with an eased hover transition and keyboard-focus ring. */
public class FlatButton extends GuiButton {
    public enum Style {
        NORMAL, PRIMARY, DANGER, TAB, TAB_ACTIVE, GHOST
    }

    public Style style;
    public String subtitle;
    public String tooltip;
    public boolean focused;
    private float hover;
    private long lastFrame;

    public FlatButton(int id, int x, int y, int w, int h, String text, Style style) {
        super(id, x, y, w, h, text);
        this.style = style;
    }

    public FlatButton tip(String t) {
        tooltip = t;
        return this;
    }

    public FlatButton sub(String s) {
        subtitle = s;
        return this;
    }

    public void setBounds(int x, int y, int w, int h) {
        xPosition = x;
        yPosition = y;
        width = w;
        height = h;
    }

    public boolean isHovered(int mx, int my) {
        return visible && mx >= xPosition && my >= yPosition && mx < xPosition + width && my < yPosition + height;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        hovered = isHovered(mouseX, mouseY);
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float target = (hovered || focused) && enabled ? 1f : 0f;
        hover += (target - hover) * Math.min(1f, dt * 14f);

        int base;
        int hi;
        switch (style) {
            case PRIMARY:
                base = Theme.BUTTON_PRIMARY;
                hi = Theme.BUTTON_PRIMARY_HOVER;
                break;
            case DANGER:
                base = Theme.BUTTON_DANGER;
                hi = Theme.BUTTON_DANGER_HOVER;
                break;
            case TAB_ACTIVE:
                base = Theme.CARD_RAISED;
                hi = Theme.CARD_RAISED;
                break;
            case TAB:
            case GHOST:
                base = 0x00000000;
                hi = Theme.BUTTON_HOVER;
                break;
            default:
                base = Theme.BUTTON;
                hi = Theme.BUTTON_HOVER;
        }
        int fill = Theme.lerp(base, hi, hover);
        if (!enabled) {
            fill = Theme.withAlpha(Theme.BUTTON, 0.55f);
        }
        if (focused) {
            Draw.roundRect(xPosition - 1, yPosition - 1, width + 2, height + 2, 4, Theme.FOCUS);
        }
        if ((fill >>> 24) != 0) {
            Draw.roundRect(xPosition, yPosition, width, height, 3, fill);
        }
        if (style == Style.TAB_ACTIVE) {
            Draw.rect(xPosition + 3, yPosition + height - 2, xPosition + width - 3, yPosition + height, Theme.ACCENT);
        }
        int color = !enabled ? Theme.TEXT_FAINT : (style == Style.TAB ? Theme.TEXT_DIM : Theme.TEXT);
        if (subtitle != null && height >= 22) {
            int ty = yPosition + (height - 18) / 2;
            Draw.textCentered(mc.fontRendererObj, Draw.clip(mc.fontRendererObj, displayString, width - 6),
                    xPosition + width / 2f, ty, color);
            Draw.textCentered(mc.fontRendererObj, Draw.clip(mc.fontRendererObj, subtitle, width - 6),
                    xPosition + width / 2f, ty + 10, enabled ? Theme.withAlpha(Theme.TEXT, 0.7f) : Theme.TEXT_FAINT);
        } else {
            Draw.textCentered(mc.fontRendererObj, Draw.clip(mc.fontRendererObj, displayString, width - 6),
                    xPosition + width / 2f, yPosition + (height - 8) / 2f, color);
        }
    }
}
