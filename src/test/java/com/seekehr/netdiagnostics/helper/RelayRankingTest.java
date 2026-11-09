package com.seekehr.netdiagnostics.helper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Uses the relays and traces from a real ExitLag session (as.minemen.club, ~100 ms game ping). */
public class RelayRankingTest {
    private static final double GAME = 100;

    private static void relay(Snapshot s, String ip, int port, double pps, double rtt, long connSeconds) {
        Snapshot.Relay r = new Snapshot.Relay();
        r.protocol = "TCP";
        r.ip = ip;
        r.port = port;
        r.process = "ExitLag.exe";
        r.packetsOutPerSec = pps;
        r.verified = true;
        r.inUse = true;
        r.connSeconds = connSeconds;
        r.pathKey = RelayMonitor.relayKey("TCP", ip, port);
        s.relays.add(r);
        if (!Double.isNaN(rtt)) {
            Snapshot.Path p = new Snapshot.Path();
            p.key = r.pathKey;
            p.kind = "relay";
            p.protocol = "ICMP";
            p.target = ip;
            p.port = port;
            p.reached = true;
            Snapshot.Hop h = new Snapshot.Hop();
            h.ttl = 10;
            h.address = ip;
            h.sent = 3;
            h.received = 3;
            h.avg = rtt;
            h.destination = true;
            p.hops.add(h);
            s.paths.add(p);
        }
    }

    private static Snapshot session(long longLivedOn194) {
        Snapshot s = new Snapshot();
        relay(s, "156.59.154.142", 15196, 70, 22, -1);
        relay(s, "103.216.223.145", 10200, 53, 100, -1);
        relay(s, "5.223.86.16", 26066, 39, 96, -1);
        relay(s, "194.114.138.168", 35357, 15, 100, longLivedOn194);
        relay(s, "23.109.121.245", 39134, 13, 97, -1);
        relay(s, "51.79.228.2", 53207, 0, 96, -1);
        relay(s, "159.223.77.137", 32911, 0, 97, -1);
        return s;
    }

    private static List<String> accepted(List<RelayRanking.Entry> ranked) {
        List<String> out = new ArrayList<String>();
        for (RelayRanking.Entry e : ranked) {
            if (e.accepted) {
                out.add(e.relay.ip);
            }
        }
        return out;
    }

    @Test
    public void showsAtMostThreeAndDropsIdleRelays() {
        List<RelayRanking.Entry> ranked = RelayRanking.rank(session(-1), GAME, 3);
        List<String> acc = accepted(ranked);
        assertEquals(3, acc.size());
        assertEquals("156.59.154.142", acc.get(0));
        assertFalse(acc.contains("51.79.228.2"));
        assertFalse(acc.contains("159.223.77.137"));
        assertEquals(7, ranked.size());
        for (RelayRanking.Entry e : ranked) {
            if (e.relay.ip.equals("51.79.228.2")) {
                assertTrue(e.reason.contains("no packets"));
            }
        }
        RelayRanking.Entry first = ranked.get(0);
        assertEquals(22, first.rttMs, 1e-9);
        assertEquals(78, first.beyondMs, 1e-9); // the part inside the tunnel
    }

    @Test
    public void longLivedConnectionWinsOverShortPingTraffic() {
        List<String> acc = accepted(RelayRanking.rank(session(120), GAME, 3));
        assertEquals("194.114.138.168", acc.get(0));
    }

    @Test
    public void relayFartherThanTheGamePingIsRuledOut() {
        Snapshot s = session(-1);
        s.relays.get(0).packetsOutPerSec = 500; // busiest, but...
        ((Snapshot.Path) s.path(s.relays.get(0).pathKey)).hops.get(0).avg = 150; // ...150 ms away
        List<RelayRanking.Entry> ranked = RelayRanking.rank(s, GAME, 3);
        assertFalse(accepted(ranked).contains("156.59.154.142"));
        for (RelayRanking.Entry e : ranked) {
            if (e.relay.ip.equals("156.59.154.142")) {
                assertTrue(e.reason.startsWith("Ruled out"));
            }
        }
        // within tolerance (100 * 1.1 + 5 = 115) is still fine
        s.path(s.relays.get(0).pathKey).hops.get(0).avg = 112;
        assertTrue(accepted(RelayRanking.rank(s, GAME, 3)).contains("156.59.154.142"));
    }

    @Test
    public void trivialTrafficIsNotPaddedIntoTheList() {
        Snapshot s = new Snapshot();
        relay(s, "1.1.1.1", 1, 100, 20, -1);
        relay(s, "2.2.2.2", 2, 5, 20, -1); // 5% of traffic: far below a quarter of the top score
        assertEquals(1, accepted(RelayRanking.rank(s, GAME, 3)).size());
    }

    @Test
    public void unknownGamePingStillRanksByTraffic() {
        List<RelayRanking.Entry> ranked = RelayRanking.rank(session(-1), Double.NaN, 2);
        assertEquals(2, accepted(ranked).size());
        assertTrue(Double.isNaN(ranked.get(0).beyondMs));
    }

    @Test
    public void untracedRelayIsKeptButMarked() {
        Snapshot s = new Snapshot();
        relay(s, "9.9.9.9", 9, 50, Double.NaN, 60);
        RelayRanking.Entry e = RelayRanking.rank(s, GAME, 3).get(0);
        assertTrue(e.accepted);
        assertTrue(e.reason.contains("not traced yet"));
    }
}
