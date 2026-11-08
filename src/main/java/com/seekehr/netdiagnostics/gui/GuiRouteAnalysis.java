package com.seekehr.netdiagnostics.gui;

import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.test.TestRun;
import com.seekehr.netdiagnostics.mod.NetDiag;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Advanced Route Analysis. In-process traceroute is deliberately disabled: Java cannot send
 * TTL-limited TCP SYNs or read ICMP time-exceeded replies without native raw-socket access (which needs
 * elevated permissions), and any substitute would produce misleading data. This panel explains why and
 * makes it easy to take the target to external tools.
 */
public final class GuiRouteAnalysis extends GuiBase {
    private static final int ID_BACK = 1;
    private static final int ID_COPY_IP = 2;
    private static final int ID_COPY_IPPORT = 3;
    private static final int ID_EXPORT = 4;

    private String host;
    private String ip;
    private int port;
    private String source;

    public GuiRouteAnalysis(GuiScreen parent) {
        super(parent, "Advanced Route Analysis");
        String remote = NetDiag.currentRemoteAddress();
        Target cur = NetDiag.currentServerTarget();
        if (cur != null && remote != null) {
            int idx = remote.lastIndexOf(':');
            host = cur.host;
            ip = idx > 0 ? remote.substring(0, idx) : remote;
            port = idx > 0 ? Integer.parseInt(remote.substring(idx + 1)) : cur.port;
            source = "current server connection";
        } else {
            TestRun r = nd.results.latest();
            if (r != null) {
                host = r.target.host;
                ip = r.resolvedAddress;
                port = r.connectPort;
                source = "most recent test";
            }
        }
    }

    @Override
    protected void layout() {
        int by = height - 24;
        button(ID_BACK, 8, by, 60, 16, "Back", FlatButton.Style.NORMAL);
        boolean has = host != null;
        button(ID_COPY_IP, 72, by, 70, 16, "Copy IP", FlatButton.Style.NORMAL).enabled = has;
        button(ID_COPY_IPPORT, 146, by, 84, 16, "Copy IP:port", FlatButton.Style.NORMAL).enabled = has;
        button(ID_EXPORT, 234, by, 110, 16, "Export target file", FlatButton.Style.NORMAL)
                .tip("Writes config/netscope/reports/target-<host>-<port>.txt with the address and suggested "
                        + "commands to run yourself.").enabled = has;
    }

    private String addr() {
        return ip != null ? ip : host;
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == ID_BACK) {
            back();
        } else if (b.id == ID_COPY_IP && host != null) {
            copy(addr(), addr());
        } else if (b.id == ID_COPY_IPPORT && host != null) {
            String s = (addr().indexOf(':') >= 0 ? "[" + addr() + "]" : addr()) + ":" + port;
            copy(s, s);
        } else if (b.id == ID_EXPORT && host != null) {
            nd.exportTarget(host, ip, port, new NetDiag.ExportCallback() {
                @Override
                public void done(List<File> files, String error) {
                    toast(error != null ? "Export failed: " + error : "Saved " + files.get(0).getName(),
                            error != null ? Theme.BAD : Theme.GOOD);
                }
            });
        }
    }

    private static final String[] POINTS = {
            "Conventional traceroute (tracert, pathping) uses ICMP. Process-based optimizers such as ExitLag "
                    + "usually do not route ICMP, so those probes can take your normal ISP path instead of the "
                    + "optimized game route.",
            "A TCP traceroute needs TTL-limited TCP packets and the ICMP replies they trigger. Java cannot do "
                    + "this without native raw-socket access, which requires administrator rights.",
            "Even a TCP traceroute is not guaranteed to follow the optimized route: process-level routing "
                    + "decisions do not necessarily apply to probe packets.",
            "Minecraft proxies (BungeeCord, Velocity, DDoS protection) can hide the actual backend server. "
                    + "This mod does not and cannot discover hidden backend addresses.",
    };

    @Override
    protected void drawContent(int mouseX, int mouseY, float partialTicks) {
        int x = 8;
        int w = width - 16;
        int top = 28;
        int bottom = height - 30;
        Draw.card(x, top, w, bottom - top, Theme.CARD, Theme.BORDER);
        int y = top + 8;
        Draw.roundRect(x + 8, y - 2, w - 16, 14, 3, Theme.withAlpha(Theme.WARN, 0.18f));
        Draw.text(fontRendererObj, "In-game traceroute: disabled (no reliable, permission-free implementation)",
                x + 12, y + 1, Theme.WARN);
        y += 18;
        for (String p : POINTS) {
            Draw.text(fontRendererObj, "•", x + 10, y, Theme.ACCENT);
            y += Draw.wrapped(fontRendererObj, p, x + 20, y, w - 30, Theme.TEXT, 4) + 4;
        }
        y += 4;
        Draw.text(fontRendererObj, "TARGET FOR EXTERNAL TOOLS", x + 10, y, Theme.TEXT_FAINT);
        y += 12;
        if (host == null) {
            Draw.text(fontRendererObj, "No target yet: join a server or run a test first.", x + 10, y, Theme.TEXT_DIM);
        } else {
            Draw.text(fontRendererObj, "Host: " + host, x + 10, y, Theme.TEXT);
            Draw.text(fontRendererObj, "IP: " + (ip == null ? "(not resolved)" : ip) + "   Port: " + port, x + 10,
                    y + 10, Theme.TEXT);
            Draw.text(fontRendererObj, "From: " + source, x + 10, y + 20, Theme.TEXT_FAINT);
            y += 34;
            if (y + 20 < bottom) {
                Draw.wrapped(fontRendererObj, "To compare paths yourself, run \"tracert " + addr() + "\" or \"pathping "
                        + addr() + "\" in a terminal, and check ExitLag's real-time monitor to confirm this "
                        + "destination is being optimized.", x + 10, y, w - 20, Theme.TEXT_DIM, 3);
            }
        }
    }
}
