package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The terminal's own recording of live prices: one point per item per minute, kept for
 * KEEP_MS, from every Hypixel update while the game runs. Covers the gap Coflnet's 5-minute
 * history doesn't, and powers 1-hour changes. Pure logic.
 */
public final class Recorder {
    public static final long STEP_MS = 60_000L;
    public static final long KEEP_MS = 6 * 3_600_000L;
    private static final int CAP = (int) (KEEP_MS / STEP_MS) + 2;

    private static final class Ring {
        final long[] t = new long[CAP];
        final float[] bid = new float[CAP], ask = new float[CAP];
        int start, size;

        void add(long time, double b, double a) {
            if (size > 0) {
                int last = (start + size - 1) % CAP;
                if (time - t[last] < STEP_MS) { bid[last] = (float) b; ask[last] = (float) a; return; } // keep the latest in this minute
            }
            int i;
            if (size < CAP) { i = (start + size) % CAP; size++; } else { i = start; start = (start + 1) % CAP; }
            t[i] = time; bid[i] = (float) b; ask[i] = (float) a;
        }

        void trim(long now) {
            while (size > 0 && now - t[start] > KEEP_MS) { start = (start + 1) % CAP; size--; }
        }
    }

    private final Map<String, Ring> rings = new HashMap<>();
    private long firstAt = 0;

    public synchronized void record(Market.Snapshot s, long now) {
        if (firstAt == 0) firstAt = now;
        for (Market.Product p : s.products().values()) {
            if (p.bid() <= 0 && p.ask() <= 0) continue;
            rings.computeIfAbsent(p.id(), k -> new Ring()).add(now, p.bid(), p.ask());
        }
        for (Ring r : rings.values()) r.trim(now);
    }

    public synchronized List<Bars.Point> points(String id) {
        Ring r = rings.get(id);
        List<Bars.Point> out = new ArrayList<>();
        if (r == null) return out;
        for (int k = 0; k < r.size; k++) {
            int i = (r.start + k) % CAP;
            out.add(new Bars.Point(r.t[i], r.bid[i], r.ask[i]));
        }
        return out;
    }

    /** How long the terminal has been recording (for "warming up" labels). */
    public synchronized long recordingMs(long now) {
        return firstAt == 0 ? 0 : now - firstAt;
    }
}
