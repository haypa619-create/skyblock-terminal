package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The trading model. Market making the way a desk does it, scaled to the Bazaar:
 *   fair value   EWMA of the mid blended with the order-book microprice
 *   volatility   hourly standard deviation of the mid
 *   fill time    how long your size takes to fill on each side, from the real insta-trade flow
 *   edge         net per unit after tax, minus a charge for the price risk while you hold
 *   sizing       capped by purse fraction, one inventory, the daily limit and what the market can absorb
 * It only produces signals. You place the orders. Pure logic.
 */
public final class Quant {
    private Quant() {}

    /** Inputs that are not market data. */
    public record Params(double tax, double share, double purseFrac, double risk, double purse, double invUnits, double limitLeft) {}

    /**
     * @param z     how far the mid is from fair, in standard deviations over about 2 hours (negative = cheap)
     * @param sigma hourly volatility as a fraction of price
     * @param cycle hours for one full buy-then-sell round trip at this size
     * @param edgeH risk-adjusted coins per hour at this size
     * @param why   short reason when skipped, else the stance
     */
    public record Signal(Market.Product p, double fair, double z, double sigma, double buy, double sell, double net, double riskAdj,
                         double units, double cycle, double edgeH, boolean skip, String why, double pred) {}

    // ---------- model pieces ----------

    /** EWMA of the mid with the given half-life, oldest-first points. NaN if none. */
    public static double ewma(List<Bars.Point> pts, double halfLifeMs) {
        double fair = Double.NaN, tau = halfLifeMs / Math.log(2);
        long last = 0;
        for (Bars.Point p : pts) {
            double m = p.mid();
            if (m <= 0) continue;
            if (Double.isNaN(fair)) fair = m;
            else fair += (m - fair) * (1 - Math.exp(-(p.time() - last) / tau));
            last = p.time();
        }
        return fair;
    }

    /** Book-weighted price: leans toward the side with less size, where the price is likelier to move. */
    public static double microprice(Market.Product p) {
        if (p.bidLevels().length < 2 || p.askLevels().length < 2) return p.mid();
        double bu = p.bidLevels()[1], au = p.askLevels()[1];
        return bu + au <= 0 ? p.mid() : (p.bid() * au + p.ask() * bu) / (bu + au);
    }

