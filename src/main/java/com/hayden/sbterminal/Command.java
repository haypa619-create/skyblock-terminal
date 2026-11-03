package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The command line, Bloomberg style. Function codes:
 *   MON              monitor (your watchlist)
 *   MOV              movers
 *   SCR [filters]    screener, e.g. SCR VOL>50K SPR>3 PX<1M CHG<-5
 *   HELP             commands and keys
 *   ADD item         add to the watchlist        DEL item   remove it
 *   item [GP|DES|OB] [1H|1D|1W]   item page (GP = chart, OB = order book)
 * Anything that isn't a function code is treated as an item.
 */
public final class Command {
    public enum Fn { MON, MOV, SCR, HELP, ADD, DEL, FONT, ITEM, EMPTY }

    /** Function codes, for the autocomplete dropdown. */
    public static final String[][] CODES = {
            {"MON", "Monitor (your watchlist)"}, {"MOV", "Movers"}, {"SCR", "Screener"}, {"HELP", "Commands and keys"},
            {"ADD", "Add an item to your Monitor"}, {"DEL", "Remove an item from your Monitor"},
            {"FONT", "FONT MONO or FONT MC"}};
    public enum Range {
        H1("1H", 3_600_000L), H6("6H", 6 * 3_600_000L), D1("1D", 86_400_000L), D3("3D", 3 * 86_400_000L), W1("1W", 7 * 86_400_000L);
        public final String label;
        public final long span;
        Range(String label, long span) { this.label = label; this.span = span; }
    }

    public record Parsed(Fn fn, String item, String view, Range range, List<Screener.Filter> filters, String error) {}

    private static final Set<String> VIEWS = Set.of("GP", "DES", "OB");

    private Command() {}

    public static Parsed parse(String line) {
        String s = line == null ? "" : line.trim();
        if (s.isEmpty()) return new Parsed(Fn.EMPTY, null, null, null, List.of(), null);
        String[] w = s.split("\\s+");
        String head = w[0].toUpperCase(Locale.US);
        switch (head) {
            case "MON", "W", "WATCH" -> { return simple(Fn.MON); }
            case "MOV", "MOST", "TOP" -> { return simple(Fn.MOV); }
            case "HELP", "?" -> { return simple(Fn.HELP); }
            case "FONT" -> { return new Parsed(Fn.FONT, w.length > 1 ? w[1].toUpperCase(Locale.US) : "", null, null, List.of(), null); }
            case "SCR", "EQS", "SCREEN" -> {
                List<Screener.Filter> fs = new ArrayList<>();
                for (int i = 1; i < w.length; i++) {
                    Screener.Filter f = Screener.Filter.parse(w[i]);
                    if (f == null) return new Parsed(Fn.SCR, null, null, null, List.of(), "Can't read filter \"" + w[i] + "\". Try VOL>50K SPR>3 PX<1M CHG<-5");
                    fs.add(f);
                }
                return new Parsed(Fn.SCR, null, null, null, fs, null);
            }
            case "ADD", "DEL" -> {
                if (w.length < 2) return new Parsed(head.equals("ADD") ? Fn.ADD : Fn.DEL, null, null, null, List.of(), "Which item? e.g. " + head + " enchanted coal");
                return new Parsed(head.equals("ADD") ? Fn.ADD : Fn.DEL, s.substring(w[0].length()).trim(), null, null, List.of(), null);
            }
            default -> {
                // item [GP|DES|OB] [1H|1D|1W], codes can come in any order after the item
                String view = "DES";
                Range range = null;
                int end = w.length;
                while (end > 1) {
                    String t = w[end - 1].toUpperCase(Locale.US);
                    if (VIEWS.contains(t)) { view = t; end--; }
                    else if (rangeOf(t) != null) { range = rangeOf(t); end--; }
                    else break;
                }
                StringBuilder item = new StringBuilder();
                for (int i = 0; i < end; i++) { if (i > 0) item.append(' '); item.append(w[i]); }
                return new Parsed(Fn.ITEM, item.toString(), view, range, List.of(), null);
            }
        }
    }

    static Range rangeOf(String t) {
        return switch (t) {
            case "1H" -> Range.H1;
            case "6H" -> Range.H6;
            case "1D", "24H" -> Range.D1;
            case "3D" -> Range.D3;
            case "1W", "7D" -> Range.W1;
            default -> null;
        };
    }

    private static Parsed simple(Fn fn) {
        return new Parsed(fn, null, null, null, List.of(), null);
    }
}
