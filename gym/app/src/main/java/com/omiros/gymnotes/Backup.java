package com.omiros.gymnotes;

import android.database.Cursor;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export / import of all data as one readable JSON file:
 * <pre>
 * {"app": "gymnotes", "format": 1, "exported": "2026-10-01",
 *  "exercises": [{"name": "Lat pulldown", "log": [{"date": "2026-09-26", "sets": [[50, 10], [50, 9]]}]}],
 *  "presets": [{"name": "Δευτέρα", "exercises": ["Lat pulldown"]}]}
 * </pre>
 * Both directions stream, so memory stays small however long the history is.
 */
final class Backup {
    static final String APP = "gymnotes";
    static final int FORMAT = 1;

    static final class Data {
        final List<Ex> exercises = new ArrayList<>();
        final List<Pre> presets = new ArrayList<>();
        int sessions;
        int sets;
    }

    static final class Ex {
        String name;
        final List<Session> log = new ArrayList<>();
    }

    static final class Pre {
        String name;
        final List<String> exercises = new ArrayList<>();
    }

    static final class BadFile extends IOException {
        BadFile(String msg) {
            super(msg);
        }
    }

    private Backup() {
    }

    /** Writes everything; returns {exercises, sessions}. */
    static int[] export(Db db, OutputStream out) throws IOException {
        JsonWriter w = new JsonWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        w.setIndent(" ");
        int exercises = 0;
        int sessions = 0;
        w.beginObject();
        w.name("app").value(APP);
        w.name("format").value(FORMAT);
        w.name("exported").value(LocalDate.now().toString());

        w.name("exercises").beginArray();
        // Both cursors are ordered by exercise id: walk them side by side in one pass.
        try (Cursor ex = db.exportExercises(); Cursor sets = db.exportSets()) {
            boolean more = sets.moveToFirst();
            while (ex.moveToNext()) {
                long id = ex.getLong(0);
                w.beginObject();
                w.name("name").value(ex.getString(1));
                w.name("log").beginArray();
                while (more && sets.getLong(0) < id) more = sets.moveToNext();
                long day = Long.MIN_VALUE;
                while (more && sets.getLong(0) == id) {
                    long d = sets.getLong(1);
                    if (d != day) {
                        if (day != Long.MIN_VALUE) w.endArray().endObject();
                        day = d;
                        sessions++;
                        w.beginObject();
                        w.name("date").value(LocalDate.ofEpochDay(d).toString());
                        w.name("sets").beginArray();
                    }
                    w.beginArray();
                    writeKg(w, sets.getDouble(2));
                    w.value(sets.getInt(3));
                    w.endArray();
                    more = sets.moveToNext();
                }
                if (day != Long.MIN_VALUE) w.endArray().endObject();
                w.endArray();
                w.endObject();
                exercises++;
            }
        }
        w.endArray();

        w.name("presets").beginArray();
        try (Cursor p = db.exportPresets()) {
            while (p.moveToNext()) {
                w.beginObject();
                w.name("name").value(p.getString(1));
                w.name("exercises").beginArray();
                for (String name : db.presetExerciseNames(p.getLong(0))) w.value(name);
                w.endArray();
                w.endObject();
            }
        }
        w.endArray();
        w.endObject();
        w.flush();
        return new int[]{exercises, sessions};
    }

    private static void writeKg(JsonWriter w, double kg) throws IOException {
        long c = Strength.centi(kg);
        if (c % 100 == 0) w.value(c / 100);
        else w.value(c / 100.0);
    }

