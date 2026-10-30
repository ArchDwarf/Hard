package dev.hardstone.tracker;

import java.util.Locale;

final class Fmt {
    private Fmt() { }

    static String num(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    static String compact(double v) {
        if (v >= 1_000_000_000) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (v >= 10_000) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        return num(Math.round(v));
    }

    static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
