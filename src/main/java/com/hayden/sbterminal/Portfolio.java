package com.hayden.sbterminal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Positions, cost basis, open orders and realized P&L, built from Hypixel's own Bazaar chat
 * lines. Read-only: it listens to chat and never sends or clicks anything.
 *
 * A position starts when you CLAIM bought items (or insta-buy), at the price you paid.
 * It shrinks when you claim sold items (or insta-sell), booking P&L against average cost.
 * Coins from Hypixel claim lines are already after Bazaar tax, so realized P&L is net.
 */
public final class Portfolio {
    public static final class Pos {
        public String name;
        public double units, cost; // cost = average price per unit
    }

    public static final class Order {
        public String name;
        public boolean buy, filled;
        public double units, price, claimed;
        public long at;
    }

    public Map<String, Pos> positions = new LinkedHashMap<>();
    public List<Order> orders = new ArrayList<>();
    /** yyyy-MM-dd -> realized coins. */
    public Map<String, Double> daily = new LinkedHashMap<>();
    public double realizedAll = 0;
    /** Sold units that had no tracked cost (bought before the terminal was watching). */
    public double untrackedUnits = 0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private transient Path file;

    // ---- Hypixel chat lines ----
    static final Pattern SETUP = Pattern.compile("^\\[Bazaar\\] (Buy Order|Sell Offer) Setup! ([\\d,]+)x (.+?) for ([\\d,.]+) coins?\\.?$");
    static final Pattern FILLED = Pattern.compile("^\\[Bazaar\\] Your (Buy Order|Sell Offer) for ([\\d,]+)x (.+?) was filled!.*$");
    static final Pattern CANCEL_SELL = Pattern.compile("^\\[Bazaar\\] Cancelled! Refunded ([\\d,]+)x (.+?) from cancelling Sell Offer!.*$");
    static final Pattern CANCEL_BUY = Pattern.compile("^\\[Bazaar\\] Cancelled! Refunded ([\\d,.]+) coins? from cancelling Buy Order!.*$");
    static final Pattern INSTA_BUY = Pattern.compile("^\\[Bazaar\\] Bought ([\\d,]+)x (.+?) for ([\\d,.]+) coins?!?.*$");
    static final Pattern INSTA_SELL = Pattern.compile("^\\[Bazaar\\] Sold ([\\d,]+)x (.+?) for ([\\d,.]+) coins?!?.*$");
    static final Pattern CLAIM_BUY = Pattern.compile("^\\[Bazaar\\] Claimed ([\\d,]+)x (.+?) worth ([\\d,.]+) coins? bought for ([\\d,.]+) each!.*$");
    static final Pattern CLAIM_SELL = Pattern.compile("^\\[Bazaar\\] Claimed ([\\d,.]+) coins? from selling ([\\d,]+)x (.+?) at ([\\d,.]+) each!.*$");

