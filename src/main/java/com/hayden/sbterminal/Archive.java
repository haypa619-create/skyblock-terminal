package com.hayden.sbterminal;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Builds a 2-hour price archive going back about two years for the busiest items. Coflnet returns 2-hour candles when
 * asked for 5 days at a time, so this walks back one 5-day window per request (one request every 2 seconds, newest
 * first, all items before going further back). Everything is saved to config/sbterminal-archive, so each window is only
 * downloaded once, across sessions. Feeds the SkyBlock-year clock study (SEAS).
 */
public final class Archive {
    public static final int ITEMS = 60, WINDOWS = 146; // 146 x 5 days = 2 years
    static final long W = 5 * 86_400_000L;
    static final String URL = "https://sky.coflnet.com/api/bazaar/%s/history?start=%s&end=%s";

    public static volatile int done = 0, total = 0;
    public static volatile String phase = "starting in a minute";
    public static volatile List<Seasons.Effect> effects = List.of();
    public static volatile int studied = 0;
    private static Path dir;
    private static final Map<String, ConcurrentSkipListMap<Long, Bars.Point>> DATA = new ConcurrentHashMap<>();
    private static final Map<String, Set<Long>> DONE = new ConcurrentHashMap<>();

    private Archive() {}

    public static void init(Path config) {
        dir = config.resolve("sbterminal-archive");
        Thread t = new Thread(Archive::loop, "sbterminal-archive");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Archived 2-hour points of an item, oldest first (empty if none). */
    public static List<Bars.Point> series(String id) {
        ConcurrentSkipListMap<Long, Bars.Point> m = DATA.get(id);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    private static void loop() {
        try {
            Thread.sleep(60_000);
            Files.createDirectories(dir);
            while (true) {
                pass();
                Thread.sleep(6 * 3_600_000L);
            }
        } catch (Throwable t) {
            phase = "stopped: " + t;
            Terminal.LOG.warn("Archive: {}", t.toString());
        }
    }

    private static List<String> pick() throws InterruptedException {
        while (Feed.snapshot.products().isEmpty()) Thread.sleep(2000);
        List<Market.Product> ps = new ArrayList<>(Feed.snapshot.products().values());
        ps.removeIf(p -> p.mid() < Terminal.CONFIG.flipMinPrice * 0.2);
        ps.sort(Comparator.comparingDouble((Market.Product p) -> -Math.min(p.instaBuyWeek(), p.instaSellWeek())));
        List<String> ids = new ArrayList<>();
        for (Market.Product p : ps) if (ids.size() < ITEMS) ids.add(p.id());
        return ids;
    }

    private static void pass() throws Exception {
        List<String> ids = pick();
        for (String id : ids) load(id);
        long cur = Math.floorDiv(System.currentTimeMillis(), W);
        total = ids.size() * WINDOWS;
        int d = 0;
        for (String id : ids) for (int k = 1; k <= WINDOWS; k++) if (DONE.get(id).contains(cur - k)) d++;
        done = d;
        study(ids);
        int sinceStudy = 0;
        for (int k = 1; k <= WINDOWS; k++) {
            for (String id : ids) {
                long idx = cur - k;
                if (DONE.get(id).contains(idx)) continue;
                phase = "downloading 2-hour history, " + done + "/" + total + " windows";
                if (fetch(id, idx)) { done++; sinceStudy++; }
                Thread.sleep(2000);
            }
            if (sinceStudy >= 60) { study(ids); sinceStudy = 0; }
        }
        study(ids);
        phase = "up to date (" + done + "/" + total + " windows)";
    }

    private static boolean fetch(String id, long idx) throws InterruptedException {
        try {
            String url = String.format(URL, id, Instant.ofEpochMilli(idx * W), Instant.ofEpochMilli((idx + 1) * W));
            List<Bars.Point> pts = Bars.parseCoflnet(Http.get(url));
            ConcurrentSkipListMap<Long, Bars.Point> m = DATA.get(id);
            StringBuilder sb = new StringBuilder();
            for (Bars.Point p : pts) {
                if (p.time() < idx * W || p.time() >= (idx + 1) * W) continue;
                m.put(p.time(), p);
                sb.append(String.format(Locale.US, "P %d %.4f %.4f%n", p.time(), p.bid(), p.ask()));
            }
            sb.append("W ").append(idx).append('\n');
            try (BufferedWriter w = Files.newBufferedWriter(file(id), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) { w.write(sb.toString()); }
            DONE.get(id).add(idx);
            return true;
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("429")) { phase = "Coflnet asked us to slow down, waiting a minute"; Thread.sleep(60_000); }
            else Thread.sleep(5000);
            return false;
        }
    }

    private static Path file(String id) {
        return dir.resolve(id.replace(':', '-') + ".txt");
    }

    private static void load(String id) {
        if (DATA.containsKey(id)) return;
        ConcurrentSkipListMap<Long, Bars.Point> m = new ConcurrentSkipListMap<>();
        Set<Long> w = ConcurrentHashMap.newKeySet();
        try {
            Path f = file(id);
            if (Files.exists(f)) for (String line : Files.readAllLines(f)) {
                String[] s = line.trim().split(" ");
                try {
                    if (s[0].equals("W") && s.length == 2) w.add(Long.parseLong(s[1]));
                    else if (s[0].equals("P") && s.length == 4) { long t = Long.parseLong(s[1]); m.put(t, new Bars.Point(t, Double.parseDouble(s[2]), Double.parseDouble(s[3]))); }
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) { Terminal.LOG.warn("Archive load {}: {}", id, e.toString()); }
        DATA.put(id, m);
        DONE.put(id, w);
    }

    private static void study(List<String> ids) {
        List<Seasons.Effect> out = new ArrayList<>();
        int n = 0;
        for (String id : ids) {
            List<Seasons.Effect> es = Seasons.study(id, series(id));
            if (!es.isEmpty()) n++;
            for (Seasons.Effect e : es) if (e.strong()) out.add(e);
        }
        effects = List.copyOf(out);
        studied = n;
    }
}
