package com.hayden.sbterminal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background job: downloads years of daily history for the busiest items, builds the dataset, runs the walk-forward
 * test and fits the final model. Runs on its own thread, one request at a time, so it never competes with the UI.
 * Display only: the result feeds the ALGO "3D" column, and only when the test verdict is EDGE.
 */
public final class DeepTrainer {
    public static final int ITEMS = 150, TREES = 150;
    private static final Gson GSON = new GsonBuilder().create();
    private static final long STALE_MS = 24 * 3_600_000L;

    public static volatile Deep.Report report;
    public static volatile String phase = "idle";
    public static volatile int done = 0, total = 0;
    private static volatile Gbm model;
    private static volatile boolean running;
    private static Path file;
    private static final Map<String, double[]> CACHE = new ConcurrentHashMap<>(); // id -> {prediction, time}

    private record Saved(Deep.Report report, Gbm model) {}

    private DeepTrainer() {}

    public static void init(Path dir) {
        file = dir.resolve("sbterminal-deep.json");
        try {
            if (Files.exists(file)) {
                Saved s = GSON.fromJson(Files.readString(file), Saved.class);
                if (s != null) { report = s.report(); model = s.model(); phase = "loaded from last session"; }
            }
        } catch (Exception e) { Terminal.LOG.warn("Deep model load: {}", e.toString()); }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(120_000);
                if (report == null || System.currentTimeMillis() - report.builtAt() > STALE_MS) start();
            } catch (InterruptedException ignored) { }
        }, "sbterminal-deep-start");
        t.setDaemon(true);
        t.start();
    }

    public static boolean running() { return running; }

    /** Starts a full retrain unless one is already running. */
    public static synchronized boolean start() {
        if (running) return false;
        running = true;
        Thread t = new Thread(DeepTrainer::run, "sbterminal-deep");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return true;
    }

    private static void run() {
        try {
            phase = "choosing items";
            Market.Snapshot s = Feed.snapshot;
            List<Market.Product> ps = new ArrayList<>(s.products().values());
            ps.sort(Comparator.comparingDouble((Market.Product p) -> -Math.min(p.instaSellWeek(), p.instaBuyWeek())));
            List<String> ids = new ArrayList<>();
            for (Market.Product p : ps) if (ids.size() < ITEMS && p.bid() * 1.0 >= Terminal.CONFIG.flipMinPrice * 0.2) ids.add(p.id());
            total = ids.size();
            done = 0;
            if (total == 0) { phase = "no Bazaar data yet, try again in a minute"; return; }
            List<Deep.Sample> all = new ArrayList<>();
            int used = 0;
            for (String id : ids) {
                phase = "downloading history " + (done + 1) + "/" + total;
                List<Bars.Point> pts = fetch(id);
                done++;
                if (pts == null) continue;
                List<Deep.Sample> sm = Deep.samples(id, pts);
                if (!sm.isEmpty()) { all.addAll(sm); used++; }
            }
            phase = "testing on unseen years (" + all.size() + " samples)";
            Deep.Report r = Deep.walkForward(all, used, 1.5, TREES, System.currentTimeMillis());
            Gbm m = r.edge() ? Deep.finalModel(all, TREES) : null;
            report = r;
            model = m;
            CACHE.clear();
            phase = "done";
            if (file != null) Files.writeString(file, GSON.toJson(new Saved(r, m)));
        } catch (Throwable t) {
            phase = "failed: " + t;
            Terminal.LOG.warn("Deep trainer: {}", t.toString());
        } finally {
            running = false;
        }
    }

    /** Waits (up to 40 s) for one item's long history; null if it did not arrive or is empty. */
    private static List<Bars.Point> fetch(String id) {
        long end = System.currentTimeMillis() + 40_000;
        try {
            while (System.currentTimeMillis() < end) {
                List<Bars.Point> l = Feed.longHistory(id);
                if (l != null) return l.isEmpty() ? null : l;
                Thread.sleep(300);
            }
        } catch (InterruptedException ignored) { }
        return null;
    }

    /** Forecast of the 3-day move in % for an item, or NaN. Only answers when the walk-forward verdict is EDGE. */
    public static double predict(String id) {
        Deep.Report r = report;
        Gbm m = model;
        if (r == null || !r.edge() || m == null) return Double.NaN;
        double[] c = CACHE.get(id);
        long now = System.currentTimeMillis();
        if (c != null && now - (long) c[1] < 3_600_000L) return c[0];
        List<Bars.Point> pts = Feed.longHistory(id);
        if (pts == null || pts.isEmpty()) return Double.NaN;
        float[] x = Deep.latest(pts);
        if (x == null) return Double.NaN;
        double p = m.predict(x);
        CACHE.put(id, new double[]{p, now});
        return p;
    }
}
