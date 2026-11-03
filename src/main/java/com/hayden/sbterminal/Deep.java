package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deep training on years of daily history: features per item-day, a gradient-boosted model for the next 3 days,
 * and a walk-forward test (train on earlier years, test on the next) that reports what would have been earned
 * AFTER the Bazaar tax. The verdict only says "edge" when it holds up across years. Pure logic.
 */
public final class Deep {
    public static final String[] NAMES = {"cheap vs 10d fair (z)", "daily volatility", "spread %", "day range %", "1d move", "3d move", "7d move", "14d move", "30d move",
            "weekday (sin)", "weekday (cos)", "day of year (sin)", "day of year (cos)", "near Spooky", "near Jerry", "near Zoo", "near New Year",
            "SkyBlock year phase (sin)", "SkyBlock year phase (cos)", "price level (log10)",
            "order book: buy vs sell units", "flow: insta-buys vs insta-sells", "activity vs a week ago", "liquidity (log10 weekly units)"};
    public static final int F = NAMES.length;
    public static final int[] HORIZONS = {1, 3, 7};
    public static final int MAX_H = 7;
    public static final double TAX_PCT = 1.25;
    static final long DAY = 86_400_000L;

    private Deep() {}

    /** y[k] = % mid move over HORIZONS[k] days (NaN when that day is missing). */
    public record Sample(long time, String id, float[] x, float[] y) {}

    /** One calendar-day value per UTC day (the last point of that day), NaN-free arrays with a presence mask. */
    private record Daily(long day0, Bars.Point[] pts) {}

    private static Daily daily(List<Bars.Point> raw) {
        if (raw == null || raw.size() < 60) return null;
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (Bars.Point p : raw) { first = Math.min(first, Math.floorDiv(p.time(), DAY)); last = Math.max(last, Math.floorDiv(p.time(), DAY)); }
        int n = (int) (last - first + 1);
        if (n < 60 || n > 20000) return null;
        Bars.Point[] a = new Bars.Point[n];
        for (Bars.Point p : raw) {
            if (p.bid() <= 0 || p.ask() <= 0) continue;
            int i = (int) (Math.floorDiv(p.time(), DAY) - first);
            if (a[i] == null || p.time() >= a[i].time()) a[i] = p;
        }
        return new Daily(first, a);
    }

    /** Features for day index d, or null if the needed days are missing. */
    private static float[] features(Daily d, int i, double[] ew) {
        Bars.Point[] a = d.pts();
        if (i < 31 || a[i] == null) return null;
        double mid = a[i].mid();
        double s = 0, sq = 0;
        int c = 0;
        for (int k = i - 19; k <= i; k++) {
            if (a[k] == null || a[k - 1] == null) continue;
            double r = Math.log(a[k].mid() / a[k - 1].mid());
            s += r;
            sq += r * r;
            c++;
        }
        if (c < 12) return null;
        double mean = s / c, sd = Math.sqrt(Math.max(0, sq / c - mean * mean));
        if (sd < 1e-6 || Double.isNaN(ew[i])) return null;
        double[] m = new double[5];
        int[] back = {1, 3, 7, 14, 30};
        for (int k = 0; k < 5; k++) {
            Bars.Point p = a[i - back[k]];
            if (p == null) return null;
            m[k] = Math.max(-50, Math.min(50, (mid / p.mid() - 1) * 100));
        }
        long t = (d.day0() + i) * DAY;
        java.time.LocalDate ld = java.time.LocalDate.ofEpochDay(d.day0() + i);
        double dow = ld.getDayOfWeek().getValue() - 1, doy = ld.getDayOfYear() / 365.25;
        double phase = ((t - SbCalendar.EPOCH_MS) % SbCalendar.YEAR_MS + SbCalendar.YEAR_MS) % SbCalendar.YEAR_MS / (double) SbCalendar.YEAR_MS;
        float[] x = new float[F];
        x[0] = (float) Math.max(-5, Math.min(5, Math.log(mid / ew[i]) / sd));
        x[1] = (float) (sd * 100);
        x[2] = (float) Math.min(30, (a[i].ask() - a[i].bid()) / mid * 100);
        x[3] = (float) Math.min(50, Math.max(0, (a[i].askHigh() - a[i].bidLow()) / mid * 100));
        for (int k = 0; k < 5; k++) x[4 + k] = (float) m[k];
        x[9] = (float) Math.sin(2 * Math.PI * dow / 7);
        x[10] = (float) Math.cos(2 * Math.PI * dow / 7);
        x[11] = (float) Math.sin(2 * Math.PI * doy);
        x[12] = (float) Math.cos(2 * Math.PI * doy);
        x[13] = (float) SbCalendar.proximity("SPOOKY", t);
        x[14] = (float) SbCalendar.proximity("JERRY", t);
        x[15] = (float) Math.max(SbCalendar.proximity("ZOO_S", t), SbCalendar.proximity("ZOO_W", t));
        x[16] = (float) SbCalendar.proximity("NEWYEAR", t);
        x[17] = (float) Math.sin(2 * Math.PI * phase);
        x[18] = (float) Math.cos(2 * Math.PI * phase);
        x[19] = (float) Math.log10(mid);
        Bars.Point p = a[i], w = a[i - 7];
        if (p.bookBid() > 0 || p.bookAsk() > 0) x[20] = (float) clamp(Math.log((p.bookBid() + 1) / (p.bookAsk() + 1)), 5);
        if (p.flowBuy() > 0 || p.flowSell() > 0) {
            x[21] = (float) clamp(Math.log((p.flowBuy() + 1) / (p.flowSell() + 1)), 5);
            double act = p.flowBuy() + p.flowSell();
            if (w != null && w.flowBuy() + w.flowSell() > 0) x[22] = (float) clamp(Math.log((act + 1) / (w.flowBuy() + w.flowSell() + 1)), 3);
            x[23] = (float) Math.log10(act + 1);
        }
        return x;
    }

