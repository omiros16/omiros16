package com.omiros.gymnotes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Text formatting for weights, dates, sets and PRs. */
final class Fmt {
    static final Locale GREEK = new Locale("el", "GR");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE d/M", GREEK);
    private static final DateTimeFormatter WITH_YEAR = DateTimeFormatter.ofPattern("d/M/yy", GREEK);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEEE d MMMM", GREEK);

    private Fmt() {
    }

    static long today() {
        return LocalDate.now().toEpochDay();
    }

    /** 50 -> "50", 52.5 -> "52.5", 1.25 -> "1.25". */
    static String kg(double w) {
        long c = Math.max(0, Strength.centi(w));
        long whole = c / 100;
        long frac = c % 100;
        if (frac == 0) return Long.toString(whole);
        if (frac % 10 == 0) return whole + "." + frac / 10;
        return whole + "." + (frac < 10 ? "0" : "") + frac;
    }

    /** A weight with its unit; 0 kg is bodyweight. */
    static String load(double w) {
        return Strength.centi(w) == 0 ? "BW" : kg(w) + " kg";
    }

    /** One decimal, without a trailing ".0". */
    static String num1(double v) {
        long t = Math.round(v * 10);
        return t % 10 == 0 ? Long.toString(t / 10) : (t / 10) + "." + Math.abs(t % 10);
    }

    static String signed1(double v) {
        return (v >= 0 ? "+" : "−") + num1(Math.abs(v));
    }

    static String day(long day, long today) {
        if (day == today) return "Σήμερα";
        if (day == today - 1) return "Χθες";
        LocalDate d = LocalDate.ofEpochDay(day);
        if (d.getYear() == LocalDate.ofEpochDay(today).getYear()) return capitalize(d.format(SHORT));
        return d.format(WITH_YEAR);
    }

    /** "Σήμερα · Τετ 1/10" style label for the date being logged. */
    static String dayWithDate(long day, long today) {
        String shortDate = capitalize(LocalDate.ofEpochDay(day).format(SHORT));
        if (day == today) return "Σήμερα · " + shortDate;
        if (day == today - 1) return "Χθες · " + shortDate;
        return day(day, today);
    }

    static String dayLong(long day) {
        return capitalize(LocalDate.ofEpochDay(day).format(LONG));
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(GREEK) + s.substring(1);
    }

    static String setCount(int n) {
        return n == 1 ? "1 set" : n + " sets";
    }

    /** "50 kg × 10, 10, 9" or, when the weight changes, "50 kg × 10 · 55 kg × 8, 7". */
    static String sets(Session s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.n) {
            long c = Strength.centi(s.weight[i]);
            if (sb.length() > 0) sb.append(" · ");
            sb.append(load(s.weight[i])).append(" × ");
            int j = i;
            while (j < s.n && Strength.centi(s.weight[j]) == c) {
                if (j > i) sb.append(", ");
                sb.append(s.reps[j]);
                j++;
            }
            i = j;
        }
        return sb.toString();
    }

    /** Short badge text, e.g. "PR βάρους · PR 1RM". */
    static String badges(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & Strength.WEIGHT) != 0) sb.append("PR βάρους");
        if ((flags & Strength.E1RM) != 0) sb.append(sb.length() > 0 ? " · " : "").append("PR 1RM");
        if ((flags & Strength.REPS) != 0) sb.append(sb.length() > 0 ? " · " : "").append("PR reps");
        return sb.toString();
    }

    /** What exactly was beaten, for the "new PR" banner. {@code prev} are the bests before this set. */
    static String describe(int flags, double w, int r, Strength.Bests prev) {
        StringBuilder sb = new StringBuilder();
        if ((flags & Strength.WEIGHT) != 0) {
            sb.append("• Βαρύτερο σετ: ").append(kg(w)).append(" kg × ").append(r)
                    .append("  (μέχρι τώρα ").append(kg(prev.maxWeight)).append(" kg)");
        }
        if ((flags & Strength.E1RM) != 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• Εκτιμώμενο 1RM: ").append(num1(Strength.e1rm(w, r))).append(" kg  (μέχρι τώρα ")
                    .append(num1(prev.bestE1rm)).append(" kg)");
        }
        if ((flags & Strength.REPS) != 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(r).append(" reps με ").append(load(w)).append("  (μέχρι τώρα max ")
                    .append(prev.repsAtOrAbove(w)).append(")");
        }
        return sb.toString();
    }

    /** Parses a weight typed with either "." or "," as the decimal mark; blank or invalid is 0. */
    static double parseKg(String s) {
        s = s.trim().replace(',', '.');
        if (s.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || v < 0) return 0;
            return Math.min(v, 2000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses reps; blank or invalid is 0. */
    static int parseReps(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            return Math.max(0, Math.min(Integer.parseInt(s), 1000));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
