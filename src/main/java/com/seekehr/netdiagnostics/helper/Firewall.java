package com.seekehr.netdiagnostics.helper;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * The one temporary Windows Firewall change Advanced Tracing makes: inbound ICMPv4 Time Exceeded (type 11) and
 * Destination Unreachable (type 3), allowed only for the helper's own Java executable. Windows has no default
 * inbound rule for ICMPv4 Time Exceeded, and packets a program's firewall rules don't allow never reach its capture
 * socket, so without this the routers on a path stay invisible. ICMP errors cannot open connections. The rule
 * is removed when the helper exits, and any stale copy is removed when it starts.
 */
final class Firewall {
    static final String RULE = "SeekerNetDiagnostics Advanced Tracing (temporary, ICMP errors)";

    private Firewall() {
    }

    /** Adds the rule for {@code program}; returns null on success or a short error. */
    static String allowIcmpErrors(String program) {
        remove();
        for (String type : new String[]{"11", "3"}) {
            int rc = netsh("advfirewall", "firewall", "add", "rule", "name=" + RULE, "dir=in", "action=allow",
                    "enable=yes", "profile=any", "program=" + program, "protocol=icmpv4:" + type + ",any");
            if (rc != 0) {
                return "netsh exit code " + rc;
            }
        }
        return null;
    }

    static void remove() {
        netsh("advfirewall", "firewall", "delete", "rule", "name=" + RULE);
    }

    private static int netsh(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "netsh";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[1024];
            while (in.read(buf) >= 0) {
                // drain; output is in the console code page and not needed
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroy();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
