package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyBlock-year clock study on 2-hour history. The SkyBlock year (124 real hours, one mayor term) is cut into 20 slots of
 * about 6 hours. For every slot, the move from its start to its end is measured in every past year. Only slots whose move
 * is large, statistically strong and the same in the older and newer half of the history are reported. Pure logic.
 */
public final class Seasons {
    public static final int SLOTS = 20;
    public static final long SLOT_MS = SbCalendar.YEAR_MS / SLOTS, TOL = 3 * 3_600_000L;
    public static final double MIN_T = 3.3; // strict: ~60 items x 20 slots are tested, so weak results are mostly luck
    public static final int MIN_N = 20;

    public record Effect(String id, int slot, String label, int n, double mean, double pctUp, double t, double meanOld, double meanNew) {
        public boolean strong() { return n >= MIN_N && Math.abs(t) >= MIN_T && Math.signum(meanOld) == Math.signum(mean) && Math.signum(meanNew) == Math.signum(mean); }
        /** Buy at the slot start, sell at its end: pays after tax. */
        public boolean tradable(double taxPct) { return mean > taxPct + 0.25; }
    }

    private Seasons() {}

    /** Start of the slot that contains t. */
    public static long slotStart(long t) {
        long since = t - SbCalendar.EPOCH_MS;
        return t - Math.floorMod(since, SbCalendar.YEAR_MS) + Math.floorMod(since, SbCalendar.YEAR_MS) / SLOT_MS * SLOT_MS;
    }

    public static int slotOf(long t) {
        return (int) (Math.floorMod(t - SbCalendar.EPOCH_MS, SbCalendar.YEAR_MS) / SLOT_MS);
    }

    /** "Autumn 10 to Late Autumn 9, before Spooky Festival". */
    public static String label(int slot) {
        long s = SbCalendar.EPOCH_MS + slot * SLOT_MS, e = s + SLOT_MS;
        SbCalendar.SbTime a = SbCalendar.at(s), b = SbCalendar.at(e - 1);
        String tag = "";
        for (SbCalendar.Event ev : SbCalendar.EVENTS) {
            if (ev.hourly()) continue;
            long st = SbCalendar.EPOCH_MS + ev.offsetMs();
            if (st >= s && st < e) { tag = ", " + ev.name() + " starts"; break; }
            if (st >= e && st < e + SLOT_MS && tag.isEmpty()) tag = ", before " + ev.name();
        }
        return SbCalendar.MONTHS[a.month()] + " " + a.day() + " to " + SbCalendar.MONTHS[b.month()] + " " + b.day() + tag;
    }

    /** Mid price nearest to t (within 3h), or NaN. pts sorted by time. */
    static double at(List<Bars.Point> pts, long t) {
        int lo = 0, hi = pts.size() - 1;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (pts.get(m).time() < t) lo = m + 1; else hi = m; }
        double best = Double.NaN;
        long bd = Long.MAX_VALUE;
        for (int i = Math.max(0, lo - 1); i <= Math.min(pts.size() - 1, lo); i++) {
            Bars.Point p = pts.get(i);
            long d = Math.abs(p.time() - t);
            if (d < bd && d <= TOL && p.bid() > 0 && p.ask() > 0) { bd = d; best = p.mid(); }
        }
        return best;
    }

    public static List<Effect> study(String id, List<Bars.Point> pts) {
        List<Effect> out = new ArrayList<>();
        if (pts == null || pts.size() < 200) return out;
        List<List<Double>> r = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) r.add(new ArrayList<>());
        long end = pts.get(pts.size() - 1).time();
        for (long s = slotStart(pts.get(0).time()) + SLOT_MS; s + SLOT_MS <= end; s += SLOT_MS) {
            double a = at(pts, s), b = at(pts, s + SLOT_MS);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            r.get(slotOf(s)).add(Math.max(-50, Math.min(50, (b / a - 1) * 100)));
        }
        for (int k = 0; k < SLOTS; k++) {
            List<Double> v = r.get(k);
            int n = v.size();
            if (n < 6) continue;
            double sum = 0, up = 0;
            for (double x : v) { sum += x; if (x > 0) up++; }
            double mean = sum / n, sq = 0;
            for (double x : v) sq += (x - mean) * (x - mean);
            double sd = Math.sqrt(sq / (n - 1));
            double t = sd == 0 ? 0 : mean / (sd / Math.sqrt(n));
            int h = n / 2;
            double mo = 0, mn = 0;
            for (int i = 0; i < n; i++) if (i < h) mo += v.get(i); else mn += v.get(i);
            out.add(new Effect(id, k, label(k), n, mean, up / n, t, mo / h, mn / (n - h)));
        }
        return out;
    }
}
