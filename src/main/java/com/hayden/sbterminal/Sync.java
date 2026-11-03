package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads your real orders off Hypixel's "Your Bazaar Orders" menu (Manage Orders). Pure logic:
 * the Minecraft side hands it each menu item's name and tooltip lines. Read-only.
 *
 * An order item looks like:
 *   BUY Broken Radar
 *     Order amount: 182x
 *     Filled: 50/182 27%
 *     Price per unit: 461,188.0 coins
 *     You have 50 items to claim!
 */
public final class Sync {
    private Sync() {}

    public record Order(boolean buy, String name, double amount, double filled, double price, boolean claimable) {}

    private static final Pattern AMOUNT = Pattern.compile("(?:Order|Offer) amount:\\s*([\\d,.]+[kKmM]?)x");
    private static final Pattern FILLED = Pattern.compile("Filled:\\s*([\\d,.]+[kKmM]?)\\s*/\\s*([\\d,.]+[kKmM]?)");
    private static final Pattern PRICE = Pattern.compile("Price per unit:\\s*([\\d,.]+)\\s*coins?");

    public static boolean isOrdersTitle(String title) {
        return Market.strip(title == null ? "" : title).toLowerCase(Locale.US).contains("bazaar orders");
    }

    public static Order parse(String name, List<String> lore) {
        if (name == null || lore == null) return null;
        String n = clean(name);
        boolean buy;
        String item;
        if (n.regionMatches(true, 0, "BUY ", 0, 4)) { buy = true; item = n.substring(4).trim(); }
        else if (n.regionMatches(true, 0, "SELL ", 0, 5)) { buy = false; item = n.substring(5).trim(); }
        else return null;
        double amount = -1, filled = 0, price = -1;
        boolean claimable = false;
        for (String raw : lore) {
            String l = clean(raw);
            Matcher m;
            if ((m = AMOUNT.matcher(l)).find()) amount = Ticket.parseNum(m.group(1));
            if ((m = FILLED.matcher(l)).find()) {
                filled = Ticket.parseNum(m.group(1));
                if (amount < 0) amount = Ticket.parseNum(m.group(2));
            }
            if ((m = PRICE.matcher(l)).find()) price = Double.parseDouble(m.group(1).replace(",", ""));
            if (l.toLowerCase(Locale.US).contains("to claim")) claimable = true;
        }
        if (amount <= 0 || price <= 0 || item.isEmpty()) return null;
        return new Order(buy, item, amount, Math.max(0, Math.min(filled, amount)), price, claimable);
    }

    public static List<Order> parseAll(List<String> names, List<List<String>> lores) {
        List<Order> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Order o = parse(names.get(i), i < lores.size() ? lores.get(i) : null);
            if (o != null) out.add(o);
        }
        return out;
    }

    private static String clean(String s) {
        return Market.strip(s).replaceAll("[^\\x20-\\x7E]", "").trim();
    }
}
