package com.hayden.sbterminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SkyBlock time and the recurring events. A SkyBlock day is 20 real minutes, a month 31 days, a year 12 months (124 real hours).
 * Constants and yearly start days follow community calendar sources; durations are 3 SkyBlock days (about 1 real hour) for the yearly events.
 * Pure logic.
 */
public final class SbCalendar {
    public static final long EPOCH_MS = 1_560_275_700_000L, DAY_MS = 1_200_000L, MONTH_MS = 31 * DAY_MS, YEAR_MS = 12 * MONTH_MS;
    public static final String[] MONTHS = {"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
            "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"};

    private SbCalendar() {}

    public record SbTime(int year, int month, int day, int hour, int minute) {
        public String monthName() { return MONTHS[month]; }

        @Override
        public String toString() {
            return String.format("Year %d, %s %d, %d:%02d", year, monthName(), day, hour, minute);
        }
    }

    public static SbTime at(long ms) {
        long e = Math.max(0, ms - EPOCH_MS);
        long year = e / YEAR_MS, inYear = e % YEAR_MS;
        int month = (int) (inYear / MONTH_MS);
        long inMonth = inYear % MONTH_MS;
        int day = (int) (inMonth / DAY_MS) + 1;
        long inDay = inMonth % DAY_MS;
        int hour = (int) (inDay * 24 / DAY_MS), minute = (int) (inDay * 24 * 60 / DAY_MS % 60);
        return new SbTime((int) year + 1, month, day, hour, minute);
    }

    /** A recurring event: starts at {@code startDay} (0-based day of the year) every year, or every hour at a real minute. */
    public record Event(String key, String name, boolean hourly, long offsetMs, long lengthMs, String note) {}

    public static final List<Event> EVENTS = List.of(
            new Event("SPOOKY", "Spooky Festival", false, (7 * 31 + 28) * DAY_MS, 3 * DAY_MS, "Autumn 29: candy, pumpkins, Spooky items"),
            new Event("JERRY", "Season of Jerry", false, (11 * 31 + 23) * DAY_MS, 3 * DAY_MS, "Late Winter 24: gifts, snow, Jerry's Workshop"),
            new Event("ZOO_S", "Traveling Zoo (summer)", false, (3 * 31) * DAY_MS, 3 * DAY_MS, "Early Summer 1: pets and pet items"),
            new Event("ZOO_W", "Traveling Zoo (winter)", false, (9 * 31) * DAY_MS, 3 * DAY_MS, "Early Winter 1: pets and pet items"),
            new Event("NEWYEAR", "New Year Celebration", false, (11 * 31 + 28) * DAY_MS, 3 * DAY_MS, "Late Winter 29: New Year cakes"),
            new Event("MAYOR", "New mayor takes office", false, (2 * 31 + 26) * DAY_MS, DAY_MS, "Late Spring 27: election ends, perks switch"),
            new Event("DARK", "Dark Auction", true, 55 * 60_000L, 5 * 60_000L, "every hour at :55"),
            new Event("JACOB", "Jacob's Contest", true, 15 * 60_000L, 20 * 60_000L, "every hour at :15, 20 minutes"));

    /** Milliseconds until the event next starts (0 if it is running now). */
    public static long untilStart(Event e, long now) {
        if (e.hourly()) {
            long hour = 3_600_000L, inHour = Math.floorMod(now, hour), s = e.offsetMs();
            if (inHour >= s && inHour < s + e.lengthMs()) return 0;
            return Math.floorMod(s - inHour, hour);
        }
        long since = now - EPOCH_MS, inYear = Math.floorMod(since, YEAR_MS), s = e.offsetMs();
        if (inYear >= s && inYear < s + e.lengthMs()) return 0;
        return inYear < s ? s - inYear : YEAR_MS - inYear + s;
    }

    public static boolean active(Event e, long now) {
        return untilStart(e, now) == 0;
    }

    public record Upcoming(Event event, long inMs, boolean active) {}

    /** All events by how soon they start (running ones first). */
    public static List<Upcoming> upcoming(long now) {
        List<Upcoming> out = new ArrayList<>();
        for (Event e : EVENTS) { long u = untilStart(e, now); out.add(new Upcoming(e, u, u == 0)); }
        out.sort(Comparator.comparingLong(Upcoming::inMs));
        return out;
    }

    /** 0..1 closeness of a yearly event: 1 while it runs, falling to 0 at 48 real hours before the start. For the learning model. */
    public static double proximity(String key, long now) {
        for (Event e : EVENTS) {
            if (!e.key().equals(key)) continue;
            long u = untilStart(e, now);
            return u == 0 ? 1.0 : Math.max(0, 1.0 - u / (48 * 3_600_000.0));
        }
        return 0;
    }
}
