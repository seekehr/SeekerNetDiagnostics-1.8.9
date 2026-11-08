package com.seekehr.netdiagnostics.core.stats;

/**
 * Fixed-capacity circular buffer of timestamped samples. Allocation happens only in the constructor.
 *
 * <p>Not thread-safe by design: the live monitor writes and the GUI reads on the Minecraft client
 * thread, so no locking is needed on the render path. Producers on other threads must hand data
 * over through their own queue.</p>
 */
public final class SampleRing {
    public static final byte FLAG_NONE = 0;
    public static final byte FLAG_SPIKE = 1;

    private final long[] times;
    private final double[] values;
    private final byte[] flags;
    private int head; // index of next write
    private int size;
    private long totalWritten;

    public SampleRing(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        times = new long[capacity];
        values = new double[capacity];
        flags = new byte[capacity];
    }

    public int capacity() {
        return values.length;
    }

    public int size() {
        return size;
    }

    public long totalWritten() {
        return totalWritten;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void add(long timeMillis, double value) {
        add(timeMillis, value, FLAG_NONE);
    }

    public void add(long timeMillis, double value, byte flag) {
        times[head] = timeMillis;
        values[head] = value;
        flags[head] = flag;
        head = (head + 1) % values.length;
        if (size < values.length) {
            size++;
        }
        totalWritten++;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    private int physical(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= size) {
            throw new IndexOutOfBoundsException("index " + logicalIndex + " size " + size);
        }
        int start = (head - size + values.length) % values.length;
        return (start + logicalIndex) % values.length;
    }

    /** Value at logical index (0 = oldest retained sample). */
    public double value(int i) {
        return values[physical(i)];
    }

    public long time(int i) {
        return times[physical(i)];
    }

    public byte flag(int i) {
        return flags[physical(i)];
    }

    public void setFlag(int i, byte flag) {
        flags[physical(i)] = flag;
    }

    public double latestValue() {
        return size == 0 ? Double.NaN : value(size - 1);
    }

    public long latestTime() {
        return size == 0 ? 0L : time(size - 1);
    }

    /** First logical index whose timestamp is >= {@code sinceMillis} (size if none). Binary search. */
    public int firstIndexSince(long sinceMillis) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (time(mid) < sinceMillis) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Copy values with timestamps >= sinceMillis, oldest first, into {@code out}. Returns the number
     * copied (bounded by out.length; the most recent samples win if out is too small).
     */
    public int copyValuesSince(long sinceMillis, double[] out) {
        int from = firstIndexSince(sinceMillis);
        int n = size - from;
        if (n > out.length) {
            from = size - out.length;
            n = out.length;
        }
        for (int i = 0; i < n; i++) {
            out[i] = value(from + i);
        }
        return n;
    }

    /** Copy of all retained values, oldest first. */
    public double[] toArray() {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = value(i);
        }
        return out;
    }
}
