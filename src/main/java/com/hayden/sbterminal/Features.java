package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a price history into the numbers the learning model sees. Everything is computed on a 2-hour grid,
 * so warm-start history (2-hour bars) and live data (1-minute recording) give the same features. Pure logic.
 */
public final class Features {
    public static final String[] NAMES = {"cheap vs fair (z)", "volatility /h", "spread %", "2h move", "6h move", "24h move",
            "hour of day (sin)", "hour of day (cos)", "near Spooky", "near Jerry", "near Zoo", "near New Year", "mayor boosts sector", "bias"};
    public static final int N = NAMES.length;
    public static final long STEP = 2 * 3_600_000L;
    /** The model predicts the move over this many grid steps (4 hours). */
    public static final int HORIZON_STEPS = 2;

    private Features() {}

    /** Last value at or before each grid time (not older than one step), plus a final point at t if t is off the grid. */
    public static List<Bars.Point> resample(List<Bars.Point> pts, long t) {
        List<Bars.Point> out = new ArrayList<>();
        if (pts == null || pts.isEmpty()) return out;
        long g = (pts.get(0).time() / STEP + 1) * STEP;
        int k = 0;
        Bars.Point lastAt = null;
        for (; g <= t; g += STEP) {
            while (k < pts.size() && pts.get(k).time() <= g) { lastAt = pts.get(k); k++; }
            if (lastAt != null && g - lastAt.time() <= STEP && lastAt.bid() > 0 && lastAt.ask() > 0) out.add(new Bars.Point(g, lastAt.bid(), lastAt.ask()));
        }
        while (k < pts.size() && pts.get(k).time() <= t) { lastAt = pts.get(k); k++; }
        if (lastAt != null && lastAt.bid() > 0 && lastAt.ask() > 0 && t - lastAt.time() <= STEP
                && (out.isEmpty() || t - out.get(out.size() - 1).time() > 10 * 60_000L)) out.add(new Bars.Point(t, lastAt.bid(), lastAt.ask()));
        return out;
    }

    /** Features at t from raw points (oldest first), or null when there is not enough history. */
    public static double[] build(List<Bars.Point> pts, long t, double mayorBoost) {
        List<Bars.Point> grid = resample(pts, t);
        return fromGrid(grid, grid.size() - 1, mayorBoost);
    }

    /** Features at grid index i using only points up to i. */
    public static double[] fromGrid(List<Bars.Point> g, int i, double mayorBoost) {
        if (i < 13 || i >= g.size()) return null;
        double mid = g.get(i).mid();
        if (mid <= 0) return null;
        long t = g.get(i).time();
        double var = 0;
        int c = 0;
        for (int k = i - 11; k <= i; k++) {
            double a = g.get(k - 1).mid(), b = g.get(k).mid();
            double dt = (g.get(k).time() - g.get(k - 1).time()) / 3_600_000.0;
            if (a <= 0 || b <= 0 || dt < 0.2) continue;
            double r = Math.log(b / a);
            var += r * r / dt;
            c++;
        }
        if (c < 8) return null;
        double sigma = Math.sqrt(var / c);
        if (sigma <= 1e-6) return null;
        double fair = Quant.ewma(g.subList(Math.max(0, i - 12), i + 1), 4 * 3_600_000.0);
        double z = Math.max(-5, Math.min(5, Math.log(mid / fair) / (sigma * Math.sqrt(2))));
        double spread = Math.min(30, (g.get(i).ask() - g.get(i).bid()) / mid * 100);
        double hour = (t / 3_600_000.0) % 24;
        double[] x = new double[N];
        x[0] = z;
        x[1] = sigma * 100;
        x[2] = spread;
        x[3] = move(g, i, 1);
        x[4] = move(g, i, 3);
        x[5] = move(g, i, 12);
        x[6] = Math.sin(2 * Math.PI * hour / 24);
        x[7] = Math.cos(2 * Math.PI * hour / 24);
        x[8] = SbCalendar.proximity("SPOOKY", t);
        x[9] = SbCalendar.proximity("JERRY", t);
        x[10] = Math.max(SbCalendar.proximity("ZOO_S", t), SbCalendar.proximity("ZOO_W", t));
        x[11] = SbCalendar.proximity("NEWYEAR", t);
        x[12] = mayorBoost;
        x[13] = 1;
        for (double v : x) if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return x;
    }

    private static double move(List<Bars.Point> g, int i, int back) {
        double a = g.get(i - back).mid(), b = g.get(i).mid();
        return a <= 0 ? 0 : Math.max(-30, Math.min(30, (b / a - 1) * 100));
    }

    /** % change of the mid from grid index i to i + steps, or NaN. */
    public static double labelAhead(List<Bars.Point> g, int i, int steps) {
        if (i + steps >= g.size()) return Double.NaN;
        double a = g.get(i).mid(), b = g.get(i + steps).mid();
        if (a <= 0 || b <= 0) return Double.NaN;
        if (g.get(i + steps).time() - g.get(i).time() > steps * STEP + 30 * 60_000L) return Double.NaN;
        return Math.max(-15, Math.min(15, Math.log(b / a) * 100));
    }
}
