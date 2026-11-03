package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters and sorts every Bazaar item.
 * Fields: VOL (weekly units, thinner side), FLOW (coins traded per day), PX (mid price),
 * SPR (spread %), CHG (24h change %), CHG1H (1h change %), ORD (orders on both sides).
 */
public final class Screener {
    public enum Field { VOL, FLOW, PX, SPR, CHG, CHG1H, ORD }

    public record Filter(Field field, String op, double value) {
        private static final Pattern P = Pattern.compile("(?i)^([A-Z0-9]+)(>=|<=|>|<|=)(-?[0-9.]+)([KMB]?)$");

        public static Filter parse(String s) {
            Matcher m = P.matcher(s.trim());
            if (!m.matches()) return null;
            Field f;
            try { f = Field.valueOf(m.group(1).toUpperCase(Locale.US)); } catch (IllegalArgumentException e) { return null; }
            double v = Double.parseDouble(m.group(3));
            switch (m.group(4).toUpperCase(Locale.US)) {
                case "K" -> v *= 1e3;
                case "M" -> v *= 1e6;
                case "B" -> v *= 1e9;
                default -> {}
            }
            return new Filter(f, m.group(2), v);
        }

        boolean test(double x) {
            if (!Double.isFinite(x)) return false;
            return switch (op) {
                case ">" -> x > value;
                case "<" -> x < value;
                case ">=" -> x >= value;
                case "<=" -> x <= value;
                default -> Math.abs(x - value) < 1e-9;
            };
        }

        @Override
        public String toString() {
            return field + op + Fmt.qty(value);
        }
    }

    /** Everything the screener can filter or sort on for one item. */
    public record Row(Market.Product p, double chg24, double chg1h) {
        public double get(Field f) {
            return switch (f) {
                case VOL -> p.weekVolume();
                case FLOW -> p.coinFlowDay();
                case PX -> p.mid();
                case SPR -> p.spreadPct();
                case CHG -> chg24;
                case CHG1H -> chg1h;
                case ORD -> p.bidOrders() + p.askOrders();
            };
        }
    }

    private Screener() {}

    public static List<Row> run(Collection<Market.Product> products, List<Filter> filters,
                                ToDoubleFunction<String> chg24, ToDoubleFunction<String> chg1h,
                                Field sortBy, boolean descending) {
        List<Row> out = new ArrayList<>();
        for (Market.Product p : products) {
            if (p.bid() <= 0 || p.ask() <= 0) continue;
            Row r = new Row(p, chg24.applyAsDouble(p.id()), chg1h.applyAsDouble(p.id()));
            boolean ok = true;
            for (Filter f : filters) if (!f.test(r.get(f.field()))) { ok = false; break; }
            if (ok) out.add(r);
        }
        Comparator<Row> c = Comparator.comparingDouble(r -> {
            double v = r.get(sortBy);
            return Double.isFinite(v) ? v : (descending ? -Double.MAX_VALUE : Double.MAX_VALUE);
        });
        out.sort(descending ? c.reversed() : c);
        return out;
    }
}