    public static Portfolio load(Path dir) {
        Path f = dir.resolve("sbterminal-portfolio.json");
        Portfolio p = null;
        try {
            if (Files.exists(f)) p = GSON.fromJson(Files.readString(f), Portfolio.class);
        } catch (Exception e) {
            Terminal.LOG.warn("Could not read portfolio, starting empty", e);
        }
        if (p == null) p = new Portfolio();
        if (p.positions == null) p.positions = new LinkedHashMap<>();
        if (p.orders == null) p.orders = new ArrayList<>();
        if (p.daily == null) p.daily = new LinkedHashMap<>();
        p.file = f;
        return p;
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            Terminal.LOG.warn("Could not save portfolio", e);
        }
    }

    static String key(String name) { return Items.norm(name); }

    static double num(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return 0; }
    }

    public static String day(long now) {
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault()).toString();
    }

    public double realizedOn(String day) { return daily.getOrDefault(day, 0.0); }

    // ---------- chat ----------

    /** Feeds one chat line. Returns true when something changed. */
    public synchronized boolean onChat(String raw, long now) {
        String line = Market.strip(raw).trim();
        if (!line.startsWith("[Bazaar]")) return false;
        Matcher m;
        if ((m = SETUP.matcher(line)).matches()) {
            double u = num(m.group(2));
            if (u <= 0) return false;
            Order o = new Order();
            o.buy = m.group(1).startsWith("Buy");
            o.units = u;
            o.name = m.group(3);
            o.price = num(m.group(4)) / u;
            o.at = now;
            orders.add(o);
            return true;
        }
        if ((m = FILLED.matcher(line)).matches()) {
            Order o = find(m.group(3), m.group(1).startsWith("Buy"), false);
            if (o == null) return false;
            o.filled = true;
            return true;
        }
        if ((m = CANCEL_SELL.matcher(line)).matches()) {
            Order o = find(m.group(2), false, false);
            if (o != null) orders.remove(o);
            return o != null;
        }
        if ((m = CANCEL_BUY.matcher(line)).matches()) {
            double coins = num(m.group(1));
            Order best = null;
            for (Order o : orders) {
                if (!o.buy || o.filled) continue;
                double gap = Math.abs(o.units * o.price - coins);
                if (gap <= Math.max(2, coins * 0.01) && (best == null || o.at < best.at)) best = o;
            }
            if (best != null) orders.remove(best);
            return best != null;
        }
        if ((m = CLAIM_BUY.matcher(line)).matches()) {
            double u = num(m.group(1)), each = num(m.group(4));
            addUnits(m.group(2), u, each);
            claimOrder(m.group(2), true, u);
            return true;
        }
        if ((m = CLAIM_SELL.matcher(line)).matches()) {
            sellUnits(m.group(3), num(m.group(2)), num(m.group(1)), now);
            claimOrder(m.group(3), false, num(m.group(2)));
            return true;
        }
        if ((m = INSTA_BUY.matcher(line)).matches()) {
            double u = num(m.group(1));
            if (u <= 0) return false;
            addUnits(m.group(2), u, num(m.group(3)) / u);
            return true;
        }
        if ((m = INSTA_SELL.matcher(line)).matches()) {
            double u = num(m.group(1));
            if (u <= 0) return false;
            sellUnits(m.group(2), u, num(m.group(3)), now);
            return true;
        }
        return false;
    }

    private Order find(String name, boolean buy, boolean anyFilledState) {
        Order best = null;
        for (Order o : orders) {
            if (o.buy != buy || !key(o.name).equals(key(name))) continue;
            if (!anyFilledState && o.filled) continue;
            if (best == null || o.at < best.at) best = o;
        }
        return best;
    }

    private void claimOrder(String name, boolean buy, double units) {
        Order o = find(name, buy, true);
        if (o == null) return;
        o.claimed += units;
        if (o.claimed >= o.units - 0.5) orders.remove(o);
    }

    private void addUnits(String name, double units, double each) {
        Pos p = positions.computeIfAbsent(key(name), k -> { Pos x = new Pos(); x.name = name; return x; });
        double total = p.units + units;
        p.cost = total <= 0 ? 0 : (p.units * p.cost + units * each) / total;
        p.units = total;
    }

    private void sellUnits(String name, double units, double coins, long now) {
        if (units <= 0) return;
        Pos p = positions.get(key(name));
        double tracked = p == null ? 0 : Math.min(units, p.units);
        if (tracked > 0) {
            double pnl = coins * (tracked / units) - p.cost * tracked;
            realizedAll += pnl;
            daily.merge(day(now), pnl, Double::sum);
            p.units -= tracked;
            if (p.units < 0.5) positions.remove(key(name));
        }
        untrackedUnits += units - tracked;
    }

    // ---------- manual edits ----------

    /** POS item units cost: set or replace a position by hand (for what you held before tracking). */
    public synchronized void setPosition(String name, double units, double cost) {
        if (units <= 0) { positions.remove(key(name)); return; }
        Pos p = positions.computeIfAbsent(key(name), k -> { Pos x = new Pos(); x.name = name; return x; });
        p.units = units;
        p.cost = cost;
    }

    public synchronized void removePosition(String name) { positions.remove(key(name)); }

    public synchronized List<Pos> positionList() { return new ArrayList<>(positions.values()); }

    public synchronized List<Order> orderList() { return new ArrayList<>(orders); }

    // ---------- order status ----------

    public enum Status { TOP, BEHIND, FILLED }

    /** Where an open order stands against the live book. */
    public static Status status(Order o, Market.Product p) {
        if (o.filled) return Status.FILLED;
        if (p == null) return Status.TOP;
        return o.buy ? (o.price >= p.bid() - 0.05 ? Status.TOP : Status.BEHIND)
                : (o.price <= p.ask() + 0.05 ? Status.TOP : Status.BEHIND);
    }
}
