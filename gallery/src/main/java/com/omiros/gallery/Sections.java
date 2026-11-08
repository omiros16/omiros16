package com.omiros.gallery;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splits a newest-first list into grid rows, with a header per day. Plain Java so it is tested on the JVM. */
final class Sections {
    static final int HEADER = 0;
    static final int CELLS = 1;

    static final Locale GREEK = new Locale("el", "GR");
    private static final DateTimeFormatter THIS_YEAR = DateTimeFormatter.ofPattern("EEE d MMMM", GREEK);
    private static final DateTimeFormatter OTHER_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", GREEK);

    private Sections() {
    }

    /** Rows as {type, start, end} over item indexes; a HEADER row spans its whole day. */
    static List<int[]> rows(long[] dates, int cols, boolean byDay, ZoneId zone) {
        List<int[]> out = new ArrayList<>();
        int n = dates.length;
        int i = 0;
        while (i < n) {
            int end = n;
            if (byDay) {
                LocalDate d = day(dates[i], zone);
                end = i + 1;
                while (end < n && day(dates[end], zone).equals(d)) end++;
                out.add(new int[]{HEADER, i, end});
            }
            for (int s = i; s < end; s += cols) out.add(new int[]{CELLS, s, Math.min(s + cols, end)});
            i = end;
        }
        return out;
    }

    /** The row that shows item {@code index}. */
    static int rowOf(List<int[]> rows, int index) {
        for (int r = 0; r < rows.size(); r++) {
            int[] row = rows.get(r);
            if (row[0] == CELLS && index >= row[1] && index < row[2]) return r;
        }
        return 0;
    }

    static LocalDate day(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    static String label(long millis, LocalDate today, ZoneId zone) {
        LocalDate d = day(millis, zone);
        if (d.equals(today)) return "Σήμερα";
        if (d.equals(today.minusDays(1))) return "Χθες";
        return (d.getYear() == today.getYear() ? THIS_YEAR : OTHER_YEAR).format(d);
    }
}