    private static double clamp(double v, double m) { return Math.max(-m, Math.min(m, v)); }

    private static double[] ewma(Daily d, double halfLifeDays) {
        Bars.Point[] a = d.pts();
        double[] out = new double[a.length];
        double v = Double.NaN, k = 1 - Math.exp(-Math.log(2) / halfLifeDays);
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) v = Double.isNaN(v) ? a[i].mid() : v + (a[i].mid() - v) * k;
            out[i] = v;
        }
        return out;
    }

    /** All training samples of one item. */
    public static List<Sample> samples(String id, List<Bars.Point> raw) {
        List<Sample> out = new ArrayList<>();
        Daily d = daily(raw);
        if (d == null) return out;
        double[] ew = ewma(d, 10);
        Bars.Point[] a = d.pts();
        for (int i = 31; i + 1 < a.length; i++) {
            float[] y = new float[HORIZONS.length];
            boolean any = false;
            for (int k = 0; k < HORIZONS.length; k++) {
                int j = i + HORIZONS[k];
                if (j >= a.length || a[j] == null) { y[k] = Float.NaN; continue; }
                y[k] = (float) clamp(Math.log(a[j].mid() / a[i].mid()) * 100, 30);
                any = true;
            }
            if (!any) continue;
            float[] x = features(d, i, ew);
            if (x == null) continue;
            out.add(new Sample((d.day0() + i) * DAY, id, x, y));
        }
        return out;
    }

    /** Today's feature vector for one item (latest day), or null. */
    public static float[] latest(List<Bars.Point> raw) {
        Daily d = daily(raw);
        if (d == null) return null;
        double[] ew = ewma(d, 10);
        for (int i = d.pts().length - 1; i >= Math.max(31, d.pts().length - 4); i--) {
            float[] x = features(d, i, ew);
            if (x != null) return x;
        }
        return null;
    }

    // ---------- walk-forward with a locked holdout ----------

    /** One test year: skill/direction of the forecast, and the trades the rule would have taken (net after tax). */
    public record Fold(int year, int samples, double skill, double dirAcc, int trades, double avgNet, double winRate, double perDay) {}

    /** rule THRESH = every forecast above tax + 0.25%; TOP5 = each day only the 5 best forecasts above the tax (one inventory). */
    public record Config(int horizon, String rule) {
        public String label() { return horizon + "d " + (rule.equals("TOP5") ? "best 5 a day" : "all above " + (TAX_PCT + 0.25) + "%"); }
    }

    public record ConfigScore(String label, int trades, double avgNet, double perDay, int positiveYears, int testedYears) {}

    public record Report(String config, int horizon, List<Fold> folds, Fold holdout, List<ConfigScore> tried, int totalSamples, int items,
                         long builtAt, boolean edge, String verdict, double[] importance) {}

    private static int year(long t) { return java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneOffset.UTC).getYear(); }

    private record Preds(int year, List<Sample> test, double[] p) {}

    /**
     * Validation years pick the best of 6 setups (3 horizons x 2 rules); the most recent year is a locked holdout that
     * only the chosen setup is scored on, so choosing between setups cannot flatter the result.
     */
    public static Report walkForward(List<Sample> all, int items, int nTrees, long now) {
        List<Fold> none = new ArrayList<>();
        if (all.size() < 5000) return new Report("-", 0, none, null, List.of(), all.size(), items, now, false, "Not enough history yet (" + all.size() + " samples)", new double[F]);
        long minT = Long.MAX_VALUE, maxT = Long.MIN_VALUE;
        for (Sample s : all) { minT = Math.min(minT, s.time()); maxT = Math.max(maxT, s.time()); }
        int firstYear = year(minT), lastYear = year(maxT);
        int holdYear = lastYear;
        if (count(all, holdYear) < 500) holdYear--;
        List<Integer> valYears = new ArrayList<>();
        for (int y = firstYear + 2; y < holdYear; y++) valYears.add(y);
        if (valYears.size() < 2) return new Report("-", 0, none, null, List.of(), all.size(), items, now, false, "Not enough years of history to test (need 4+ years)", new double[F]);
        // forecasts for every validation year and the holdout, per horizon
        List<List<Preds>> val = new ArrayList<>();
        List<Preds> hold = new ArrayList<>();
        for (int k = 0; k < HORIZONS.length; k++) {
            List<Preds> pk = new ArrayList<>();
            for (int y : valYears) pk.add(predictYear(all, k, y, nTrees));
            val.add(pk);
            hold.add(predictYear(all, k, holdYear, nTrees));
        }
        List<ConfigScore> tried = new ArrayList<>();
        Config best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<Fold> bestFolds = null;
        int bestK = 0;
        for (int k = 0; k < HORIZONS.length; k++) for (String rule : new String[]{"THRESH", "TOP5"}) {
            Config c = new Config(HORIZONS[k], rule);
            List<Fold> folds = new ArrayList<>();
            for (Preds p : val.get(k)) if (p != null) folds.add(score(p, k, c));
            int trades = 0, pos = 0, tested = 0;
            double net = 0;
            for (Fold f : folds) { trades += f.trades(); net += f.avgNet() * f.trades(); if (f.trades() >= 30) { tested++; if (f.avgNet() > 0) pos++; } }
            double avg = trades == 0 ? 0 : net / trades, perDay = avg / c.horizon();
            tried.add(new ConfigScore(c.label(), trades, avg, perDay, pos, tested));
            double sc = trades < 100 ? Double.NEGATIVE_INFINITY : perDay;
            if (sc > bestScore) { bestScore = sc; best = c; bestFolds = folds; bestK = k; }
        }
        if (best == null) {
            best = new Config(HORIZONS[1], "TOP5");
            bestK = 1;
            bestFolds = new ArrayList<>();
            for (Preds p : val.get(1)) if (p != null) bestFolds.add(score(p, 1, best));
        }
        Fold h = hold.get(bestK) == null ? null : score(hold.get(bestK), bestK, best);
        int trades = 0, pos = 0, tested = 0;
        double net = 0;
        for (Fold f : bestFolds) { trades += f.trades(); net += f.avgNet() * f.trades(); if (f.trades() >= 30) { tested++; if (f.avgNet() > 0) pos++; } }
        double avg = trades == 0 ? 0 : net / trades;
        boolean valOk = tested >= 2 && trades >= 300 && avg > 0.2 && pos >= Math.ceil(tested * 0.6);
        boolean holdOk = h != null && h.trades() >= 30 && h.avgNet() > 0;
        boolean edge = valOk && holdOk;
        String hs = h == null ? "no holdout year" : String.format("holdout %d: %+.2f%% over %d trades", h.year(), h.avgNet(), h.trades());
        String verdict = edge
                ? String.format("EDGE (%s): %+.2f%% per trade after tax, positive in %d of %d years; %s", best.label(), avg, pos, tested, hs)
                : String.format("NO EDGE (best setup %s): %+.2f%% per trade after tax, positive in %d of %d years; %s. Not used for trading.", best.label(), avg, pos, tested, hs);
        return new Report(best.label(), best.horizon(), bestFolds, h, tried, all.size(), items, now, edge, verdict, importance(all, bestK, nTrees));
    }

    private static int count(List<Sample> all, int y) {
        int n = 0;
        for (Sample s : all) if (year(s.time()) == y) n++;
        return n;
    }

    private static Preds predictYear(List<Sample> all, int k, int year, int nTrees) {
        long from = java.time.LocalDate.of(year, 1, 1).toEpochDay() * DAY, to = java.time.LocalDate.of(year + 1, 1, 1).toEpochDay() * DAY;
        List<Sample> train = new ArrayList<>(), test = new ArrayList<>();
        for (Sample s : all) {
            if (Float.isNaN(s.y()[k])) continue;
            if (s.time() < from - (HORIZONS[k] + 1) * DAY) train.add(s); // embargo: no label may reach into the test year
            else if (s.time() >= from && s.time() < to) test.add(s);
        }
        if (train.size() < 3000 || test.size() < 500) return null;
        Gbm g = fit(train, k, nTrees);
        double[] p = new double[test.size()];
        for (int i = 0; i < p.length; i++) p[i] = g.predict(test.get(i).x());
        return new Preds(year, test, p);
    }

    private static Fold score(Preds pr, int k, Config c) {
        List<Sample> test = pr.test();
        double[] p = pr.p();
        double se = 0, se0 = 0, dirHit = 0, dirN = 0, net = 0, wins = 0;
        int trades = 0;
        boolean[] take = new boolean[p.length];
        if (c.rule().equals("TOP5")) {
            Map<Long, List<Integer>> byDay = new java.util.HashMap<>();
            for (int i = 0; i < p.length; i++) byDay.computeIfAbsent(test.get(i).time(), t -> new ArrayList<>()).add(i);
            for (List<Integer> day : byDay.values()) {
                day.sort((a, b) -> Double.compare(p[b], p[a]));
                for (int j = 0; j < Math.min(5, day.size()); j++) if (p[day.get(j)] > TAX_PCT) take[day.get(j)] = true;
            }
        } else for (int i = 0; i < p.length; i++) take[i] = p[i] > TAX_PCT + 0.25;
        for (int i = 0; i < p.length; i++) {
            double y = test.get(i).y()[k];
            se += (y - p[i]) * (y - p[i]);
            se0 += y * y;
            if (Math.abs(p[i]) > 0.05 && y != 0) { dirN++; if (Math.signum(p[i]) == Math.signum(y)) dirHit++; }
            if (take[i]) { trades++; double r = y - TAX_PCT; net += r; if (r > 0) wins++; }
        }
        double avg = trades == 0 ? 0 : net / trades;
        return new Fold(pr.year(), test.size(), se0 == 0 ? 0 : 1 - se / se0, dirN == 0 ? 0.5 : dirHit / dirN, trades, avg, trades == 0 ? 0 : wins / trades, avg / c.horizon());
    }

    private static Gbm fit(List<Sample> train, int k, int nTrees) {
        int n = 0;
        for (Sample s : train) if (!Float.isNaN(s.y()[k])) n++;
        float[][] X = new float[n][];
        float[] y = new float[n];
        int j = 0;
        for (Sample s : train) if (!Float.isNaN(s.y()[k])) { X[j] = s.x(); y[j++] = s.y()[k]; }
        return Gbm.fit(X, y, nTrees, 3, 0.05, 20, 100, 42);
    }

    /** Which features the model splits on, weighted by usage. */
    private static double[] importance(List<Sample> all, int k, int nTrees) {
        Gbm g = fit(all, k, Math.min(nTrees, 60));
        double[] u = new double[F];
        double sum = 0;
        for (Gbm.Tree t : g.trees) for (int i = 0; i < t.feat.length; i++) if (t.feat[i] >= 0) { u[t.feat[i]]++; sum++; }
        if (sum > 0) for (int i = 0; i < F; i++) u[i] /= sum;
        return u;
    }

    /** Fits the final model on everything for the chosen horizon (days). */
    public static Gbm finalModel(List<Sample> all, int horizonDays, int nTrees) {
        int k = 0;
        for (int i = 0; i < HORIZONS.length; i++) if (HORIZONS[i] == horizonDays) k = i;
        return fit(all, k, nTrees);
    }
}
