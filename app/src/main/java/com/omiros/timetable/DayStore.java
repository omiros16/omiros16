package com.omiros.timetable;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persists one plan per date as its own small JSON file (files/days/YYYY-MM-DD.json), so opening
 * or editing a day costs the same whether there is one week or several years of history.
 * Small settings (notifications, recent entries) live in a tiny SharedPreferences file.
 */
final class DayStore {
    static final int START_MIN = 5 * 60;   // 05:00
    static final int END_MIN = 24 * 60;    // 24:00
    static final int SLOT_MIN = 15;
    static final int SLOTS = (END_MIN - START_MIN) / SLOT_MIN;

    static final int NONE = 0;
    static final int DONE = 1;
    static final int MISSED = 2;

    private static final int MAX_RECENT = 20;

    private final File dir;
    private final SharedPreferences settings;

    DayStore(Context context) {
        dir = new File(context.getFilesDir(), "days");
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean("migrated_v2", false)) migrateFromPrefs(context);
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

    private AtomicFile file(LocalDate date) {
        return new AtomicFile(new File(dir, date + ".json"));
    }

    boolean hasPlan(LocalDate date) {
        // A ".bak" left by an interrupted write is restored by AtomicFile on the next read.
        return new File(dir, date + ".json").exists() || new File(dir, date + ".json.bak").exists();
    }

    Day load(LocalDate date) {
        Day day = new Day();
        try {
            String raw = new String(file(date).readFully(), StandardCharsets.UTF_8);
            JSONArray slots = new JSONObject(raw).getJSONArray("slots");
            for (int n = 0; n < slots.length(); n++) {
                JSONObject s = slots.getJSONObject(n);
                int i = s.getInt("i");
                if (i < 0 || i >= SLOTS) continue;
                day.text[i] = s.getString("t");
                day.status[i] = s.optInt("s", NONE);
            }
        } catch (FileNotFoundException e) {
            // No plan for this day.
        } catch (IOException | JSONException ignored) {
            // Unreadable entry: start the day fresh rather than crash.
        }
        return day;
    }

    void save(LocalDate date, Day day) {
        AtomicFile f = file(date);
        if (day.isEmpty()) {
            f.delete();
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
            write(f, new JSONObject().put("slots", slots).toString());
        } catch (JSONException ignored) {
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

    /** Version 1 kept every day inside one SharedPreferences XML; move each day to its own file. */
    private void migrateFromPrefs(Context context) {
        SharedPreferences legacy = context.getSharedPreferences("days", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = settings.edit();
        for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals("_recent") && value instanceof String) {
                e.putString("recent", (String) value);
            } else if (key.equals("_notif_default") && value instanceof Boolean) {
                e.putBoolean("notifications", (Boolean) value);
            } else if (value instanceof String) {
                try {
                    write(file(LocalDate.parse(key)), (String) value);
                } catch (DateTimeParseException ignored) {
                }
            }
        }
        e.putBoolean("migrated_v2", true).commit();
        legacy.edit().clear().commit();
        context.deleteSharedPreferences("days");
    }

    List<String> recent() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(settings.getString("recent", "[]"));
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
        settings.edit().putString("recent", new JSONArray(list).toString()).apply();
    }

    void removeRecent(String text) {
        List<String> list = recent();
        list.remove(text);
        settings.edit().putString("recent", new JSONArray(list).toString()).apply();
    }

    /** One switch for all days. */
    boolean notificationsOn() {
        return settings.getBoolean("notifications", true);
    }

    void setNotificationsOn(boolean on) {
        settings.edit().putBoolean("notifications", on).apply();
    }

    boolean askedNotificationPermission() {
        return settings.getBoolean("asked_notif_permission", false);
    }

    void setAskedNotificationPermission() {
        settings.edit().putBoolean("asked_notif_permission", true).apply();
    }

    static int slotStart(int i) {
        return START_MIN + i * SLOT_MIN;
    }

    static String formatMin(int m) {
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }
}
