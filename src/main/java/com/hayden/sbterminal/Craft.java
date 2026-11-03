package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Structural profit: crafts whose output sells for more than the inputs cost, and items an NPC buys for more than the
 * Bazaar asks. Pure logic. You do the crafting/selling by hand.
 */
public final class Craft {
    private Craft() {}

    /**
     * @param cost   inputs bought with buy orders (bid + 0.1 each)
     * @param fast   inputs insta-bought (ask)
     * @param sell   output sold with a sell offer (ask - 0.1), after tax
     * @param profit sell - cost per craft; fastProfit = sell - fast
     * @param perH   crafts per hour the market can absorb (output demand, input supply, your share)
     */
    public record Row(String kind, String id, String inputs, double cost, double fast, double sell, double profit, double fastProfit,
                      double roi, double perH, double profitH) {}

    public static List<Row> scan(Map<String, Recipes.Recipe> recipes, Map<String, Market.Product> prods, Map<String, Double> npc,
                                 double taxPct, double share, double minProfitH) {
        List<Row> out = new ArrayList<>();
        double keep = 1 - taxPct / 100.0;
        for (Recipes.Recipe r : recipes.values()) {
            Market.Product o = prods.get(r.output());
            if (o == null || o.ask() <= 0 || o.bid() <= 0) continue;
            double cost = 0, fast = 0, supply = Double.MAX_VALUE;
            boolean ok = true;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : r.inputs().entrySet()) {
                Market.Product p = prods.get(e.getKey());
                if (p == null || p.bid() <= 0 || p.ask() <= 0) { ok = false; break; }
                int q = e.getValue();
                cost += q * (p.bid() + 0.1);
                fast += q * p.ask();
                supply = Math.min(supply, p.instaSellWeek() / 168.0 * share / q);
                if (sb.length() > 0) sb.append(", ");
                sb.append(q).append("x ").append(e.getKey());
            }
            if (!ok) continue;
            double sell = r.outCount() * (o.ask() - 0.1) * keep;
            double demand = o.instaBuyWeek() / 168.0 * share / r.outCount();
            double perH = Math.min(demand, supply);
            double profit = sell - cost;
            if (profit <= 0) continue;
            double ph = profit * perH;
            if (ph < minProfitH) continue;
            out.add(new Row("CRAFT", r.output(), sb.toString(), cost, fast, sell, profit, sell - fast, profit / cost * 100, perH, ph));
        }
        for (Map.Entry<String, Double> e : npc.entrySet()) {
            Market.Product p = prods.get(e.getKey());
            double n = e.getValue();
            if (p == null || n <= 0 || p.bid() <= 0) continue;
            double cost = p.bid() + 0.1, profit = n - cost; // NPC sales pay no Bazaar tax
            if (profit <= 0) continue;
            double perH = p.instaSellWeek() / 168.0 * share;
            double ph = profit * perH;
            if (ph < minProfitH) continue;
            out.add(new Row("NPC", p.id(), "sell to any NPC at " + n, cost, p.ask(), n, profit, n - p.ask(), profit / cost * 100, perH, ph));
        }
        out.sort(Comparator.comparingDouble((Row x) -> -x.profitH()));
        return out;
    }
}
