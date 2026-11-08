package com.seekehr.netdiagnostics.core.test;

import com.seekehr.netdiagnostics.core.json.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded history of finished test runs, persisted as JSON. Thread-safe.
 */
public final class ResultStore {
    public static final int MAX_RUNS = 100;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    public enum SortKey {
        DATE("Date"), HOST("Host"), MEDIAN("Median"), P95("p95"), FAILURES("Failures");

        public final String label;

        SortKey(String label) {
            this.label = label;
        }
    }

    private final List<TestRun> runs = new ArrayList<TestRun>(); // newest first
    private final File file;
    private volatile int version;

    public ResultStore(File file) {
        this.file = file;
    }

    public int version() {
        return version;
    }

    public synchronized void add(TestRun run) {
        runs.remove(run);
        runs.add(0, run);
        while (runs.size() > MAX_RUNS) {
            runs.remove(runs.size() - 1);
        }
        version++;
    }

    public synchronized void clear() {
        runs.clear();
        version++;
    }

    public synchronized List<TestRun> all() {
        return new ArrayList<TestRun>(runs);
    }

    public synchronized TestRun latest() {
        return runs.isEmpty() ? null : runs.get(0);
    }

    /** Most recent finished run to the same target before {@code run}, or null. */
    public synchronized TestRun previousFor(TestRun run) {
        boolean seen = false;
        for (TestRun r : runs) {
            if (r == run) {
                seen = true;
                continue;
            }
            if (seen && r.target.equals(run.target) && !r.summary().isEmpty()) {
                return r;
            }
        }
        return null;
    }

    /** Filter by case-insensitive substring of host/profile/type, then sort. */
    public List<TestRun> query(String filter, final SortKey key, final boolean ascending) {
        List<TestRun> out = new ArrayList<TestRun>();
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        for (TestRun r : all()) {
            if (f.isEmpty() || r.target.display().toLowerCase(Locale.ROOT).contains(f)
                    || (r.resolvedAddress != null && r.resolvedAddress.contains(f))
                    || (r.profileLabel != null && r.profileLabel.toLowerCase(Locale.ROOT).contains(f))) {
                out.add(r);
            }
        }
        Comparator<TestRun> c = new Comparator<TestRun>() {
            @Override
            public int compare(TestRun a, TestRun b) {
                switch (key) {
                    case HOST:
                        int h = a.target.host.compareToIgnoreCase(b.target.host);
                        return h != 0 ? h : a.target.port - b.target.port;
                    case MEDIAN:
                        return nanLast(a.summary().median, b.summary().median);
                    case P95:
                        return nanLast(a.summary().p95, b.summary().p95);
                    case FAILURES:
                        return a.failedAttempts() - b.failedAttempts();
                    case DATE:
                    default:
                        return Long.compare(a.startedAt, b.startedAt);
                }
            }
        };
        Collections.sort(out, ascending ? c : Collections.reverseOrder(c));
        return out;
    }

    private static int nanLast(double a, double b) {
        boolean an = Double.isNaN(a);
        boolean bn = Double.isNaN(b);
        if (an && bn) {
            return 0;
        }
        if (an) {
            return 1;
        }
        if (bn) {
            return -1;
        }
        return Double.compare(a, b);
    }

    // ------------------------------------------------------------------ persistence

    public void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("format", 2);
        List<Object> list = new ArrayList<Object>();
        for (TestRun r : all()) {
            if (r.finished()) {
                list.add(r.toMap());
            }
        }
        root.put("runs", list);
        byte[] data = Json.writeCompact(root).getBytes("UTF-8");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(file.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot replace " + file);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot rename " + tmp + " to " + file);
        }
    }

    /** Loads history; returns number of runs loaded. A corrupt file is ignored (never fatal). */
    public int load() {
        if (!file.isFile() || file.length() > MAX_FILE_BYTES) {
            return 0;
        }
        try {
            byte[] data = new byte[(int) file.length()];
            InputStream in = new FileInputStream(file);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            Map<String, Object> root = Json.obj(Json.parse(new String(data, "UTF-8"), MAX_FILE_BYTES, 32));
            List<Object> list = Json.arr(root == null ? null : root.get("runs"));
            if (list == null) {
                return 0;
            }
            int n = 0;
            synchronized (this) {
                runs.clear();
                for (Object o : list) {
                    TestRun r = TestRun.fromMap(Json.obj(o));
                    if (r != null && runs.size() < MAX_RUNS) {
                        runs.add(r);
                        n++;
                    }
                }
                version++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