    /** Reads and validates a backup without touching the database. */
    static Data read(InputStream in) throws IOException {
        JsonReader r = new JsonReader(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        // Exercises merged by name; days of the same exercise merged by date (the later entry wins).
        Map<String, Ex> exercises = new LinkedHashMap<>();
        Map<String, Map<Long, Session>> days = new HashMap<>();
        Map<String, Pre> presets = new LinkedHashMap<>();
        String app = null;
        try {
            if (r.peek() != JsonToken.BEGIN_OBJECT) throw new BadFile("not an object");
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                if (name.equals("app") && r.peek() == JsonToken.STRING) {
                    app = r.nextString();
                } else if (name.equals("exercises") && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) readExercise(r, exercises, days);
                    r.endArray();
                } else if (name.equals("presets") && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) readPreset(r, presets);
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        } catch (BadFile e) {
            throw e;
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            // Malformed or truncated JSON.
            throw new BadFile(String.valueOf(e.getMessage()));
        }
        if (!APP.equals(app)) throw new BadFile("not a Gym Notes backup");

        Data d = new Data();
        for (Map.Entry<String, Ex> e : exercises.entrySet()) {
            Ex ex = e.getValue();
            Map<Long, Session> byDay = days.get(e.getKey());
            if (byDay != null) {
                ex.log.addAll(byDay.values());
                Collections.sort(ex.log, new Comparator<Session>() {
                    @Override
                    public int compare(Session a, Session b) {
                        return Long.compare(a.day, b.day);
                    }
                });
            }
            d.sessions += ex.log.size();
            for (Session s : ex.log) d.sets += s.n;
            d.exercises.add(ex);
        }
        d.presets.addAll(presets.values());
        return d;
    }

    private static void readExercise(JsonReader r, Map<String, Ex> exercises, Map<String, Map<Long, Session>> days)
            throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return;
        }
        String name = null;
        List<Session> log = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            if (field.equals("name") && r.peek() == JsonToken.STRING) {
                name = Db.clean(r.nextString());
            } else if (field.equals("log") && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    Session s = readSession(r);
                    if (s != null) log.add(s);
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (name == null || name.isEmpty()) return;
        String key = Db.key(name);
        if (!exercises.containsKey(key)) {
            Ex ex = new Ex();
            ex.name = name;
            exercises.put(key, ex);
            days.put(key, new HashMap<Long, Session>());
        }
        Map<Long, Session> byDay = days.get(key);
        for (Session s : log) byDay.put(s.day, s);
    }

    /** One day's entry, or null if it has no valid sets. */
    private static Session readSession(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return null;
        }
        Long day = null;
        Session s = new Session(0);
        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            if (field.equals("date") && r.peek() == JsonToken.STRING) {
                try {
                    day = LocalDate.parse(r.nextString().trim()).toEpochDay();
                } catch (DateTimeParseException e) {
                    day = null;
                }
            } else if (field.equals("sets") && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) readSet(r, s);
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (day == null || s.n == 0) return null;
        Session out = new Session(day);
        for (int i = 0; i < s.n; i++) out.add(s.weight[i], s.reps[i]);
        return out;
    }

    /** A set is [weight, reps]; anything else is skipped. */
    private static void readSet(JsonReader r, Session into) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue();
            return;
        }
        r.beginArray();
        double w = -1;
        int reps = 0;
        int i = 0;
        while (r.hasNext()) {
            if (r.peek() != JsonToken.NUMBER) {
                r.skipValue();
            } else if (i == 0) {
                w = r.nextDouble();
            } else if (i == 1) {
                double v = r.nextDouble();
                reps = v == Math.rint(v) && v <= 1000 ? (int) v : 0;
            } else {
                r.skipValue();
            }
            i++;
        }
        r.endArray();
        if (w >= 0 && w <= 2000 && reps > 0) into.add(w, reps);
    }

    private static void readPreset(JsonReader r, Map<String, Pre> presets) throws IOException {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue();
            return;
        }
        Pre p = new Pre();
        List<String> keys = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String field = r.nextName();
            if (field.equals("name") && r.peek() == JsonToken.STRING) {
                p.name = Db.clean(r.nextString());
            } else if (field.equals("exercises") && r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray();
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.STRING) {
                        r.skipValue();
                        continue;
                    }
                    String name = Db.clean(r.nextString());
                    String key = Db.key(name);
                    if (name.isEmpty() || keys.contains(key)) continue;
                    keys.add(key);
                    p.exercises.add(name);
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (p.name == null || p.name.isEmpty()) return;
        String key = Db.key(p.name);
        if (!presets.containsKey(key)) presets.put(key, p);
    }
}
