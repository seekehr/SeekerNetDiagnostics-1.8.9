package com.seekehr.netdiagnostics.mod;

import com.seekehr.netdiagnostics.SeekerNetDiagnostics;
import com.seekehr.netdiagnostics.core.monitor.WinTcpTrace;
import com.seekehr.netdiagnostics.helper.HelperClient;
import com.seekehr.netdiagnostics.helper.HelperMain;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The "Enable Advanced Tracing (Admin)" switch. When on, a separate helper process is started with administrator
 * rights through UAC; Minecraft itself is never elevated. With it off nothing changes: no helper, no prompts.
 */
public final class AdvancedTracing {
    private static final HelperClient CLIENT = new HelperClient();

    private AdvancedTracing() {
    }

    public static HelperClient client() {
        return CLIENT;
    }

    public static boolean supported() {
        return WinTcpTrace.windows();
    }

    public static boolean enabled() {
        return HudConfig.advancedTracing && supported();
    }

    /** Turns the feature on (shows the UAC prompt) or off (stops the helper). Saved in the config. */
    public static void setEnabled(boolean on) {
        HudConfig.advancedTracing = on;
        HudConfig.save();
        if (on) {
            start();
        } else {
            CLIENT.stop();
        }
    }

    /** Called when a trace starts: points the helper at the server and starts it if enabled but not running. */
    public static void onTrace(String ip, int port) {
        CLIENT.setServer(ip, port);
        if (!enabled()) {
            return;
        }
        if (CLIENT.active()) {
            CLIENT.retrace();
        } else {
            start();
        }
    }

    private static void start() {
        if (!supported()) {
            return;
        }
        String cp = classpath();
        if (cp == null) {
            NetDiag.LOG.warn("Advanced tracing: could not locate the mod jar or JNA");
            return;
        }
        CLIENT.start(cp, javaExecutable());
    }

    /** The game's own Java runtime, so the helper uses the same JVM and the same firewall permissions. */
    static String javaExecutable() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File javaw = new File(bin, "javaw.exe");
        return (javaw.isFile() ? javaw : new File(bin, "java.exe")).getAbsolutePath();
    }

    /** The mod jar (or dev classes directory) plus the JNA jar shipped with Minecraft. */
    static String classpath() {
        Set<String> parts = new LinkedHashSet<String>();
        ModContainer mc = Loader.instance().getIndexedModList().get(SeekerNetDiagnostics.MODID);
        if (mc != null && mc.getSource() != null) {
            parts.add(mc.getSource().getAbsolutePath());
        }
        File self = codeSource(HelperMain.class);
        if (self != null) {
            parts.add(self.getAbsolutePath());
        }
        File jna = codeSource(com.sun.jna.Native.class);
        if (jna == null || parts.isEmpty()) {
            return null;
        }
        parts.add(jna.getAbsolutePath());
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(sb.length() == 0 ? "" : File.pathSeparator).append(p);
        }
        return sb.toString();
    }

    private static File codeSource(Class<?> c) {
        try {
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            URL u = cs.getLocation();
            String s = u.toString();
            if (s.startsWith("jar:")) {
                int bang = s.indexOf("!/");
                s = s.substring(4, bang < 0 ? s.length() : bang);
            }
            File f = new File(new URL(s).toURI());
            return f.exists() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }
}
