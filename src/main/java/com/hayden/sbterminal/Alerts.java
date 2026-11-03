package com.hayden.sbterminal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Price alerts: ALERT coal < 120, ALERT coal BID > 150, ALERT coal SPR > 5, ALERT coal CHG1H < -3.
 * Each rule fires once (chat + sound), then waits until you re-arm it. Pure logic.
 */
public final class Alerts {
    public enum Field { PX, BID, ASK, SPR, CHG1H }

    /** Saved in the config. */
    public static final class Rule {
        public String id, field, op;
        public double value;
        public boolean fired;
        public long firedAt;

        public Field f() {
            try { return Field.valueOf(field); } catch (Exception e) { return Field.PX; }
        }

        public String describe() {
            String v = f() == Field.SPR || f() == Field.CHG1H ? String.format(Locale.US, "%.1f%%", value) : Fmt.px(value);
            return (f() == Field.PX ? "price" : f().name()) + " " + op + " " + v;
        }
    }

    public record Parsed(String item, Field field, String op, double value, String error) {}

    private static final Pattern P = Pattern.compile("^(.*?)\\s*(>=|<=|>|<)\\s*(-?[\\d.,]+[kmbKMB]?)%?$");

    private Alerts() {}

    public static Parsed parse(String args) {
        Matcher m = P.matcher(args == null ? "" : args.trim());
        if (!m.matches()) return new Parsed(null, null, null, 0, "Try ALERT coal < 120, ALERT coal BID > 150, ALERT coal SPR > 5");
        String left = m.group(1).trim();
        Field f = Field.PX;
        String[] w = left.split("\\s+");
        if (w.length > 1) {
            String last = w[w.length - 1].toUpperCase(Locale.US);
            if (last.equals("PRICE") || last.equals("MID")) last = "PX";
            try { f = Field.valueOf(last); left = left.substring(0, left.length() - w[w.length - 1].length()).trim(); } catch (IllegalArgumentException ignored) {}
        }
        String num = m.group(3);
        boolean neg = num.startsWith("-");
        double v = Ticket.parseNum(neg ? num.substring(1) : num);
        if (v < 0 || left.isEmpty()) return new Parsed(null, null, null, 0, "Try ALERT coal < 120");
        return new Parsed(left, f, m.group(2), neg ? -v : v, null);
    }

    /** The live value a rule watches (NaN = unknown). */
    public static double value(Field f, Market.Product p, double chg1h) {
        if (p == null) return Double.NaN;
        return switch (f) {
            case PX -> p.mid();
            case BID -> p.bid();
            case ASK -> p.ask();
            case SPR -> p.spreadPct();
            case CHG1H -> chg1h;
        };
    }

    public static boolean test(String op, double x, double v) {
        if (!Double.isFinite(x)) return false;
        return switch (op) {
            case ">" -> x > v;
            case "<" -> x < v;
            case ">=" -> x >= v;
            case "<=" -> x <= v;
            default -> false;
        };
    }
}
