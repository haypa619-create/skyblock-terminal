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
    public enum Fn { MON, MOV, SCR, HELP, ADD, DEL, FONT, BUY, SELL, QTY, PX, PORT, POS, ALERT, ALRT, PNL, SECT, CMP, LIMIT, KEYS, FLIP, LIST, NOTIFY, BB, BIND, ALGO, BT, ITEM, EMPTY }

    /** Function codes, for the autocomplete dropdown. */
    public static final String[][] CODES = {
            {"MON", "Monitor (your watchlist)"}, {"MOV", "Movers"}, {"SCR", "Screener"}, {"HELP", "Commands and keys"},
            {"ADD", "Add an item to your Monitor"}, {"DEL", "Remove an item from your Monitor"},
            {"FONT", "FONT MONO or FONT MC"},
            {"BUY", "BUY 5k [price] [NOW]: open the order in the Bazaar"}, {"SELL", "SELL [price] [NOW]: list what you carry"},
            {"PORT", "Portfolio: positions, cost, P&L, open orders"}, {"PNL", "P&L history: equity curve, by day, by item"},
            {"ALERT", "ALERT coal < 120 (or BID/ASK/SPR/CHG1H)"}, {"ALRT", "Your alerts"}, {"SECT", "Sectors: index moves by Bazaar category"},
            {"CMP", "CMP <item>: compare on the chart (CMP alone clears)"}, {"LIMIT", "Daily limit ledger, LIMIT 15B, LIMIT RESET 7:00 PM"}, {"POS", "POS item units cost: set a position by hand"},
            {"QTY", "QTY 5k or QTY MAX: ticket quantity"}, {"PX", "PX 123.4 or PX AUTO: ticket price"},
            {"FLIP", "Flip finder. FLIP MIN 5k = cheapest item, FLIP SLOTS 36 = inventory size"},
            {"LIST", "LIST mining: switch or create a Monitor list (LIST alone cycles)"},
            {"NOTIFY", "NOTIFY FILLED OFF, SOUND GOOD BELL, LIMIT 85"}, {"ALGO", "Trading model: market-making signals ranked by risk-adjusted profit per hour"}, {"BT", "BT <item>: backtest the mean-reversion rule on years of daily history"}, {"BB", "Bollinger bands on the chart on/off"}, {"BIND", "BIND FLIP, BIND FLIP F6, BIND RESET: set your hotkeys"}};
    public enum Range {
        H1("1H", 3_600_000L), H6("6H", 6 * 3_600_000L), D1("1D", 86_400_000L), D3("3D", 3 * 86_400_000L), W1("1W", 7 * 86_400_000L),
        M1("1M", 30 * 86_400_000L), M3("3M", 90 * 86_400_000L), Y1("1Y", 365 * 86_400_000L), ALL("ALL", 3650L * 86_400_000L);
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
            case "PORT", "PF", "POSITIONS" -> { return simple(Fn.PORT); }
            case "PNL", "JRNL", "JOURNAL" -> { return simple(Fn.PNL); }
            case "SECT", "SECTORS", "SEC" -> { return simple(Fn.SECT); }
            case "ALRT", "ALERTS" -> { return simple(Fn.ALRT); }
            case "ALERT", "CMP", "LIMIT" -> {
                String rest = s.substring(w[0].length()).trim();
                Fn fn = head.equals("ALERT") && rest.isEmpty() ? Fn.ALRT : Fn.valueOf(head);
                return new Parsed(fn, rest, null, null, List.of(), null);
            }
            case "POS" -> { return new Parsed(Fn.POS, s.substring(w[0].length()).trim(), null, null, List.of(), null); }
            case "BUY", "SELL", "QTY", "PX" -> {
                Fn fn = Fn.valueOf(head);
                return new Parsed(fn, s.substring(w[0].length()).trim().toUpperCase(Locale.US), null, null, List.of(), null);
            }
            case "KEYS" -> { return simple(Fn.KEYS); }
            case "FLIP", "FLIPS" -> { return new Parsed(Fn.FLIP, s.substring(w[0].length()).trim().toUpperCase(Locale.US), null, null, List.of(), null); }
            case "BIND", "KEYMAP", "HOTKEYS" -> { return new Parsed(Fn.BIND, s.substring(w[0].length()).trim().toUpperCase(Locale.US), null, null, List.of(), null); }
            case "ALGO", "QUANT", "MM" -> { return simple(Fn.ALGO); }
            case "BT", "BACKTEST" -> { return new Parsed(Fn.BT, s.substring(w[0].length()).trim(), null, null, List.of(), null); }
            case "BB", "BOLL" -> { return simple(Fn.BB); }
            case "LIST", "LISTS" -> { return new Parsed(Fn.LIST, s.substring(w[0].length()).trim(), null, null, List.of(), null); }
            case "NOTIFY", "NOTIF" -> { return new Parsed(Fn.NOTIFY, s.substring(w[0].length()).trim().toUpperCase(Locale.US), null, null, List.of(), null); }
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
            case "1M", "30D" -> Range.M1;
            case "3M", "90D" -> Range.M3;
            case "1Y", "365D" -> Range.Y1;
            case "ALL", "MAX" -> Range.ALL;
            default -> null;
        };
    }

    private static Parsed simple(Fn fn) {
        return new Parsed(fn, null, null, null, List.of(), null);
    }
}
