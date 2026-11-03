package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * All market data for the terminal, kept fresh on background threads:
 *   live snapshot  Hypixel Bazaar API, every few seconds (faster while the terminal is open)
 *   recording      one point per item per minute, from every live update
 *   history        Coflnet day (about 5-minute points) and week (2-hour bars), on demand,
 *                  plus a slow background backfill of the most traded items for 24h movers
 */
public final class Feed {
    public static volatile Market.Snapshot snapshot = Market.Snapshot.EMPTY;
    public static volatile Map<String, String> names = Map.of();
    /** id -> NPC sell price. */
    public static volatile Map<String, Double> npc = Map.of();
    public static volatile String error = null;
    public static volatile boolean terminalOpen = false;
    public static final Recorder RECORDER = new Recorder();
    /** Daily candles back to 2020 from Coflnet's custom-range endpoint, loaded on demand (1M, 3M, 1Y, ALL charts). */
    private static final Map<String, Hist> LONGH = new ConcurrentHashMap<>();
    private static final long LONG_TTL = 6 * 3_600_000L;
    public static final String COFLNET_RANGE = "https://sky.coflnet.com/api/bazaar/%s/history?start=2020-06-01T00:00:00Z&end=%s";

    /** Daily history since 2020 (null while loading). Requests it if missing or stale. */
    public static List<Bars.Point> longHistory(String id) {
        return hist(LONGH, id, "long", LONG_TTL);
    }

    private record Hist(List<Bars.Point> pts, long fetchedAt) {}

