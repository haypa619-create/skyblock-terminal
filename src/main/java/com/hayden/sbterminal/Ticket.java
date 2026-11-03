package com.hayden.sbterminal;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The order ticket. Remembers the order you launched from the item page and decides what to
 * type into Hypixel's signs and which menu slot to point at. Pure logic, no Minecraft code.
 * It only ever fills in text and points; you press every confirm yourself.
 */
public final class Ticket {
    public enum Side { BUY, SELL }
    public enum Mode { ORDER, INSTA, SYNC, CANCEL, CLAIM }
    public enum Field { AMOUNT, PRICE, UNKNOWN }

    /** itemName is the Bazaar display name; amount 0 = not needed (sell offers use what you hold). */
    public record Active(String id, String itemName, Side side, Mode mode, double price, long amount, long createdAt, double oldPrice) {
        public Active(String id, String itemName, Side side, Mode mode, double price, long amount, long createdAt) {
            this(id, itemName, side, mode, price, amount, createdAt, 0);
        }
    }

    public static final long TTL_MS = 5 * 60 * 1000;
    public static final long GAP_MS = 2000;

    private volatile Active active;
    private volatile long gapSince = 0;

    public void start(Active a) { active = a; gapSince = 0; }
    public void clear() { active = null; }
    public void inMenus() { gapSince = 0; }
    public void leftMenus(long now) { if (active != null && gapSince == 0) gapSince = now; }
    public boolean abandoned(long now) { return active != null && gapSince > 0 && now - gapSince > GAP_MS; }

    public Active current(long now) {
        Active a = active;
        if (a == null) return null;
        if (now - a.createdAt() > TTL_MS) { active = null; return null; }
        return a;
    }

    public void reprice(double price, long now) {
        Active a = active;
        if (a != null) active = new Active(a.id(), a.itemName(), a.side(), a.mode(), price, a.amount(), now, a.oldPrice());
    }

    // ---------- numbers ----------

    private static final Pattern NUM = Pattern.compile("(?i)^([\\d,]*\\.?\\d+)\\s*([kmb]?)$");

    /** "5k" -> 5000, "1.5m" -> 1500000, "1,000" -> 1000. -1 if it isn't a number. */
    public static double parseNum(String s) {
        if (s == null) return -1;
        Matcher m = NUM.matcher(s.trim());
        if (!m.matches()) return -1;
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        return switch (m.group(2).toLowerCase(Locale.US)) {
            case "k" -> v * 1_000;
            case "m" -> v * 1_000_000;
            case "b" -> v * 1_000_000_000;
            default -> v;
        };
    }

    public static double round1(double v) { return Math.round(v * 10) / 10.0; }

    /** The price that puts you on top: bid + 0.1 to buy, ask - 0.1 to sell. */
    public static double joinPrice(Side side, double bid, double ask) {
        return side == Side.BUY ? round1(bid + 0.1) : round1(ask - 0.1);
    }

    /** 130.4 -> "130.4". No commas, since the sign wants a plain number. */
    public static String formatPrice(double price) { return String.format(Locale.US, "%.1f", round1(price)); }

    // ---------- signs ----------

