package com.omiros.habits;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Persists one month per small JSON file (files/months/YYYY-MM.json): that month's habits and the
 * days each one was done. Every month keeps its own copy of the habit list, so adding, renaming or
 * removing a habit changes only the current month while earlier months keep what they had.
 * A month without a file starts from the latest earlier month's habits with nothing ticked,
 * which is the monthly reset.
 *
 * Each file is read at most once and then kept in memory (a few hundred bytes a month), so drawing
 * the screen and working out streaks costs the same after years of use as in the first week.
 */
final class MonthStore {
    static final int MAX_HABITS = 6;
    static final int MAX_NAME = 40;

    private final File dir;
    private final SharedPreferences settings;
    /** Months that have a file, as read or last saved. The same object is handed out every time. */
    private final Map<YearMonth, Month> cache = new HashMap<>();
    /** Months that have a file, listed from disk once. */
    private TreeSet<YearMonth> stored;

    MonthStore(Context context) {
        dir = new File(context.getFilesDir(), "months");
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static final class Habit {
        /** Stable across months, so a habit's streak can run from one month into the next. */
        final int id;
        String name;
        /** Bit d-1 is set when the habit was done on day d. */
        int days;
        /** First day of the month the habit counts from: the day it was added, or 1. */
        int since = 1;

        Habit(int id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean isDone(int day) {
            return (days & (1 << (day - 1))) != 0;
        }

        void setDone(int day, boolean done) {
            if (done) {
                days |= 1 << (day - 1);
                // Ticking a day before the habit was added means it was already going then.
                since = Math.min(since, day);
            } else {
                days &= ~(1 << (day - 1));
            }
        }

        int count() {
            return Integer.bitCount(days);
        }
    }

    static final class Month {
        final YearMonth ym;
        final List<Habit> habits = new ArrayList<>();

        Month(YearMonth ym) {
            this.ym = ym;
        }

        Habit find(int id) {
            for (Habit h : habits) {
                if (h.id == id) return h;
            }
            return null;
        }

        int total() {
            int n = 0;
            for (Habit h : habits) n += h.count();
            return n;
        }

        /**
         * Share of the possible ticks over the first {@code elapsedDays} days, 0–100. A habit added
         * mid-month only counts the days since it was added.
         */
        int percent(int elapsedDays) {
            int possible = 0;
            int done = 0;
            int mask = elapsedDays >= 31 ? -1 : (1 << elapsedDays) - 1;
            for (Habit h : habits) {
                possible += Math.max(0, elapsedDays - h.since + 1);
                done += Integer.bitCount(h.days & mask);
            }
            if (possible == 0) return 0;
            return Math.min(100, Math.round(done * 100f / possible));
        }
    }

    /**
     * The month's habits and ticks. A month with a file is always the same object, so changing it
     * and calling {@link #save} keeps every holder in step. A month without one is rebuilt from the
     * latest earlier month each time (cheap, that month is in memory), so it can never go stale.
     */
    Month load(YearMonth ym) {
        Month m = own(ym);
        if (m != null) return m;
        m = new Month(ym);
        Month prev = latestBefore(ym);
        if (prev != null) {
            for (Habit h : prev.habits) m.habits.add(new Habit(h.id, h.name));
        }
        return m;
    }

    /** Writes the month even when it has no habits left, so it does not inherit them again. */
    void save(Month m) {
        cache.put(m.ym, m);
        try {
            JSONArray habits = new JSONArray();
            for (Habit h : m.habits) {
                JSONArray days = new JSONArray();
                for (int d = 1; d <= 31; d++) {
                    if (h.isDone(d)) days.put(d);
                }
                JSONObject o = new JSONObject().put("id", h.id).put("name", h.name).put("days", days);
                if (h.since > 1) o.put("since", h.since);
                habits.put(o);
            }
            if (write(file(m.ym), new JSONObject().put("habits", habits).toString())) stored().add(m.ym);
        } catch (JSONException ignored) {
        }
    }

    /** Earliest month with saved data, or null before anything has been saved. */
    YearMonth earliest() {
        return stored().isEmpty() ? null : stored().first();
    }

    /** True if the month has its own file (it was used), as opposed to only inheriting habits. */
    boolean hasData(YearMonth ym) {
        return stored().contains(ym);
    }

    int nextId() {
        int id = settings.getInt("next_id", 1);
        // Written straight away: losing it to a crash would hand the same id out twice.
        settings.edit().putInt("next_id", id + 1).commit();
        return id;
    }

    /**
     * Days in a row the habit was done, ending today, or ending yesterday while today is not ticked
     * yet. {@code current} is today's month as shown, which may not have a file yet.
     */
    int streak(int habitId, LocalDate today, Month current) {
        Habit h = current.find(habitId);
        if (h == null) return 0;
        YearMonth ym = current.ym;
        int day = today.getDayOfMonth();
        if (!h.isDone(day)) day--;
        int n = 0;
        // A whole month at a time: count the run of ticks ending at `day`, and only if it reaches
        // the 1st go on to the end of the month before.
        while (n < 3660) {
            if (day == 0) {
                ym = ym.minusMonths(1);
                h = load(ym).find(habitId);
                if (h == null) break;
                day = ym.lengthOfMonth();
            }
            int run = Math.min(day, Integer.numberOfLeadingZeros(~(h.days << (32 - day))));
            n += run;
            if (run < day) break;
            day = 0;
        }
        return n;
    }

    private Month latestBefore(YearMonth ym) {
        for (YearMonth s = stored().lower(ym); s != null; s = stored().lower(s)) {
            Month m = own(s);
            if (m != null) return m;
        }
        return null;
    }

    /** The month's own data from memory or its file, or null if it has none (or it cannot be read). */
    private Month own(YearMonth ym) {
        Month m = cache.get(ym);
        if (m == null && stored().contains(ym)) {
            m = read(ym);
            if (m != null) cache.put(ym, m);
        }
        return m;
    }

    private TreeSet<YearMonth> stored() {
        if (stored != null) return stored;
        stored = new TreeSet<>();
        String[] names = dir.list();
        if (names == null) return stored;
        for (String n : names) {
            // A ".bak" left by an interrupted write is restored by AtomicFile on the next read.
            if (n.length() < 7 || !(n.endsWith(".json") || n.endsWith(".json.bak"))) continue;
            try {
                stored.add(YearMonth.parse(n.substring(0, 7)));
            } catch (DateTimeParseException ignored) {
            }
        }
        return stored;
    }

    private AtomicFile file(YearMonth ym) {
        return new AtomicFile(new File(dir, ym + ".json"));
    }

    /** The month's own data, or null if it has no file (or it cannot be read). */
    private Month read(YearMonth ym) {
        try {
            String raw = new String(file(ym).readFully(), StandardCharsets.UTF_8);
            JSONArray habits = new JSONObject(raw).getJSONArray("habits");
            Month m = new Month(ym);
            for (int i = 0; i < habits.length(); i++) {
                JSONObject o = habits.getJSONObject(i);
                Habit h = new Habit(o.getInt("id"), o.getString("name"));
                h.since = Math.max(1, o.optInt("since", 1));
                JSONArray days = o.optJSONArray("days");
                for (int k = 0; days != null && k < days.length(); k++) {
                    int d = days.getInt(k);
                    if (d >= 1 && d <= ym.lengthOfMonth()) h.setDone(d, true);
                }
                m.habits.add(h);
            }
            return m;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private boolean write(AtomicFile f, String json) {
        dir.mkdirs();
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
            return true;
        } catch (IOException e) {
            if (out != null) f.failWrite(out);
            return false;
        }
    }
}
