package com.hayden.sbterminal;

import java.util.Locale;

/** Terminal number formats. */
public final class Fmt {
    private Fmt() {}

    /** Prices: 1,234.5 / 123.4k / 12.35M / 1.23B */
    public static String px(double v) {
        if (!Double.isFinite(v) || v <= 0) return "-";
        if (v >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (v >= 1e5) return String.format(Locale.US, "%.1fk", v / 1e3);
        if (v >= 100) return String.format(Locale.US, "%,.1f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    /** Big counts: 950 / 12.3k / 4.56M */
    public static String qty(double v) {
        if (!Double.isFinite(v)) return "-";
        double a = Math.abs(v);
        if (a >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (a >= 1e6) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (a >= 1e4) return String.format(Locale.US, "%.1fk", v / 1e3);
        return String.format(Locale.US, "%,.0f", v);
    }

    /** +1.23% / -0.40% / n/a */
    public static String pct(double v) {
        if (!Double.isFinite(v)) return "n/a";
        return String.format(Locale.US, "%+.2f%%", v);
    }

    public static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }
}
