package com.hayden.sbterminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Price history points. Sources: Coflnet's free Bazaar history (about 5-minute points for a day,
 * 2-hour bars for a week) and the terminal's own recording of every live update.
 *
 * Coflnet "buy" is the insta-buy price (= best ask) and "sell" the insta-sell price (= best bid).
 */
public final class Bars {
    public record Point(long time, double bid, double ask, double bidLow, double bidHigh, double askLow, double askHigh) {
        public Point(long time, double bid, double ask) {
            this(time, bid, ask, bid, bid, ask, ask);
        }

        public double mid() {
            return bid > 0 && ask > 0 ? (bid + ask) / 2 : Math.max(bid, ask);
        }
    }

    private Bars() {}

    public static final String COFLNET = "https://sky.coflnet.com/api/bazaar/%s/history/%s";

    /** Parses Coflnet's history array; returns points oldest first. */
    public static List<Point> parseCoflnet(String json) {
        List<Point> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return out;
        JsonArray arr = root.getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            long t = time(o.has("timestamp") ? o.get("timestamp").getAsString() : null);
            if (t <= 0) continue;
            double ask = Market.num(o, "buy"), bid = Market.num(o, "sell");
            out.add(new Point(t, bid, ask,
                    orElse(Market.num(o, "minSell"), bid), orElse(Market.num(o, "maxSell"), bid),
                    orElse(Market.num(o, "minBuy"), ask), orElse(Market.num(o, "maxBuy"), ask)));
        }
        out.sort(Comparator.comparingLong(Point::time));
        return out;
    }

    private static double orElse(double v, double d) {
        return v > 0 ? v : d;
    }

    /** "2026-11-02T20:00:00" (UTC, no zone), "...Z", or with fractions. 0 if unreadable. */
    static long time(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * Joins older history with newer recorded points: history up to the first recorded point,
     * then the recording. Both oldest first. Returns a new list.
     */
    public static List<Point> merge(List<Point> history, List<Point> recorded) {
        List<Point> out = new ArrayList<>();
        long cut = recorded.isEmpty() ? Long.MAX_VALUE : recorded.get(0).time();
        for (Point p : history) if (p.time() < cut) out.add(p);
        out.addAll(recorded);
        return out;
    }

    /** % change of the mid from the first point at or after now - window, to nowMid. NaN if no data that old. */
    public static double change(List<Point> pts, double nowMid, long now, long window) {
        if (pts == null || pts.isEmpty() || nowMid <= 0) return Double.NaN;
        long from = now - window;
        if (pts.get(0).time() > from + window / 5) return Double.NaN;
        for (Point p : pts) if (p.time() >= from && p.mid() > 0) return (nowMid / p.mid() - 1) * 100;
        return Double.NaN;
    }

    /** Points inside [from, to]. */
    public static List<Point> window(List<Point> pts, long from, long to) {
        List<Point> out = new ArrayList<>();
        for (Point p : pts) if (p.time() >= from && p.time() <= to) out.add(p);
        return out;
    }

    /** {low, high} across bid lows and ask highs in the points, or null. */
    public static double[] range(List<Point> pts) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Point p : pts) {
            if (p.bidLow() > 0) lo = Math.min(lo, p.bidLow());
            if (p.bid() > 0) lo = Math.min(lo, p.bid());
            if (p.askHigh() > 0) hi = Math.max(hi, p.askHigh());
            if (p.ask() > 0) hi = Math.max(hi, p.ask());
        }
        if (lo > hi) return null;
        return new double[]{lo, hi};
    }
}
