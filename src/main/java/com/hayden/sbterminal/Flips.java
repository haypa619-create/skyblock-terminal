package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Flip finder: ranks Bazaar items by what a buy-order / sell-offer flip could earn per hour.
 * Buy at bid + 0.1, sell at ask - 0.1, minus the Bazaar tax. Pure logic.
 */
public final class Flips {
    public enum Sort { EST, NET, ROI, VOLH, PRICE }

    /**
     * @param net   coins earned per unit after tax
     * @param roi   net as % of the buy price
     * @param volH  units that really trade per hour (thinner side of the 7-day volume)
     * @param est   coins/hour if you capture {@code share} of that volume
     * @param risky wide-open margins on thin books are usually manipulation, not free money
     */
    public record Row(Market.Product p, double buy, double sell, double net, double roi, double volH, double est, boolean risky) {}

    private Flips() {}

    public static Row row(Market.Product p, double taxFactor, double share) {
        double buy = p.bid() + 0.1, sell = p.ask() - 0.1;
        double net = sell * taxFactor - buy;
        double roi = buy > 0 ? net / buy * 100 : 0;
        double volH = p.weekVolume() / 168.0;
        boolean risky = roi > 100 || p.bidOrders() < 5 || p.askOrders() < 5;
        return new Row(p, buy, sell, net, roi, volH, Math.max(0, net) * volH * share, risky);
    }

    /** ROI % of the flip right now (for MARGIN alerts). NaN if the item has no book. */
    public static double roi(Market.Product p, double taxFactor) {
        if (p == null || p.bid() <= 0 || p.ask() <= 0) return Double.NaN;
        return row(p, taxFactor, 1).roi();
    }

    /** Net coins per unit (for NET alerts). */
    public static double net(Market.Product p, double taxFactor) {
        if (p == null || p.bid() <= 0 || p.ask() <= 0) return Double.NaN;
        return row(p, taxFactor, 1).net();
    }

    public static List<Row> run(Collection<Market.Product> products, double taxFactor, double share,
                                double minWeekVolume, boolean showRisky, Sort sort, boolean desc) {
        List<Row> out = new ArrayList<>();
        for (Market.Product p : products) {
            if (p.bid() <= 0 || p.ask() <= 0 || p.weekVolume() < minWeekVolume) continue;
            Row r = row(p, taxFactor, share);
            if (r.net() <= 0) continue;
            if (r.risky() && !showRisky) continue;
            out.add(r);
        }
        Comparator<Row> c = Comparator.comparingDouble(r -> switch (sort) {
            case EST -> r.est();
            case NET -> r.net();
            case ROI -> r.roi();
            case VOLH -> r.volH();
            case PRICE -> r.buy();
        });
        out.sort(desc ? c.reversed() : c);
        return out;
    }
}
