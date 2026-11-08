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

    static String mmss(long sec) {
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    static String hms(long sec) {
        if (sec >= 3600) return String.format(Locale.ROOT, "%dч %02dм", sec / 3600, (sec / 60) % 60);
        return mmss(sec);
    }

    /** 45 → "45 с", 90 → "1 мин 30 с", 300 → "5 мин" */
    static String dur(long sec) {
        if (sec < 60) return sec + " с";
        long m = sec / 60, s = sec % 60;
        return s == 0 ? m + " мин" : m + " мин " + s + " с";
    }

    static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
