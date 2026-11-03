package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Biggest gainers and losers among items that actually trade. */
public final class Movers {
    public record Move(Market.Product p, double change) {}

    private Movers() {}

    /** @return {gainers, losers}, each best first, only items with weekly volume >= minWeekVolume */
    public static List<List<Move>> top(Collection<Market.Product> products, ToDoubleFunction<String> change,
                                       double minWeekVolume, int n) {
        List<Move> all = new ArrayList<>();
        for (Market.Product p : products) {
            if (p.bid() <= 0 || p.ask() <= 0 || p.weekVolume() < minWeekVolume) continue;
            double c = change.applyAsDouble(p.id());
            if (Double.isFinite(c)) all.add(new Move(p, c));
        }
        all.sort(Comparator.comparingDouble(Move::change).reversed());
        List<Move> up = new ArrayList<>(), down = new ArrayList<>();
        for (Move m : all) if (m.change() > 0 && up.size() < n) up.add(m);
        for (int i = all.size() - 1; i >= 0 && down.size() < n; i--) if (all.get(i).change() < 0) down.add(all.get(i));
        return List.of(up, down);
    }
}