    private static final Map<String, Hist> DAY = new ConcurrentHashMap<>(), WEEK = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService LIVE = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "sbterminal-live"));
    private static final ScheduledExecutorService HISTORY = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "sbterminal-history"));
    /** For history you asked for right now (item pages), separate from the slow backfill. */
    private static final java.util.concurrent.ExecutorService ON_DEMAND = Executors.newFixedThreadPool(2, r -> daemon(r, "sbterminal-ondemand"));

    private static volatile long lastPoll = 0;
    private static volatile int backfillDone = 0, backfillTotal = 0;
    private static final long DAY_TTL = 5 * 60_000L, WEEK_TTL = 30 * 60_000L;

    private Feed() {}

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public static void start() {
        HISTORY.execute(() -> {
            try { String j = Http.get(Market.ITEMS_URL); names = Market.parseNames(j); npc = Market.parseNpc(j); } catch (Exception e) { Terminal.LOG.warn("Item names: {}", e.toString()); }
        });
        LIVE.scheduleWithFixedDelay(Feed::tick, 0, 1, TimeUnit.SECONDS);
        HISTORY.scheduleWithFixedDelay(Feed::backfillStep, 20, 3, TimeUnit.SECONDS);
    }

    /** One synchronous price refresh (used after Hypixel rejects a price). */
    public static void refreshNow() throws Exception {
        long now = System.currentTimeMillis();
        Market.Snapshot s = Market.parse(Http.get(Market.BAZAAR_URL), now);
        snapshot = s;
        lastPoll = now;
    }

    private static volatile Map<String, String> byName = Map.of();
    private static volatile int byNameFrom = -1;

    /** Bazaar product id for a display name ("Enchanted Coal"), or null. */
    public static String idForName(String name) {
        Map<String, String> n = names;
        if (n.size() != byNameFrom) {
            Map<String, String> m = new java.util.HashMap<>();
            for (Map.Entry<String, String> e : n.entrySet()) m.putIfAbsent(Items.norm(e.getValue()), e.getKey());
            byName = m;
            byNameFrom = n.size();
        }
        return byName.get(Items.norm(name));
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        int every = terminalOpen ? Terminal.CONFIG.pollOpenSecs : Terminal.CONFIG.pollClosedSecs;
        if (now - lastPoll < every * 1000L) return;
        lastPoll = now;
        try {
            Market.Snapshot s = Market.parse(Http.get(Market.BAZAAR_URL), now);
            if (s.hypixelUpdated() != snapshot.hypixelUpdated() || snapshot.products().isEmpty()) {
                snapshot = s;
                RECORDER.record(s, now);
            }
            error = null;
        } catch (Exception e) {
            error = "Hypixel API: " + e.getMessage();
        }
    }

    // ---------- history ----------

    /** Coflnet day history (null while loading or unavailable). Requests it if missing or stale. */
    public static List<Bars.Point> day(String id) {
        return hist(DAY, id, "day", DAY_TTL);
    }

    public static List<Bars.Point> week(String id) {
        return hist(WEEK, id, "week", WEEK_TTL);
    }

    public static boolean loading(String id) {
        return LOADING.containsKey(id + "day") || LOADING.containsKey(id + "week") || LOADING.containsKey(id + "long");
    }

    private static List<Bars.Point> hist(Map<String, Hist> cache, String id, String range, long ttl) {
        Hist h = cache.get(id);
        if (h == null || System.currentTimeMillis() - h.fetchedAt() > ttl) request(cache, id, range, true);
        return h == null ? null : h.pts();
    }

    private static void request(Map<String, Hist> cache, String id, String range, boolean now) {
        if (LOADING.putIfAbsent(id + range, Boolean.TRUE) != null) return;
        Runnable job = () -> {
            try {
                String url = range.equals("long")
                        ? String.format(COFLNET_RANGE, id, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                        : String.format(Bars.COFLNET, id, range);
                cache.put(id, new Hist(Bars.parseCoflnet(Http.get(url)), System.currentTimeMillis()));
            } catch (Exception e) {
                long age = range.equals("long") ? LONG_TTL - 120_000L : 0; // a failed long fetch retries after 2 minutes
                cache.put(id, new Hist(List.of(), System.currentTimeMillis() - age)); // don't hammer a failing item
            } finally {
                LOADING.remove(id + range);
            }
        };
        if (now) ON_DEMAND.execute(job); else job.run();
    }

    /** Background: day history for the most traded items, one request every 3s, so 24h movers fill in. */
    private static void backfillStep() {
        Market.Snapshot s = snapshot;
        if (s.products().isEmpty()) return;
        List<Market.Product> top = new ArrayList<>(s.products().values());
        top.removeIf(p -> p.weekVolume() < Terminal.CONFIG.minWeekVolume || p.bid() <= 0);
        top.sort(Comparator.comparingDouble(Market.Product::coinFlowDay).reversed());
        if (top.size() > 200) top = top.subList(0, 200);
        backfillTotal = top.size();
        int done = 0;
        long now = System.currentTimeMillis();
        Market.Product next = null;
        for (Market.Product p : top) {
            Hist h = DAY.get(p.id());
            if (h != null && now - h.fetchedAt() < 30 * 60_000L) done++;
            else if (next == null) next = p;
        }
        backfillDone = done;
        if (next != null) request(DAY, next.id(), "day", false);
    }

    public static String backfillStatus() {
        return backfillTotal == 0 ? "" : backfillDone >= backfillTotal ? "" : "24h history " + backfillDone + "/" + backfillTotal;
    }

    // ---------- derived ----------

    /** Recording first, older gaps from Coflnet's day history. */
    public static List<Bars.Point> recent(String id) {
        List<Bars.Point> rec = RECORDER.points(id);
        Hist h = DAY.get(id);
        return h == null ? rec : Bars.merge(h.pts(), rec);
    }

    public static double change(String id, long window) {
        Market.Product p = snapshot.get(id);
        if (p == null) return Double.NaN;
        return Bars.change(recent(id), p.mid(), System.currentTimeMillis(), window);
    }

    public static String name(String id) {
        String n = names.get(id);
        return n != null ? n : Market.prettyId(id);
    }

    /** For ranking search matches: busier items first. */
    public static double rankVolume(String id) {
        Market.Product p = snapshot.get(id);
        return p == null ? 0 : p.coinFlowDay();
    }
}
