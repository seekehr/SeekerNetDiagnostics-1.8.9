package com.seekehr.netdiagnostics.core.compare;

import com.seekehr.netdiagnostics.core.net.ProbeOutcome;
import com.seekehr.netdiagnostics.core.net.Target;
import com.seekehr.netdiagnostics.core.stats.ProfileComparison;
import com.seekehr.netdiagnostics.core.stats.RttStats;
import com.seekehr.netdiagnostics.core.stats.StatSummary;
import com.seekehr.netdiagnostics.core.test.TestRun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Route comparison: the user measures the same target under differently configured ExitLag
 * profiles (changed manually in ExitLag; the mod never controls ExitLag). Each profile can hold
 * several runs; statistics pool all of a profile's successful samples.
 *
 * <p>Repeat order follows ABBA (generalised to A B C C B A ...) so that slow drifts in network
 * conditions affect every profile roughly equally.</p>
 */
public final class CompareSession {
    public static final int MAX_PROFILES = 4;

    public static final class Profile {
        public final String label;
        public final String exitLagDescription;
        private final List<TestRun> runs = new ArrayList<TestRun>();

        Profile(String label, String exitLagDescription) {
            this.label = label;
            this.exitLagDescription = exitLagDescription == null ? "" : exitLagDescription;
        }

        public synchronized List<TestRun> runs() {
            return new ArrayList<TestRun>(runs);
        }

        synchronized void add(TestRun r) {
            runs.add(r);
        }

        public double[] pooledSamples() {
            List<double[]> parts = new ArrayList<double[]>();
            int n = 0;
            for (TestRun r : runs()) {
                double[] s = r.successfulSamples();
                parts.add(s);
                n += s.length;
            }
            double[] out = new double[n];
            int i = 0;
            for (double[] p : parts) {
                System.arraycopy(p, 0, out, i, p.length);
                i += p.length;
            }
            return out;
        }

        public StatSummary summary() {
            return RttStats.summarize(pooledSamples());
        }

        public int attempts() {
            int n = 0;
            for (TestRun r : runs()) {
                n += r.measuredAttempts();
            }
            return n;
        }

        public int count(ProbeOutcome o) {
            int n = 0;
            for (TestRun r : runs()) {
                n += r.count(o);
            }
            return n;
        }

        public int failures() {
            int n = 0;
            for (TestRun r : runs()) {
                n += r.failedAttempts();
            }
            return n;
        }

        public long durationMs() {
            long d = 0;
            for (TestRun r : runs()) {
                d += r.durationMs();
            }
            return d;
        }
    }

    private final Target target;
    private final int samplesPerRun;
    private final List<Profile> profiles = new ArrayList<Profile>();
    private int completedBlocks;

    public CompareSession(Target target, int samplesPerRun) {
        this.target = target;
        this.samplesPerRun = samplesPerRun;
    }

    public Target target() {
        return target;
    }

    public int samplesPerRun() {
        return samplesPerRun;
    }

    public synchronized List<Profile> profiles() {
        return new ArrayList<Profile>(profiles);
    }

    public synchronized Profile profile(String label) {
        for (Profile p : profiles) {
            if (p.label.equalsIgnoreCase(label)) {
                return p;
            }
        }
        return null;
    }

    /** Adds (or returns the existing) profile with this label. */
    public synchronized Profile addProfile(String label, String exitLagDescription) {
        Profile existing = profile(label);
        if (existing != null) {
            return existing;
        }
        if (profiles.size() >= MAX_PROFILES) {
            throw new IllegalStateException("At most " + MAX_PROFILES + " profiles can be compared at once.");
        }
        Profile p = new Profile(label, exitLagDescription);
        profiles.add(p);
        return p;
    }

    public synchronized void recordRun(Profile p, TestRun run) {
        p.add(run);
        completedBlocks++;
    }

    public synchronized int completedBlocks() {
        return completedBlocks;
    }

    /** Index into the ABBA-style order (A B B A for two profiles, A B C C B A for three...). */
    public static int abbaIndex(int block, int profileCount) {
        if (profileCount <= 1) {
            return 0;
        }
        int cycle = profileCount * 2;
        int pos = block % cycle;
        return pos < profileCount ? pos : cycle - 1 - pos;
    }

    /** The profile the user should measure next under ABBA ordering, or null if fewer than two exist. */
    public synchronized Profile suggestedNext() {
        if (profiles.size() < 2) {
            return null;
        }
        return profiles.get(abbaIndex(completedBlocks, profiles.size()));
    }

    /** Compare the baseline (first profile) with {@code other}. */
    public ProfileComparison compare(Profile baseline, Profile other) {
        return ProfileComparison.compare(baseline.pooledSamples(), other.pooledSamples());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("target", target.display());
        m.put("samplesPerRun", samplesPerRun);
        m.put("ordering", "ABBA (A B B A ...)");
        List<Object> ps = new ArrayList<Object>();
        List<Profile> list = profiles();
        for (Profile p : list) {
            Map<String, Object> pm = new LinkedHashMap<String, Object>();
            pm.put("label", p.label);
            pm.put("exitLagSettingsDescription", p.exitLagDescription);
            pm.put("runs", p.runs().size());
            pm.put("attempts", p.attempts());
            pm.put("timeouts", p.count(ProbeOutcome.TIMEOUT));
            pm.put("failures", p.failures());
            pm.put("durationMs", p.durationMs());
            pm.put("summary", TestRun.summaryMap(p.summary()));
            List<Object> ids = new ArrayList<Object>();
            for (TestRun r : p.runs()) {
                ids.add(r.id);
            }
            pm.put("runIds", ids);
            ps.add(pm);
        }
        m.put("profiles", ps);
        List<Object> comps = new ArrayList<Object>();
        for (int i = 1; i < list.size(); i++) {
            ProfileComparison c = compare(list.get(0), list.get(i));
            Map<String, Object> cm = new LinkedHashMap<String, Object>();
            cm.put("baseline", list.get(0).label);
            cm.put("candidate", list.get(i).label);
            cm.put("medianDifferenceMs", c.medianDiff);
            cm.put("ci95LowMs", c.ciLow);
            cm.put("ci95HighMs", c.ciHigh);
            cm.put("verdict", c.verdict.name());
            cm.put("recommendation", c.recommendation(list.get(0).label, list.get(i).label));
            comps.add(cm);
        }
        m.put("comparisons", comps);
        return m;
    }
}
