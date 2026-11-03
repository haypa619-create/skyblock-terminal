package com.hayden.sbterminal;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Feeds the model. Two sources of training samples:
 *   warm start  a week of 2-hour history for the busiest items, replayed in order (thousands of samples on first run)
 *   live        every 10 minutes the busiest items are sampled, and 4 hours later the real move becomes the label
 * The model is saved every 5 minutes and when the game closes, so it keeps improving across sessions.
 */
public final class Learner {
    public static volatile Model MODEL = new Model();
    public static volatile int warmDone = 0, warmTotal = 0, liveTrained = 0, warmTrained = 0;
    private static Path file;

    private record Pending(String id, double[] x, long due, double mid) {}

    private static final Deque<Pending> PENDING = new ArrayDeque<>();
    private static final Set<String> WARMED = ConcurrentHashMap.newKeySet();
    private static long lastSample = 0, lastSave = 0;
    private static final long HORIZON_MS = Features.HORIZON_STEPS * Features.STEP;

    private Learner() {}

    public static void init(Path dir) {
        file = dir.resolve("sbterminal-model.json");
        MODEL = Model.load(file);
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "sbterminal-learner"); t.setDaemon(true); return t; });
        ex.scheduleWithFixedDelay(Learner::tick, 45, 30, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> MODEL.save(file)));
    }

    public static synchronized int pending() { return PENDING.size(); }

    public static void reset() {
        synchronized (Learner.class) { PENDING.clear(); }
        WARMED.clear();
        warmDone = 0;
        liveTrained = 0;
        warmTrained = 0;
        MODEL = new Model();
        if (file != null) MODEL.save(file);
    }

    private static void tick() {
        try {
            World.refresh();
            Market.Snapshot s = Feed.snapshot;
            if (s.products().isEmpty()) return;
            long now = System.currentTimeMillis();
            trainMatured(s, now);
            warmStep(s);
            sampleLive(s, now);
            if (now - lastSave > 5 * 60_000L) { lastSave = now; MODEL.save(file); }
        } catch (Throwable t) {
            Terminal.LOG.warn("Learner: {}", t.toString());
        }
    }

    private static List<Market.Product> busiest(Market.Snapshot s, int n) {
        List<Market.Product> l = new java.util.ArrayList<>(s.products().values());
        l.removeIf(p -> p.bid() <= 0 || p.ask() <= 0 || p.weekVolume() < Terminal.CONFIG.minWeekVolume);
        l.sort(Comparator.comparingDouble(Market.Product::coinFlowDay).reversed());
        return l.size() > n ? l.subList(0, n) : l;
    }

    private static double mayorBoost(String id) {
        World.Election e = World.election;
        return e != null && World.boostedSectors(e).contains(Sectors.of(id)) ? 1 : 0;
    }

    private static void trainMatured(Market.Snapshot s, long now) {
        while (true) {
            Pending p;
            synchronized (Learner.class) {
                p = PENDING.peek();
                if (p == null || p.due() > now) return;
                PENDING.poll();
            }
            Market.Product cur = s.get(p.id());
            if (cur == null || cur.mid() <= 0 || p.mid() <= 0) continue;
            MODEL.train(p.x(), Math.log(cur.mid() / p.mid()) * 100);
            liveTrained++;
        }
    }

    private static void sampleLive(Market.Snapshot s, long now) {
        if (now - lastSample < 10 * 60_000L) return;
        lastSample = now;
        for (Market.Product p : busiest(s, 120)) {
            double[] x = Features.build(Feed.recent(p.id()), now, mayorBoost(p.id()));
            if (x == null) continue;
            synchronized (Learner.class) { PENDING.add(new Pending(p.id(), x, now + HORIZON_MS, p.mid())); }
        }
    }

    /** A few items per tick: replay a week of 2-hour history through the model, oldest first. */
    private static void warmStep(Market.Snapshot s) {
        List<Market.Product> top = busiest(s, 150);
        warmTotal = top.size();
        int did = 0;
        for (Market.Product p : top) {
            if (WARMED.contains(p.id())) continue;
            if (did >= 4) break;
            List<Bars.Point> wk = Feed.week(p.id()); // null while it loads
            if (wk == null) { did++; continue; }
            WARMED.add(p.id());
            did++;
            if (wk.isEmpty()) continue;
            List<Bars.Point> grid = Features.resample(wk, wk.get(wk.size() - 1).time());
            for (int i = 13; i + Features.HORIZON_STEPS < grid.size(); i++) {
                double[] x = Features.fromGrid(grid, i, 0);
                double y = Features.labelAhead(grid, i, Features.HORIZON_STEPS);
                if (x == null || Double.isNaN(y)) continue;
                MODEL.train(x, y);
                warmTrained++;
            }
        }
        warmDone = WARMED.size();
    }

    /** Predicted % move over the next 4 hours, or NaN while there is not enough history. */
    public static double predictFor(String id) {
        double[] x = Features.build(Feed.recent(id), System.currentTimeMillis(), mayorBoost(id));
        return x == null ? Double.NaN : MODEL.predict(x);
    }
}
