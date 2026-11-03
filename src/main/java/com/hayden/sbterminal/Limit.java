package com.hayden.sbterminal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The Bazaar daily limit window. Nothing about the amount or the reset time is baked in:
 * the amount comes from LIMIT or from Hypixel's "reached the daily limit" message, and the reset
 * is a time of day you can set (LIMIT RESET 19:00) that the mod also corrects when it sees an
 * order go through after a block. Pure logic.
 */
public final class Limit {
    public static final long DAY = 86_400_000L;
    /** Shown (never enforced) until a real limit is set or learned. */
    public static final double ASSUMED = 15_000_000_000.0;

    private Limit() {}

    /** Start of the current limit window: the most recent reset at or before now. */
    public static long windowStart(long now, int resetMinuteUtc) {
        long midnight = now - Math.floorMod(now, DAY);
        long b = midnight + resetMinuteUtc * 60_000L;
        return b <= now ? b : b - DAY;
    }

    public static long nextReset(long now, int resetMinuteUtc) { return windowStart(now, resetMinuteUtc) + DAY; }

    public static int minuteOfDayUtc(long t) { return (int) (Math.floorMod(t, DAY) / 60_000L); }

    /** "19:00" (your local time) or "UTC 23:00" -> minute of day in UTC, or -1. */
    public static int parseClock(String s, ZoneId zone, long now) {
        if (s == null) return -1;
        String t = s.trim().toUpperCase(Locale.US);
        boolean utc = false;
        if (t.startsWith("UTC")) { utc = true; t = t.substring(3).trim(); }
        else if (t.endsWith("UTC")) { utc = true; t = t.substring(0, t.length() - 3).trim(); }
        boolean pm = false, am = false;
        if (t.endsWith("PM")) { pm = true; t = t.substring(0, t.length() - 2).trim(); }
        else if (t.endsWith("AM")) { am = true; t = t.substring(0, t.length() - 2).trim(); }
        String[] p = t.split(":");
        try {
            int h = Integer.parseInt(p[0].trim()), m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (pm && h < 12) h += 12;
            if (am && h == 12) h = 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            int minutes = h * 60 + m;
            if (utc) return minutes;
            int offset = zone.getRules().getOffset(Instant.ofEpochMilli(now)).getTotalSeconds() / 60;
            return Math.floorMod(minutes - offset, 1440);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Your local clock time for a UTC minute of day, e.g. "7:00 PM". */
    public static String clock(int minuteUtc, ZoneId zone, long now) {
        long t = now - Math.floorMod(now, DAY) + minuteUtc * 60_000L;
        return DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.ofEpochMilli(t).atZone(zone));
    }

    public static String countdown(long ms) {
        long m = Math.max(0, ms) / 60_000L;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }
}
