package com.seekehr.netdiagnostics.route;

import com.seekehr.netdiagnostics.helper.Snapshot;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteAnalysisTest {
    private static Snapshot.Relay relay(Snapshot s, String ip, int pid, String process, boolean inUse) {
        Snapshot.Relay r = new Snapshot.Relay();
        r.protocol = "TCP";
        r.ip = ip;
        r.port = 1000;
        r.pid = pid;
        r.process = process;
        r.inUse = inUse;
        r.verified = true;
        r.pathKey = "relay:TCP:" + ip + ":1000";
        s.relays.add(r);
        return r;
    }

    private static void path(Snapshot s, String ip, String state, long updatedAt) {
        Snapshot.Path p = new Snapshot.Path();
        p.key = "relay:TCP:" + ip + ":1000";
        p.kind = "relay";
        p.target = ip;
        p.state = state;
        p.updatedAt = updatedAt;
        s.paths.add(p);
    }

    @Test
    public void onlyRelaysOfTheProcessCarryingTheGame() {
        Snapshot s = new Snapshot();
        relay(s, "1.1.1.1", 100, "ExitLag.exe", true);
        relay(s, "2.2.2.2", 101, "ExitLagPmService.exe", true); // same product, other process
        relay(s, "3.3.3.3", 200, "NoPing.exe", true); // another optimizer, not carrying the game
        relay(s, "4.4.4.4", 100, "ExitLag.exe", false); // not in use
        List<Snapshot.Relay> c = RouteAnalysis.candidates(s, 100, "ExitLag");
        assertEquals(2, c.size());
        assertEquals("1.1.1.1", c.get(0).ip);
        assertEquals("2.2.2.2", c.get(1).ip);
    }

    @Test
    public void unknownProcessIsFollowedByPid() {
        Snapshot s = new Snapshot();
        relay(s, "5.5.5.5", 300, "MyTunnel.exe", true);
        relay(s, "1.1.1.1", 100, "ExitLag.exe", true);
        List<Snapshot.Relay> c = RouteAnalysis.candidates(s, 300, "MyTunnel");
        assertEquals(1, c.size());
        assertEquals("5.5.5.5", c.get(0).ip);
    }

    @Test
    public void withoutAPidOnlyKnownProxiesCount() {
        Snapshot s = new Snapshot();
        relay(s, "6.6.6.6", 400, "wireguard.exe", true);
        relay(s, "7.7.7.7", 401, "chrome.exe", true);
        List<Snapshot.Relay> c = RouteAnalysis.candidates(s, -1, null);
        assertEquals(1, c.size());
        assertEquals("6.6.6.6", c.get(0).ip);
    }

    @Test
    public void onlyTracesFinishedInThisRoundCount() {
        Snapshot s = new Snapshot();
        relay(s, "1.1.1.1", 100, "ExitLag.exe", true);
        relay(s, "2.2.2.2", 100, "ExitLag.exe", true);
        relay(s, "3.3.3.3", 100, "ExitLag.exe", true);
        path(s, "1.1.1.1", "done", 5000); // fresh
        path(s, "2.2.2.2", "done", 1000); // from an earlier round
        path(s, "3.3.3.3", "running", 6000); // not finished
        List<Snapshot.Relay> c = RouteAnalysis.candidates(s, 100, "ExitLag");
        assertEquals(1, RouteAnalysis.traced(s, c, 4000));
    }

    @Test
    public void reportSnapshotDropsOtherProxies() {
        Snapshot s = new Snapshot();
        s.proxyRunning = true;
        relay(s, "1.1.1.1", 100, "ExitLag.exe", true);
        relay(s, "3.3.3.3", 200, "NoPing.exe", true);
        relay(s, "8.8.8.8", 100, "ExitLag.exe", false); // idle ones of the same proxy stay (listed as idle)
        Snapshot g = RouteAnalysis.onlyGameProxy(s, 100, "ExitLag");
        assertEquals(2, g.relays.size());
        for (Snapshot.Relay r : g.relays) {
            assertFalse(r.process.startsWith("NoPing"));
        }
        assertTrue(g.proxyRunning);
    }

    @Test
    public void intervalsCycleThroughOff() {
        assertEquals(15, RouteAnalysis.INTERVALS[0]);
        assertEquals(0, RouteAnalysis.INTERVALS[RouteAnalysis.INTERVALS.length - 1]);
        RouteAnalysis ra = new RouteAnalysis();
        assertEquals(-1, ra.nextUpdateAt()); // nothing finished yet
        ra.setIntervalSec(-5);
        assertEquals(0, ra.intervalSec());
    }
}