    /** Standard deviation of hourly log returns of the mid, as a fraction. NaN when there is too little history. */
    public static double hourlySigma(List<Bars.Point> pts) {
        List<Double> hourly = new ArrayList<>();
        long bucket = Long.MIN_VALUE;
        for (Bars.Point p : pts) {
            double m = p.mid();
            if (m <= 0) continue;
            long b = Math.floorDiv(p.time(), 3_600_000L);
            if (b == bucket) hourly.set(hourly.size() - 1, m);
            else { hourly.add(m); bucket = b; }
        }
        if (hourly.size() < 7) return Double.NaN;
        double sum = 0, sq = 0;
        int n = hourly.size() - 1;
        for (int i = 1; i < hourly.size(); i++) {
            double r = Math.log(hourly.get(i) / hourly.get(i - 1));
            sum += r;
            sq += r * r;
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sq / n - mean * mean));
    }

    // ---------- the signal ----------

    /** @param recent mid history for roughly the last day, oldest first */
    public static Signal signal(Market.Product p, List<Bars.Point> recent, Params q) {
        return signal(p, recent, q, Double.NaN, false);
    }

    /** @param pred model's predicted % move over 4h (NaN = none); @param trusted whether the model has earned the right to change the signal */
    public static Signal signal(Market.Product p, List<Bars.Point> recent, Params q, double pred, boolean trusted) {
        if (p.bid() <= 0 || p.ask() <= 0) return null;
        double buy = p.bid() + 0.1, sell = p.ask() - 0.1;
        double net = sell * q.tax() - buy;
        double ew = ewma(recent, 2 * 3_600_000L / 1.0 * 1.0);
        double mid = p.mid();
        double fair = Double.isNaN(ew) ? microprice(p) : 0.7 * ew + 0.3 * microprice(p);
        double sg = hourlySigma(recent);
        if (Double.isNaN(sg)) sg = Math.max(p.spreadPct() / 200.0, 0.002); // no history yet: half the spread as a guess
        double z = Math.log(mid / fair) / (sg * Math.sqrt(2));

        double inH = p.instaSellWeek() / 168.0, outH = p.instaBuyWeek() / 168.0; // insta-sells fill buy orders, insta-buys fill sell offers
        double flow = Math.min(inH, outH) * q.share();
        if (flow <= 0 || net <= 0) return new Signal(p, fair, z, sg, buy, sell, net, net, 0, 0, 0, true, net <= 0 ? "no margin" : "no volume", pred);
        double units = Math.min(Math.min(q.invUnits(), Math.max(0, q.purse()) * q.purseFrac() / buy), Math.max(0, q.limitLeft()) / buy);
        units = Math.min(units, flow * 2); // never queue more than about two hours of flow
        if (units < 1) return new Signal(p, fair, z, sg, buy, sell, net, net, 0, 0, 0, true, "no room (purse, limit or volume)", pred);
        double cycle = Math.max(0.05, units / (inH * q.share()) + units / (outH * q.share()));
        double riskAdj = net - q.risk() * 0.5 * sg * Math.sqrt(cycle) * mid;
        boolean learned = trusted && !Double.isNaN(pred);
        if (learned) riskAdj += pred / 100.0 * mid * Math.min(1, cycle / 4.0); // expected drift while you hold
        boolean risky = net / buy * 100 > 100 || p.bidOrders() < 5 || p.askOrders() < 5;
        String why;
        boolean skip = false;
        if (risky) { skip = true; why = "thin book or huge margin: looks manipulated"; }
        else if (z > 2) { skip = true; why = "price spiked above fair: wait"; }
        else if (learned && pred < -1.5) { skip = true; why = "model expects a drop over the next 4h"; }
        else if (riskAdj <= 0) { skip = true; why = "risk eats the margin"; }
        else why = z < -1 ? "cheap vs fair: buy side favoured" : z > 1 ? "rich vs fair: sell side favoured" : "quote both sides";
        return new Signal(p, fair, z, sg, buy, sell, net, riskAdj, units, cycle, skip ? 0 : units * riskAdj / cycle, skip, why + (learned ? " (model adjusted)" : ""), pred);
    }

    public static List<Signal> rank(Collection<Market.Product> products, java.util.function.Function<String, List<Bars.Point>> recent,
                                    Params q, double minWeekVolume, double minPrice, int candidates) {
        return rank(products, recent, q, minWeekVolume, minPrice, candidates, id -> Double.NaN, false);
    }

    public static List<Signal> rank(Collection<Market.Product> products, java.util.function.Function<String, List<Bars.Point>> recent,
                                    Params q, double minWeekVolume, double minPrice, int candidates,
                                    java.util.function.ToDoubleFunction<String> pred, boolean trusted) {
        List<Market.Product> cand = new ArrayList<>();
        for (Market.Product p : products) if (p.bid() >= minPrice && p.weekVolume() >= minWeekVolume) cand.add(p);
        cand.sort(Comparator.comparingDouble(Market.Product::coinFlowDay).reversed());
        if (cand.size() > candidates) cand = cand.subList(0, candidates);
        List<Signal> out = new ArrayList<>();
        for (Market.Product p : cand) {
            Signal s = signal(p, recent.apply(p.id()), q, pred.applyAsDouble(p.id()), trusted);
            if (s != null && !s.skip()) out.add(s);
        }
        out.sort(Comparator.comparingDouble(Signal::edgeH).reversed());
        return out;
    }

    // ---------- backtest: the mean-reversion overlay on daily candles ----------

    /**
     * @param equity  growth of 1 coin, one value per day
     * @param entryZ  buys when the daily mid is this many sigma under its fair value
     * @param holdPct buy-and-hold return over the same days, after one round of tax
     */
    public record Result(int trades, int wins, double totalPct, double avgPct, double maxDrawdownPct, double holdPct, double firstHalfPct,
                         double secondHalfPct, double[] equity, String note) {}

    /**
     * Rule, fixed up front (nothing is fitted to the data, so there is nothing to overfit):
     * buy a bid at the daily bid + 0.1 when the daily mid is under its 3-day-half-life fair value by entryZ daily sigmas;
     * sell at the ask - 0.1 (after tax) once the mid is back at fair, or exit at the bid after 10 days or a 15% drop.
     * Assumes your buy order fills the day you place it and the sell fills the day the target is reached: optimistic.
     */
    public static Result backtest(List<Bars.Point> daily, double tax, double entryZ) {
        int n = daily.size();
        if (n < 40) return new Result(0, 0, 0, 0, 0, 0, 0, 0, new double[0], "Need at least 40 days of history");
        double[] mids = new double[n];
        for (int i = 0; i < n; i++) mids[i] = daily.get(i).mid();
        double tau = 3.0 / Math.log(2);
        double fair = mids[0];
        double[] eq = new double[n];
        double capital = 1, peak = 1, maxDd = 0;
        boolean in = false;
        double entry = 0;
        int entryDay = 0, trades = 0, wins = 0;
        double sumRet = 0;
        double[] dayEq = new double[n];
        double lastEqAtHalf = 1;
        for (int i = 0; i < n; i++) {
            double m = mids[i];
            if (m <= 0) { eq[i] = capital; continue; }
            double vol;
            { // daily sigma of log returns over the trailing 20 days
                int from = Math.max(1, i - 19);
                double s = 0, sq = 0;
                int c = 0;
                for (int k = from; k <= i; k++) if (mids[k] > 0 && mids[k - 1] > 0) { double r = Math.log(mids[k] / mids[k - 1]); s += r; sq += r * r; c++; }
                vol = c < 5 ? Double.NaN : Math.sqrt(Math.max(0, sq / c - (s / c) * (s / c)));
            }
            double z = Double.isNaN(vol) || vol == 0 ? 0 : Math.log(m / fair) / vol;
            if (in) {
                double sellPx = daily.get(i).ask() - 0.1;
                boolean target = m >= fair && sellPx * tax > entry;
                boolean timeout = i - entryDay >= 10, stop = m < entry * 0.85;
                if (target || timeout || stop) {
                    double exit = target ? sellPx * tax : daily.get(i).bid() * tax;
                    double ret = exit / entry - 1;
                    capital *= 1 + ret;
                    sumRet += ret;
                    trades++;
                    if (ret > 0) wins++;
                    in = false;
                }
            } else if (i > 20 && z <= -entryZ) {
                entry = daily.get(i).bid() + 0.1;
                entryDay = i;
                in = true;
            }
            double mark = in ? capital * (daily.get(i).bid() * tax / entry) : capital;
            eq[i] = mark;
            peak = Math.max(peak, mark);
            maxDd = Math.max(maxDd, 1 - mark / peak);
            fair += (m - fair) * (1 - Math.exp(-1 / tau));
            if (i == n / 2) lastEqAtHalf = mark;
        }
        double hold = mids[n - 1] * tax / mids[0] - 1;
        double total = eq[n - 1] - 1;
        double first = lastEqAtHalf - 1, second = eq[n - 1] / lastEqAtHalf - 1;
        String note = trades < 10 ? "Only " + trades + " trades: too few to trust" : "";
        return new Result(trades, wins, total * 100, trades == 0 ? 0 : sumRet / trades * 100, maxDd * 100, hold * 100, first * 100, second * 100, eq, note);
    }
}
