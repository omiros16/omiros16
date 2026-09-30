package com.omiros.timetable;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Persists one plan per date in SharedPreferences, as JSON keyed by ISO date. */
final class DayStore {
    static final int START_MIN = 5 * 60;   // 05:00
    static final int END_MIN = 24 * 60;    // 24:00
    static final int SLOT_MIN = 15;
    static final int SLOTS = (END_MIN - START_MIN) / SLOT_MIN;

    static final int NONE = 0;
    static final int DONE = 1;
    static final int MISSED = 2;

    private static final int MAX_RECENT = 20;

    private final SharedPreferences prefs;

    DayStore(Context context) {
        prefs = context.getSharedPreferences("days", Context.MODE_PRIVATE);
    }

    static final class Day {
        final String[] text = new String[SLOTS];
        final int[] status = new int[SLOTS];

        boolean isEmpty() {
            for (String t : text) {
                if (t != null) return false;
            }
            return true;
        }
    }

    Day load(LocalDate date) {
        Day day = new Day();
        String raw = prefs.getString(date.toString(), null);
        if (raw == null) return day;
        try {
            JSONArray slots = new JSONObject(raw).getJSONArray("slots");
            for (int n = 0; n < slots.length(); n++) {
                JSONObject s = slots.getJSONObject(n);
                int i = s.getInt("i");
                if (i < 0 || i >= SLOTS) continue;
                day.text[i] = s.getString("t");
                day.status[i] = s.optInt("s", NONE);
            }
        } catch (JSONException ignored) {
            // Corrupt entry: start the day fresh rather than crash.
        }
        return day;
    }

    void save(LocalDate date, Day day) {
        if (day.isEmpty()) {
            prefs.edit().remove(date.toString()).apply();
            return;
        }
        try {
            JSONArray slots = new JSONArray();
            for (int i = 0; i < SLOTS; i++) {
                if (day.text[i] == null) continue;
                JSONObject s = new JSONObject();
                s.put("i", i);
                s.put("t", day.text[i]);
                if (day.status[i] != NONE) s.put("s", day.status[i]);
                slots.put(s);
            }
            prefs.edit().putString(date.toString(), new JSONObject().put("slots", slots).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    List<String> recent() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("_recent", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    void addRecent(String text) {
        List<String> list = recent();
        list.remove(text);
        list.add(0, text);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        prefs.edit().putString("_recent", new JSONArray(list).toString()).apply();
    }

    void removeRecent(String text) {
        List<String> list = recent();
        list.remove(text);
        prefs.edit().putString("_recent", new JSONArray(list).toString()).apply();
    }
}
