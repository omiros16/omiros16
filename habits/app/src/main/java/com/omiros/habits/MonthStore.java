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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Persists one month per small JSON file (files/months/YYYY-MM.json): that month's habits and the
 * days each one was done. Every month keeps its own copy of the habit list, so adding, renaming or
 * removing a habit changes only the current month while earlier months keep what they had.
 * A month without a file starts from the latest earlier month's habits with nothing ticked,
 * which is the monthly reset.
 */
final class MonthStore {
    static final int MAX_HABITS = 6;
    static final int MAX_NAME = 40;

    private final File dir;
    private final SharedPreferences settings;

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

        Habit(int id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean isDone(int day) {
            return (days & (1 << (day - 1))) != 0;
        }

        void setDone(int day, boolean done) {
            if (done) days |= 1 << (day - 1);
            else days &= ~(1 << (day - 1));
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

        /** Share of the possible ticks over the first {@code elapsedDays} days, 0–100. */
        int percent(int elapsedDays) {
            int possible = habits.size() * elapsedDays;
            if (possible == 0) return 0;
            int done = 0;
            int mask = elapsedDays >= 31 ? -1 : (1 << elapsedDays) - 1;
            for (Habit h : habits) done += Integer.bitCount(h.days & mask);
            return Math.round(done * 100f / possible);
        }
    }

    Month load(YearMonth ym) {
        Month m = read(ym);
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
        try {
            JSONArray habits = new JSONArray();
            for (Habit h : m.habits) {
                JSONArray days = new JSONArray();
                for (int d = 1; d <= 31; d++) {
                    if (h.isDone(d)) days.put(d);
                }
                habits.put(new JSONObject().put("id", h.id).put("name", h.name).put("days", days));
            }
            write(file(m.ym), new JSONObject().put("habits", habits).toString());
        } catch (JSONException ignored) {
        }
    }

    /** Earliest month with saved data, or null before anything has been saved. */
    YearMonth earliest() {
        List<YearMonth> all = stored();
        return all.isEmpty() ? null : all.get(0);
    }

    /** True if the month has its own file (it was used), as opposed to only inheriting habits. */
    boolean hasData(YearMonth ym) {
        return stored().contains(ym);
    }

    int nextId() {
        int id = settings.getInt("next_id", 1);
        settings.edit().putInt("next_id", id + 1).apply();
        return id;
    }

    /**
     * Days in a row the habit was done, ending today, or ending yesterday while today is not ticked
     * yet. {@code cache} holds the months already read (seed it with the shown month).
     */
    int streak(int habitId, LocalDate today, Map<YearMonth, Month> cache) {
        LocalDate d = isDone(habitId, today, cache) ? today : today.minusDays(1);
        int n = 0;
        while (n < 3660 && isDone(habitId, d, cache)) {
            n++;
            d = d.minusDays(1);
        }
        return n;
    }

    private boolean isDone(int habitId, LocalDate date, Map<YearMonth, Month> cache) {
        YearMonth ym = YearMonth.from(date);
        Month m = cache.get(ym);
        if (m == null) {
            m = load(ym);
            cache.put(ym, m);
        }
        Habit h = m.find(habitId);
        return h != null && h.isDone(date.getDayOfMonth());
    }

    private Month latestBefore(YearMonth ym) {
        List<YearMonth> all = stored();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).isBefore(ym)) {
                Month m = read(all.get(i));
                if (m != null) return m;
            }
        }
        return null;
    }

    private List<YearMonth> stored() {
        List<YearMonth> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) return out;
        for (String n : names) {
            // A ".bak" left by an interrupted write is restored by AtomicFile on the next read.
            if (n.length() < 7 || !(n.endsWith(".json") || n.endsWith(".json.bak"))) continue;
            try {
                YearMonth ym = YearMonth.parse(n.substring(0, 7));
                if (!out.contains(ym)) out.add(ym);
            } catch (DateTimeParseException ignored) {
            }
        }
        Collections.sort(out);
        return out;
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

    private void write(AtomicFile f, String json) {
        dir.mkdirs();
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (IOException e) {
            if (out != null) f.failWrite(out);
        }
    }
}
