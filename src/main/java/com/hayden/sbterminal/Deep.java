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
            "SkyBlock year phase (sin)", "SkyBlock year phase (cos)", "price level (log10)"};
    public static final int F = NAMES.length;
    public static final int HORIZON_DAYS = 3;
    public static final double TAX_PCT = 1.25;
    static final long DAY = 86_400_000L;

    private Deep() {}

    public record Sample(long time, String id, float[] x, float y) {}

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
        return x;
    }

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
        for (int i = 31; i + HORIZON_DAYS < a.length; i++) {
            if (a[i + HORIZON_DAYS] == null) continue;
            float[] x = features(d, i, ew);
            if (x == null) continue;
            double y = Math.log(a[i + HORIZON_DAYS].mid() / a[i].mid()) * 100;
            out.add(new Sample((d.day0() + i) * DAY, id, x, (float) Math.max(-30, Math.min(30, y))));
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

    // ---------- walk-forward ----------

    public record Fold(int year, int samples, double skill, double dirAcc, int trades, double avgNet, double winRate, double topDecileNet) {}

    public record Report(List<Fold> folds, int totalSamples, int items, long builtAt, boolean edge, String verdict, double[] importance) {}

    /** @param threshold forecast (%) above which a long trade is taken */
    public static Report walkForward(List<Sample> all, int items, double threshold, int nTrees, long now) {
        List<Fold> folds = new ArrayList<>();
        if (all.size() < 5000) return new Report(folds, all.size(), items, now, false, "Not enough history yet (" + all.size() + " samples)", new double[F]);
        long minT = Long.MAX_VALUE, maxT = Long.MIN_VALUE;
        for (Sample s : all) { minT = Math.min(minT, s.time()); maxT = Math.max(maxT, s.time()); }
        int firstYear = java.time.Instant.ofEpochMilli(minT).atZone(java.time.ZoneOffset.UTC).getYear();
        int lastYear = java.time.Instant.ofEpochMilli(maxT).atZone(java.time.ZoneOffset.UTC).getYear();
        double wNet = 0;
        int tradesAll = 0, positive = 0;
        for (int year = firstYear + 2; year <= lastYear; year++) {
            long from = java.time.LocalDate.of(year, 1, 1).toEpochDay() * DAY, to = java.time.LocalDate.of(year + 1, 1, 1).toEpochDay() * DAY;
            List<Sample> train = new ArrayList<>(), test = new ArrayList<>();
            for (Sample s : all) {
                if (s.time() < from - (HORIZON_DAYS + 1) * DAY) train.add(s); // embargo: no label may peek into the test year
                else if (s.time() >= from && s.time() < to) test.add(s);
            }
            if (train.size() < 3000 || test.size() < 500) continue;
            Gbm g = fit(train, nTrees);
            double se = 0, se0 = 0, dirHit = 0, dirN = 0, net = 0, wins = 0;
            int trades = 0;
            double[] preds = new double[test.size()];
            for (int i = 0; i < test.size(); i++) {
                Sample s = test.get(i);
                double p = g.predict(s.x());
                preds[i] = p;
                se += (s.y() - p) * (s.y() - p);
                se0 += s.y() * s.y();
                if (Math.abs(p) > 0.05 && s.y() != 0) { dirN++; if (Math.signum(p) == Math.signum(s.y())) dirHit++; }
                if (p > threshold) { trades++; double r = s.y() - TAX_PCT; net += r; if (r > 0) wins++; }
            }
            double[] sorted = preds.clone();
            java.util.Arrays.sort(sorted);
            double cut = sorted[(int) (sorted.length * 0.9)], top = 0;
            int tn = 0;
            for (int i = 0; i < test.size(); i++) if (preds[i] >= cut) { top += test.get(i).y() - TAX_PCT; tn++; }
            Fold f = new Fold(year, test.size(), 1 - se / se0, dirN == 0 ? 0.5 : dirHit / dirN, trades, trades == 0 ? 0 : net / trades,
                    trades == 0 ? 0 : wins / trades, tn == 0 ? 0 : top / tn);
            folds.add(f);
            wNet += net;
            tradesAll += trades;
            if (trades >= 30 && f.avgNet() > 0) positive++;
        }
        double avgNet = tradesAll == 0 ? 0 : wNet / tradesAll;
        int tested = (int) folds.stream().filter(f -> f.trades() >= 30).count();
        boolean edge = tested >= 2 && tradesAll >= 300 && avgNet > 0.2 && positive >= Math.ceil(tested * 0.6);
        String verdict;
        if (folds.isEmpty()) verdict = "Not enough years of history to test (need 3+ years across enough items)";
        else if (edge) verdict = String.format("EDGE: %+.2f%% per trade after tax, positive in %d of %d test years (%d trades)", avgNet, positive, tested, tradesAll);
        else verdict = String.format("NO EDGE: %+.2f%% per trade after tax, positive in %d of %d test years (%d trades). Not used for trading.", avgNet, positive, tested, tradesAll);
        return new Report(folds, all.size(), items, now, edge, verdict, importance(all, nTrees));
    }

    private static Gbm fit(List<Sample> train, int nTrees) {
        int n = train.size();
        float[][] X = new float[n][];
        float[] y = new float[n];
        for (int i = 0; i < n; i++) { X[i] = train.get(i).x(); y[i] = train.get(i).y(); }
        return Gbm.fit(X, y, nTrees, 3, 0.05, 20, 100, 42);
    }

    /** Which features the final model splits on, weighted by usage. */
    private static double[] importance(List<Sample> all, int nTrees) {
        Gbm g = fit(all, Math.min(nTrees, 60));
        double[] u = new double[F];
        double sum = 0;
        for (Gbm.Tree t : g.trees) for (int i = 0; i < t.feat.length; i++) if (t.feat[i] >= 0) { u[t.feat[i]]++; sum++; }
        if (sum > 0) for (int i = 0; i < F; i++) u[i] /= sum;
        return u;
    }

    /** Fits the final model on everything. */
    public static Gbm finalModel(List<Sample> all, int nTrees) {
        return fit(all, nTrees);
    }
}
