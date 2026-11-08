package com.omiros.gallery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Run with java -ea (build.sh does it before building the APK). */
public class SectionsTest {
    private static final ZoneId Z = ZoneId.of("Europe/Athens");

    private static long at(int y, int m, int d, int h) {
        return LocalDateTime.of(y, m, d, h, 0).atZone(Z).toInstant().toEpochMilli();
    }

    private static void row(int[] r, int type, int start, int end) {
        if (r[0] != type || r[1] != start || r[2] != end) {
            throw new AssertionError("expected {" + type + "," + start + "," + end + "} got {" + r[0] + "," + r[1] + "," + r[2] + "}");
        }
    }

    private static void eq(Object want, Object got) {
        if (!want.equals(got)) throw new AssertionError("expected " + want + " got " + got);
    }

    public static void main(String[] args) {
        // newest first: three photos on 8 Nov (one just after midnight), two on 7 Nov
        long[] dates = {at(2026, 11, 8, 20), at(2026, 11, 8, 9), at(2026, 11, 8, 0), at(2026, 11, 7, 23), at(2026, 11, 7, 1)};

        List<int[]> byDay = Sections.rows(dates, 2, true, Z);
        eq(5, byDay.size());
        row(byDay.get(0), Sections.HEADER, 0, 3);
        row(byDay.get(1), Sections.CELLS, 0, 2);
        row(byDay.get(2), Sections.CELLS, 2, 3);
        row(byDay.get(3), Sections.HEADER, 3, 5);
        row(byDay.get(4), Sections.CELLS, 3, 5);
        eq(2, Sections.rowOf(byDay, 2));
        eq(4, Sections.rowOf(byDay, 4));

        List<int[]> flat = Sections.rows(dates, 3, false, Z);
        eq(2, flat.size());
        row(flat.get(0), Sections.CELLS, 0, 3);
        row(flat.get(1), Sections.CELLS, 3, 5);

        eq(0, Sections.rows(new long[0], 4, true, Z).size());

        LocalDate today = LocalDate.of(2026, 11, 8);
        eq("Σήμερα", Sections.label(dates[2], today, Z));
        eq("Χθες", Sections.label(dates[3], today, Z));
        if (!Sections.label(at(2024, 3, 1, 12), today, Z).endsWith("2024")) throw new AssertionError("older years show the year");
        if (Sections.label(at(2026, 3, 1, 12), today, Z).contains("2026")) throw new AssertionError("this year hides the year");

        System.out.println("SectionsTest ok");
    }
}
