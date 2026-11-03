package com.hayden.sbterminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Live Bazaar data from Hypixel's public API (no key). One call returns every product.
 *
 * Naming trap in the API: "sell_summary" holds the BUY ORDERS (bids, what insta-sellers hit)
 * and "buy_summary" holds the SELL OFFERS (asks, what insta-buyers hit). Here everything is
 * named bid / ask to avoid that confusion.
 */
public final class Market {
    public static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    public static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    public static final int DEPTH = 30;

    /**
     * One product right now.
     * @param bidLevels {price, units, orders, price, units, orders, ...} best (highest) first
     * @param askLevels same for sell offers, best (lowest) first
     * @param instaBuyWeek  units insta-bought over 7 days (these fill sell offers)
     * @param instaSellWeek units insta-sold over 7 days (these fill buy orders)
     */
    public record Product(String id, double bid, double ask, double bidUnits, double askUnits, int bidOrders, int askOrders,
                          double instaBuyWeek, double instaSellWeek, double[] bidLevels, double[] askLevels) {
        public double mid() {
            return bid > 0 && ask > 0 ? (bid + ask) / 2 : Math.max(bid, ask);
        }

        public double spread() {
            return bid > 0 && ask > 0 ? ask - bid : 0;
        }

        public double spreadPct() {
            double m = mid();
            return m > 0 && bid > 0 && ask > 0 ? (ask - bid) / m * 100 : 0;
        }

        /** Weekly volume on the thinner side: how much really trades both ways. */
        public double weekVolume() {
            return Math.min(instaBuyWeek, instaSellWeek);
        }

        /** Coins traded per day on the thinner side. */
        public double coinFlowDay() {
            return weekVolume() / 7.0 * mid();
        }
    }

    public record Snapshot(Map<String, Product> products, long hypixelUpdated, long fetchedAt) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), 0, 0);

        public Product get(String id) {
            return id == null ? null : products.get(id);
        }
    }

    private Market() {}

    public static Snapshot parse(String json, long now) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) throw new IllegalStateException("Bazaar API success=false");
        long updated = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : now;
        Map<String, Product> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("products").entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            JsonObject qs = p.has("quick_status") ? p.getAsJsonObject("quick_status") : new JsonObject();
            double[] bids = levels(p.getAsJsonArray("sell_summary"));
            double[] asks = levels(p.getAsJsonArray("buy_summary"));
            out.put(e.getKey(), new Product(e.getKey(),
                    bids.length > 0 ? bids[0] : 0, asks.length > 0 ? asks[0] : 0,
                    num(qs, "sellVolume"), num(qs, "buyVolume"), (int) num(qs, "sellOrders"), (int) num(qs, "buyOrders"),
                    num(qs, "buyMovingWeek"), num(qs, "sellMovingWeek"), bids, asks));
        }
        return new Snapshot(Collections.unmodifiableMap(out), updated, now);
    }

    static double[] levels(JsonArray arr) {
        if (arr == null) return new double[0];
        int n = Math.min(DEPTH, arr.size());
        double[] out = new double[n * 3];
        for (int i = 0; i < n; i++) {
            JsonObject o = arr.get(i).getAsJsonObject();
            out[3 * i] = num(o, "pricePerUnit");
            out[3 * i + 1] = num(o, "amount");
            out[3 * i + 2] = num(o, "orders");
        }
        return out;
    }

    /** id -> display name, from the items resource. */
    public static Map<String, String> parseNames(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, String> out = new HashMap<>();
        JsonArray items = root.getAsJsonArray("items");
        if (items == null) return out;
        for (JsonElement el : items) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("id") && o.has("name")) out.put(o.get("id").getAsString(), strip(o.get("name").getAsString()));
        }
        return out;
    }

    /** id -> coins an NPC pays for one, from the items resource (only items that have a price). */
    public static Map<String, Double> parseNpc(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Double> out = new HashMap<>();
        JsonArray items = root.getAsJsonArray("items");
        if (items == null) return out;
        for (JsonElement el : items) {
            JsonObject o = el.getAsJsonObject();
            double v = num(o, "npc_sell_price");
            if (o.has("id") && v > 0) out.put(o.get("id").getAsString(), v);
        }
        return out;
    }

    static double num(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
    }

    public static String strip(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    /** ENCHANTED_COAL -> Enchanted Coal, when the item list has no name. */
    public static String prettyId(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.toLowerCase(Locale.US).split("[_:]")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
