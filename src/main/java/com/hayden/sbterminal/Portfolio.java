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

    /** One order, offer or instant trade that counts toward the daily limit. */
    public static final class Entry {
        public long at;
        public String kind, name;
        public double value;
    }

    /** A closed sale against tracked cost. */
    public static final class Trade {
        public String name;
        public long at;
        public double units, cost, price, pnl;
    }

    public static final class Order {
        public String name;
        public boolean buy, filled;
        public double units, price, claimed, filledUnits;
        public boolean claimable;
        public long at;
    }

    public Map<String, Pos> positions = new LinkedHashMap<>();
    public List<Order> orders = new ArrayList<>();
    /** yyyy-MM-dd -> realized coins. */
    public Map<String, Double> daily = new LinkedHashMap<>();
    public double realizedAll = 0;
    /** Sold units that had no tracked cost (bought before the terminal was watching). */
    public double untrackedUnits = 0;
    /** When Manage Orders last replaced the order list (0 = never). */
    public long syncedAt = 0;
    /** Everything counted against the daily limit, with the time you did it. Cancelled orders still count. */
    public List<Entry> ledger = new ArrayList<>();
    /** When the terminal started recording the ledger, so a learned limit is only trusted if it saw the whole window. */
    public long trackingSince = 0;
    /** When Hypixel last told you the limit was reached (0 = never). */
    public long blockedAt = 0;
    public List<Trade> trades = new ArrayList<>();
    /** "B:item" / "S:item" -> how long orders took to fill, newest last (ms). */
    public Map<String, List<Long>> fills = new LinkedHashMap<>();

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
        if (p.ledger == null) p.ledger = new ArrayList<>();
        if (p.trackingSince == 0) p.trackingSince = System.currentTimeMillis();
        if (p.trades == null) p.trades = new ArrayList<>();
        if (p.fills == null) p.fills = new LinkedHashMap<>();
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
            record(o.buy ? "BUY ORDER" : "SELL OFFER", o.name, num(m.group(4)), now);
            return true;
        }
        if ((m = FILLED.matcher(line)).matches()) {
            Order o = find(m.group(3), m.group(1).startsWith("Buy"), false);
            if (o == null) return false;
            if (o.at > 0 && now > o.at) {
                List<Long> l = fills.computeIfAbsent((o.buy ? "B:" : "S:") + key(o.name), k -> new ArrayList<>());
                l.add(now - o.at);
                while (l.size() > 30) l.remove(0);
            }
            o.filled = true;
            o.claimable = true;
            o.filledUnits = o.units;
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
            record("INSTA BUY", m.group(2), num(m.group(3)), now);
            return true;
        }
        if ((m = INSTA_SELL.matcher(line)).matches()) {
            double u = num(m.group(1));
            if (u <= 0) return false;
            sellUnits(m.group(2), u, num(m.group(3)), now);
            record("INSTA SELL", m.group(2), num(m.group(3)), now);
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
            Trade t = new Trade();
            t.name = p.name;
            t.at = now;
            t.units = tracked;
            t.cost = p.cost;
            t.price = coins / units;
            t.pnl = pnl;
            trades.add(t);
            while (trades.size() > 2000) trades.remove(0);
            p.units -= tracked;
            if (p.units < 0.5) positions.remove(key(name));
        }
        untrackedUnits += units - tracked;
    }

    // ---------- Manage Orders sync ----------

    /** Replaces the order list with what Hypixel's Manage Orders menu really shows. */
    public synchronized void syncFrom(List<Sync.Order> menu, long now) {
        List<Order> next = new ArrayList<>();
        for (Sync.Order s : menu) {
            Order keep = null;
            for (Order o : orders) {
                if (o.buy == s.buy() && key(o.name).equals(key(s.name())) && Math.abs(o.price - s.price()) < 0.05) { keep = o; break; }
            }
            Order o = new Order();
            o.name = s.name();
            o.buy = s.buy();
            o.units = s.amount();
            o.price = s.price();
            o.filledUnits = s.filled();
            o.claimable = s.claimable();
            o.filled = s.claimable() && s.filled() >= s.amount();
            o.at = keep != null ? keep.at : now;
            next.add(o);
        }
        orders = next;
        syncedAt = now;
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

    // ---------- limit, locked coins, fill times ----------

    private void record(String kind, String name, double value, long now) {
        Entry e = new Entry();
        e.at = now;
        e.kind = kind;
        e.name = name;
        e.value = value;
        ledger.add(e);
        long cut = now - 8 * Limit.DAY;
        ledger.removeIf(x -> x.at < cut);
    }

    /** Coins counted against the limit since a window started. */
    public synchronized double usedSince(long start) {
        double s = 0;
        for (Entry e : ledger) if (e.at >= start) s += e.value;
        return s;
    }

    public synchronized List<Entry> ledgerSince(long start) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ledger) if (e.at >= start) out.add(e);
        return out;
    }

    /** Coins sitting in buy orders that have not filled yet. */
    public synchronized double lockedInBuys() {
        double s = 0;
        for (Order o : orders) if (o.buy && !o.filled) s += Math.max(0, o.units - o.filledUnits) * o.price;
        return s;
    }

    /** {median ms, count} of your past fills for this item and side, or null. */
    public synchronized long[] fillTime(String name, boolean buy) {
        List<Long> l = fills.get((buy ? "B:" : "S:") + key(name));
        if (l == null || l.isEmpty()) return null;
        List<Long> s = new ArrayList<>(l);
        s.sort(null);
        return new long[]{s.get(s.size() / 2), s.size()};
    }

    public synchronized List<Trade> tradeList() { return new ArrayList<>(trades); }

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