    /** Which number a Hypixel sign asks for, from its prompt lines (lines 2-4). Unclear = UNKNOWN. */
    public static Field classify(String[] lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lines.length; i++) if (lines[i] != null) sb.append(' ').append(Market.strip(lines[i]).toLowerCase(Locale.US));
        String p = sb.toString();
        boolean price = p.contains("price") || p.contains("per unit") || p.contains("coins");
        boolean amount = p.contains("amount") || p.contains("quantity") || p.contains("how many");
        if (price && !amount) return Field.PRICE;
        if (amount && !price) return Field.AMOUNT;
        return Field.UNKNOWN;
    }

    /** Text for the sign's first line, or null to leave it alone. */
    public String prefillFor(String[] lines, long now) {
        Active a = current(now);
        if (a == null || lines == null || lines.length == 0) return null;
        if (lines[0] != null && !Market.strip(lines[0]).isBlank()) return null; // you already typed
        return switch (classify(lines)) {
            case PRICE -> a.mode() == Mode.ORDER && a.price() > 0 ? formatPrice(a.price()) : null;
            case AMOUNT -> a.side() == Side.BUY && a.amount() > 0 ? Long.toString(a.amount()) : null;
            case UNKNOWN -> null;
        };
    }

    // ---------- menus ----------

    public static boolean isBazaarTitle(String title) {
        String t = Market.strip(title).toLowerCase(Locale.US);
        return t.contains("bazaar") || t.contains("➜")
                || t.contains("buy order") || t.contains("sell offer") || t.contains("order options")
                || t.contains("how many") || t.contains("how much")
                || t.contains("at what price") || t.contains("your orders")
                || t.contains("confirm") && (t.contains("order") || t.contains("offer"));
    }

    private static final Pattern LORE_PRICE = Pattern.compile("(?i)price per unit:\\s*([\\d,]+(?:\\.\\d+)?)");

    /**
     * The menu slot to point at next, or -1. Checks the latest step first.
     * Order: search results (the item) > item page (Create Buy Order / Sell Offer) > amount
     * (Custom Amount) > price (Custom Price, or Hypixel's own +0.1 button when it is exactly your price) > confirm.
     * Instant: item > Buy/Sell Instantly > Custom Amount > confirm.
     */
    public static int pickSlot(String title, List<String> names, List<List<String>> lores, Active a) {
        if (a == null || names == null) return -1;
        String t = Market.strip(title == null ? "" : title).toLowerCase(Locale.US);
        boolean buy = a.side() == Side.BUY;
        String item = a.itemName().toLowerCase(Locale.US);

        if (a.mode() == Mode.SYNC) return t.contains("bazaar orders") ? -1 : find(names, "manage orders", false);
        if (a.mode() == Mode.CLAIM) {
            // Bazaar > Manage Orders > the order with something to claim (clicking it claims)
            if (t.contains("bazaar orders")) {
                for (int k = 0; k < names.size(); k++) {
                    Sync.Order o = Sync.parse(names.get(k), lores == null || k >= lores.size() ? null : lores.get(k));
                    if (o != null && claimMatches(o, a)) return k;
                }
                return -1;
            }
            return find(names, "manage orders", false);
        }
        if (a.mode() == Mode.CANCEL) {
            // Bazaar > Manage Orders > your order (matched by side, name and old price) > Cancel Order
            int c = find(names, "cancel order", false);
            if (c < 0) c = find(names, "cancel offer", false);
            if (c >= 0) return c;
            if (t.contains("bazaar orders")) {
                int loose = -1;
                for (int k = 0; k < names.size(); k++) {
                    Sync.Order o = Sync.parse(names.get(k), lores == null || k >= lores.size() ? null : lores.get(k));
                    if (o == null || o.buy() != buy || !o.name().equalsIgnoreCase(a.itemName())) continue;
                    if (Math.abs(o.price() - a.oldPrice()) < 0.05) return k;
                    if (loose < 0) loose = k;
                }
                return loose;
            }
            return find(names, "manage orders", false);
        }

        if (a.mode() == Mode.INSTA) {
            int c = t.contains("confirm") ? find(names, "confirm", false) : -1;
            if (c < 0 && buy) c = find(names, "custom amount", false);
            if (c < 0) c = find(names, buy ? "buy instantly" : "sell instantly", false);
            return c >= 0 ? c : find(names, item, true);
        }
        if (t.contains("confirm")) {
            int i = find(names, buy ? "buy order" : "sell offer", false);
            return i >= 0 ? i : find(names, "confirm", false);
        }
        int i = find(names, "custom price", false);
        if (i >= 0) {
            if (lores != null && a.price() > 0) {
                for (int k = 0; k < names.size(); k++) {
                    if (k == i || names.get(k) == null || clean(names.get(k)).contains("custom")) continue;
                    double v = loreNumber(lores, k, LORE_PRICE);
                    if (v > 0 && Math.abs(v - a.price()) <= 0.05) return k;
                }
            }
            return i;
        }
        if (buy) {
            i = find(names, "custom amount", false);
            if (i >= 0) return i;
        }
        i = find(names, buy ? "create buy order" : "create sell offer", false);
        if (i >= 0) return i;
        return find(names, item, true);
    }

    /** Whether a Manage Orders entry is a claimable order this CLAIM ticket is after (empty name = any). */
    public static boolean claimMatches(Sync.Order o, Active a) {
        if (!o.claimable()) return false;
        return a.itemName().isEmpty() || o.buy() == (a.side() == Side.BUY) && o.name().equalsIgnoreCase(a.itemName());
    }

    private static double loreNumber(List<List<String>> lores, int k, Pattern p) {
        if (k >= lores.size() || lores.get(k) == null) return -1;
        for (String line : lores.get(k)) {
            Matcher m = p.matcher(Market.strip(line));
            if (m.find()) {
                try { return Double.parseDouble(m.group(1).replace(",", "")); } catch (NumberFormatException e) { return -1; }
            }
        }
        return -1;
    }

    private static String clean(String n) { return n == null ? "" : Market.strip(n).toLowerCase(Locale.US); }

    private static int find(List<String> names, String needle, boolean exact) {
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null) continue;
            n = Market.strip(n).replaceAll("[^\\x20-\\x7E]", "").replaceAll("\\s+", " ").toLowerCase(Locale.US).trim();
            if (exact ? n.equals(needle) : n.contains(needle)) return i;
        }
        return -1;
    }
}
