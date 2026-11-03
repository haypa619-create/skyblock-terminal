package com.hayden.sbterminal;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Long history the terminal records itself: every item's bid and ask every 2 hours, kept 92 days,
 * saved to config/sbterminal-history.bin. It powers the 1M and 3M chart ranges, so those fill in
 * as you play (nothing public serves a quarter of Bazaar history). Pure logic apart from save/load.
 */
public final class LongStore {
    public static final long STEP_MS = 2 * 3_600_000L;
    public static final long KEEP_MS = 92L * 86_400_000L;
    static final int CAP = (int) (KEEP_MS / STEP_MS) + 2;
    private static final int MAGIC = 0x53425431;

    private final long[] times = new long[CAP];
    private int start = 0, size = 0;
    private final Map<String, float[][]> items = new HashMap<>(); // id -> {bid[], ask[]} by slot
    private long firstAt = 0;

    private int slot(int k) { return (start + k) % CAP; }

    /** Records one sweep if STEP has passed since the last. Returns true if a slot was added. */
    public synchronized boolean record(Market.Snapshot s, long now) {
        if (s.products().isEmpty()) return false;
        if (size > 0 && now - times[slot(size - 1)] < STEP_MS) return false;
        int i;
        if (size < CAP) { i = slot(size); size++; } else { i = start; start = (start + 1) % CAP; }
        times[i] = now;
        if (firstAt == 0 || firstAt > now) firstAt = now;
        for (float[][] a : items.values()) { a[0][i] = 0; a[1][i] = 0; }
        for (Market.Product p : s.products().values()) {
            if (p.bid() <= 0 && p.ask() <= 0) continue;
            float[][] a = items.computeIfAbsent(p.id(), k -> new float[][]{new float[CAP], new float[CAP]});
            a[0][i] = (float) p.bid();
            a[1][i] = (float) p.ask();
        }
        return true;
    }

    public synchronized List<Bars.Point> points(String id) {
        List<Bars.Point> out = new ArrayList<>();
        float[][] a = items.get(id);
        if (a == null) return out;
        for (int k = 0; k < size; k++) {
            int i = slot(k);
            if (a[0][i] > 0 || a[1][i] > 0) out.add(new Bars.Point(times[i], a[0][i], a[1][i]));
        }
        return out;
    }

    /** Time of the oldest slot, or 0. */
    public synchronized long oldest() { return size == 0 ? 0 : times[slot(0)]; }

    public synchronized int slots() { return size; }

    // ---------- file ----------

    public synchronized void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            o.writeInt(MAGIC);
            o.writeInt(size);
            for (int k = 0; k < size; k++) o.writeLong(times[slot(k)]);
            int n = 0;
            for (float[][] a : items.values()) if (has(a)) n++;
            o.writeInt(n);
            for (Map.Entry<String, float[][]> e : items.entrySet()) {
                if (!has(e.getValue())) continue;
                o.writeUTF(e.getKey());
                for (int k = 0; k < size; k++) o.writeFloat(e.getValue()[0][slot(k)]);
                for (int k = 0; k < size; k++) o.writeFloat(e.getValue()[1][slot(k)]);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean has(float[][] a) {
        for (int k = 0; k < size; k++) { int i = slot(k); if (a[0][i] > 0 || a[1][i] > 0) return true; }
        return false;
    }

    public static LongStore load(Path file) {
        LongStore s = new LongStore();
        if (!Files.exists(file)) return s;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) return s;
            int n = Math.min(in.readInt(), CAP);
            long[] t = new long[n];
            for (int k = 0; k < n; k++) t[k] = in.readLong();
            int count = in.readInt();
            Map<String, float[][]> m = new HashMap<>();
            for (int c = 0; c < count; c++) {
                String id = in.readUTF();
                float[][] a = {new float[CAP], new float[CAP]};
                for (int k = 0; k < n; k++) a[0][k] = in.readFloat();
                for (int k = 0; k < n; k++) a[1][k] = in.readFloat();
                m.put(id, a);
            }
            System.arraycopy(t, 0, s.times, 0, n);
            s.size = n;
            s.start = 0;
            s.items.putAll(m);
            if (n > 0) s.firstAt = t[0];
        } catch (Exception e) {
            return new LongStore();
        }
        return s;
    }
}
