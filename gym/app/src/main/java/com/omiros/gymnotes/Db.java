package com.omiros.gymnotes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * All data lives in one SQLite database.
 *
 * <p>Exercises are shared by every preset, so logging "Lat pulldown" from Friday's preset shows up
 * in Monday's too. Logged sets are clustered by (exercise, day): one exercise's whole history is a
 * single contiguous range of the table, so opening an exercise costs the same after years of logs.
 * Per-exercise summaries (last day, records, last PR) are cached on the exercise row and recomputed
 * from that range whenever its sets change, so lists never scan the log.
 */
final class Db extends SQLiteOpenHelper {
    static final int MAX_PRESETS = 7;
    static final long NO_DAY = Long.MIN_VALUE;

    static final class Preset {
        long id;
        String name;
        int exercises;
        int doneToday;
    }

    static final class Exercise {
        long id;
        String name;
        long lastDay = NO_DAY;
        int sessions;
        double bestE1rm;
        double maxWeight;
        int lastPr;
    }

    private static final String EX_COLS =
            "e.id, e.name, e.last_day, e.sessions, e.best_e1rm, e.max_weight, e.last_pr";

    Db(Context context) {
        super(context, "gym.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE exercise (id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL UNIQUE,"
                + " last_day INTEGER, sessions INTEGER NOT NULL DEFAULT 0, best_e1rm REAL NOT NULL DEFAULT 0,"
                + " max_weight REAL NOT NULL DEFAULT 0, last_pr INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE preset (id INTEGER PRIMARY KEY, name TEXT NOT NULL, pos INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE preset_item ("
                + " preset_id INTEGER NOT NULL REFERENCES preset(id) ON DELETE CASCADE,"
                + " exercise_id INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,"
                + " pos INTEGER NOT NULL, PRIMARY KEY (preset_id, exercise_id)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX preset_item_exercise ON preset_item(exercise_id)");
        db.execSQL("CREATE TABLE log_set ("
                + " exercise_id INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,"
                + " day INTEGER NOT NULL, idx INTEGER NOT NULL, weight REAL NOT NULL, reps INTEGER NOT NULL,"
                + " PRIMARY KEY (exercise_id, day, idx)) WITHOUT ROWID");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    private SQLiteDatabase rd() {
        return getReadableDatabase();
    }

    private SQLiteDatabase wr() {
        return getWritableDatabase();
    }

    private static String[] args(Object... a) {
        String[] s = new String[a.length];
        for (int i = 0; i < a.length; i++) s[i] = String.valueOf(a[i]);
        return s;
    }

    /** Name as shown: trimmed, single spaces. */
    static String clean(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    /** Identity of a name: case, accents and spacing do not matter ("Πιέσεις" = "ΠΙΕΣΕΙΣ" = "πιεσεις"). */
    static String key(String name) {
        String n = Normalizer.normalize(clean(name), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('ς', 'σ');
    }

    boolean isEmpty() {
        try (Cursor c = rd().rawQuery("SELECT (SELECT COUNT(*) FROM exercise) + (SELECT COUNT(*) FROM preset)", null)) {
            return !c.moveToFirst() || c.getLong(0) == 0;
        }
    }

    // ---------------------------------------------------------------- presets

    List<Preset> presets(long today) {
        List<Preset> out = new ArrayList<>();
        try (Cursor c = rd().rawQuery("SELECT p.id, p.name, COUNT(i.exercise_id),"
                + " COALESCE(SUM(e.last_day = ?), 0) FROM preset p"
                + " LEFT JOIN preset_item i ON i.preset_id = p.id LEFT JOIN exercise e ON e.id = i.exercise_id"
                + " GROUP BY p.id ORDER BY p.pos, p.id", args(today))) {
            while (c.moveToNext()) {
                Preset p = new Preset();
                p.id = c.getLong(0);
                p.name = c.getString(1);
                p.exercises = c.getInt(2);
                p.doneToday = c.getInt(3);
                out.add(p);
            }
        }
        return out;
    }

    /** Name of a preset, or null if it no longer exists. */
    String presetName(long id) {
        try (Cursor c = rd().rawQuery("SELECT name FROM preset WHERE id = ?", args(id))) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    int presetCount() {
        try (Cursor c = rd().rawQuery("SELECT COUNT(*) FROM preset", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Returns the new preset's id, or -1 if there are already {@link #MAX_PRESETS}. */
    long addPreset(String name) {
        SQLiteDatabase db = wr();
        db.beginTransaction();
        try {
            if (presetCount() >= MAX_PRESETS) return -1;
            long id = insertPreset(db, name);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private static long insertPreset(SQLiteDatabase db, String name) {
        long pos = 1;
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(pos), 0) + 1 FROM preset", null)) {
            if (c.moveToFirst()) pos = c.getLong(0);
        }
        ContentValues v = new ContentValues();
        v.put("name", clean(name));
        v.put("pos", pos);
        return db.insertOrThrow("preset", null, v);
    }

    void renamePreset(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", clean(name));
        wr().update("preset", v, "id = ?", args(id));
    }

    void deletePreset(long id) {
        wr().delete("preset", "id = ?", args(id));
    }

    void movePreset(long id, int delta) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = rd().rawQuery("SELECT id FROM preset ORDER BY pos, id", null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        if (!swap(ids, id, delta)) return;
        SQLiteDatabase db = wr();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                db.execSQL("UPDATE preset SET pos = ? WHERE id = ?", new Object[]{i + 1, ids.get(i)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static boolean swap(List<Long> ids, long id, int delta) {
        int i = ids.indexOf(id);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= ids.size()) return false;
        Collections.swap(ids, i, j);
        return true;
    }

    // ---------------------------------------------------------------- preset contents

    List<Exercise> presetExercises(long presetId) {
        List<Exercise> out = new ArrayList<>();
        try (Cursor c = rd().rawQuery("SELECT " + EX_COLS + " FROM preset_item i"
                + " JOIN exercise e ON e.id = i.exercise_id WHERE i.preset_id = ? ORDER BY i.pos", args(presetId))) {
            while (c.moveToNext()) out.add(readExercise(c));
        }
        return out;
    }

    Set<Long> presetExerciseIds(long presetId) {
        Set<Long> out = new HashSet<>();
        try (Cursor c = rd().rawQuery("SELECT exercise_id FROM preset_item WHERE preset_id = ?", args(presetId))) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    void addToPreset(long presetId, long exerciseId) {
        addToPreset(wr(), presetId, exerciseId);
    }

    private static void addToPreset(SQLiteDatabase db, long presetId, long exerciseId) {
        db.execSQL("INSERT OR IGNORE INTO preset_item (preset_id, exercise_id, pos)"
                + " SELECT ?, ?, COALESCE(MAX(pos), 0) + 1 FROM preset_item WHERE preset_id = ?",
                new Object[]{presetId, exerciseId, presetId});
    }

    void removeFromPreset(long presetId, long exerciseId) {
        wr().delete("preset_item", "preset_id = ? AND exercise_id = ?", args(presetId, exerciseId));
    }

    void moveInPreset(long presetId, long exerciseId, int delta) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = rd().rawQuery("SELECT exercise_id FROM preset_item WHERE preset_id = ? ORDER BY pos",
                args(presetId))) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        if (!swap(ids, exerciseId, delta)) return;
        SQLiteDatabase db = wr();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                db.execSQL("UPDATE preset_item SET pos = ? WHERE preset_id = ? AND exercise_id = ?",
                        new Object[]{i + 1, presetId, ids.get(i)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------------------------------------------------------------- exercises

    private static Exercise readExercise(Cursor c) {
        Exercise e = new Exercise();
        e.id = c.getLong(0);
        e.name = c.getString(1);
        e.lastDay = c.isNull(2) ? NO_DAY : c.getLong(2);
        e.sessions = c.getInt(3);
        e.bestE1rm = c.getDouble(4);
        e.maxWeight = c.getDouble(5);
        e.lastPr = c.getInt(6);
        return e;
    }

    /** Every exercise, alphabetically. */
    List<Exercise> exercises() {
        List<Exercise> out = new ArrayList<>();
        try (Cursor c = rd().rawQuery("SELECT " + EX_COLS + " FROM exercise e", null)) {
            while (c.moveToNext()) out.add(readExercise(c));
        }
        final Collator collator = Collator.getInstance(Fmt.GREEK);
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(out, new Comparator<Exercise>() {
            @Override
            public int compare(Exercise a, Exercise b) {
                return collator.compare(a.name, b.name);
            }
        });
        return out;
    }

    /** An exercise, or null if it no longer exists. */
    Exercise exercise(long id) {
        try (Cursor c = rd().rawQuery("SELECT " + EX_COLS + " FROM exercise e WHERE e.id = ?", args(id))) {
            return c.moveToFirst() ? readExercise(c) : null;
        }
    }

    /** Id of the exercise with this name (ignoring case and accents), or -1. */
    long findExercise(String name) {
        return findExercise(rd(), name);
    }

    private static long findExercise(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("SELECT id FROM exercise WHERE key = ?", new String[]{key(name)})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    /** Id of the exercise with this name, created if needed. */
    long addExercise(String name) {
        return addExercise(wr(), name);
    }

    private static long addExercise(SQLiteDatabase db, String name) {
        long id = findExercise(db, name);
        if (id >= 0) return id;
        ContentValues v = new ContentValues();
        v.put("name", clean(name));
        v.put("key", key(name));
        return db.insertOrThrow("exercise", null, v);
    }

    /** False if another exercise already has this name. */
    boolean renameExercise(long id, String name) {
        long other = findExercise(name);
        if (other >= 0 && other != id) return false;
        ContentValues v = new ContentValues();
        v.put("name", clean(name));
        v.put("key", key(name));
        wr().update("exercise", v, "id = ?", args(id));
        return true;
    }

    /** Deletes the exercise, its history and its place in every preset. */
    void deleteExercise(long id) {
        wr().delete("exercise", "id = ?", args(id));
    }

    /** Exercise id -> "Δευτέρα · Παρασκευή" (the presets it is in, in preset order). */
    Map<Long, String> presetNamesByExercise() {
        Map<Long, String> out = new HashMap<>();
        try (Cursor c = rd().rawQuery("SELECT i.exercise_id, p.name FROM preset_item i"
                + " JOIN preset p ON p.id = i.preset_id ORDER BY p.pos, p.id", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String prev = out.get(id);
                out.put(id, prev == null ? c.getString(1) : prev + " · " + c.getString(1));
            }
        }
        return out;
    }

    String presetNames(long exerciseId) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = rd().rawQuery("SELECT p.name FROM preset_item i JOIN preset p ON p.id = i.preset_id"
                + " WHERE i.exercise_id = ? ORDER BY p.pos, p.id", args(exerciseId))) {
            while (c.moveToNext()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(c.getString(0));
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- logged sets

    /** The sets logged for an exercise on a day (empty if none). */
    Session session(long exerciseId, long day) {
        Session s = new Session(day);
        try (Cursor c = rd().rawQuery("SELECT weight, reps FROM log_set WHERE exercise_id = ? AND day = ?"
                + " ORDER BY idx", args(exerciseId, day))) {
            while (c.moveToNext()) s.add(c.getDouble(0), c.getInt(1));
        }
        return s;
    }

    /** The last day before {@code day} with sets for this exercise, or {@link #NO_DAY}. */
    long lastDayBefore(long exerciseId, long day) {
        try (Cursor c = rd().rawQuery("SELECT MAX(day) FROM log_set WHERE exercise_id = ? AND day < ?",
                args(exerciseId, day))) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : NO_DAY;
        }
    }

    /** Records over everything logged before {@code day}, to judge that day's sets. */
    Strength.Bests bestsBefore(long exerciseId, long day) {
        Strength.Bests b = new Strength.Bests();
        try (Cursor c = rd().rawQuery("SELECT weight, reps FROM log_set WHERE exercise_id = ? AND day < ?"
                + " ORDER BY day, idx", args(exerciseId, day))) {
            while (c.moveToNext()) b.add(c.getDouble(0), c.getInt(1));
        }
        return b;
    }

    /** Every session of an exercise, oldest first. */
    List<Session> history(long exerciseId) {
        return history(rd(), exerciseId);
    }

    private static List<Session> history(SQLiteDatabase db, long exerciseId) {
        List<Session> out = new ArrayList<>();
        Session cur = null;
        try (Cursor c = db.rawQuery("SELECT day, weight, reps FROM log_set WHERE exercise_id = ?"
                + " ORDER BY day, idx", args(exerciseId))) {
            while (c.moveToNext()) {
                long day = c.getLong(0);
                if (cur == null || cur.day != day) {
                    cur = new Session(day);
                    out.add(cur);
                }
                cur.add(c.getDouble(1), c.getInt(2));
            }
        }
        return out;
    }

    /** Replaces the sets of {@code s.day} for this exercise (an empty session deletes the day). */
    void saveSession(long exerciseId, Session s) {
        SQLiteDatabase db = wr();
        db.beginTransaction();
        try {
            SQLiteStatement ins = insertSet(db);
            replaceSession(db, ins, exerciseId, s);
            refreshStats(db, exerciseId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void deleteSession(long exerciseId, long day) {
        saveSession(exerciseId, new Session(day));
    }

    private static SQLiteStatement insertSet(SQLiteDatabase db) {
        return db.compileStatement("INSERT INTO log_set (exercise_id, day, idx, weight, reps) VALUES (?, ?, ?, ?, ?)");
    }

    private static void replaceSession(SQLiteDatabase db, SQLiteStatement ins, long exerciseId, Session s) {
        db.delete("log_set", "exercise_id = ? AND day = ?", args(exerciseId, s.day));
        int idx = 0;
        for (int i = 0; i < s.n; i++) {
            if (s.reps[i] <= 0 || s.weight[i] < 0) continue;
            ins.bindLong(1, exerciseId);
            ins.bindLong(2, s.day);
            ins.bindLong(3, idx++);
            ins.bindDouble(4, Strength.centi(s.weight[i]) / 100.0);
            ins.bindLong(5, s.reps[i]);
            ins.executeInsert();
        }
    }

    /** Recomputes the cached summary of one exercise from its own range of the log. */
    private static void refreshStats(SQLiteDatabase db, long exerciseId) {
        List<Session> h = history(db, exerciseId);
        Strength.Bests b = Strength.markPrs(h);
        ContentValues v = new ContentValues();
        if (h.isEmpty()) v.putNull("last_day");
        else v.put("last_day", h.get(h.size() - 1).day);
        v.put("sessions", h.size());
        v.put("best_e1rm", b.bestE1rm);
        v.put("max_weight", b.maxWeight);
        v.put("last_pr", h.isEmpty() ? 0 : h.get(h.size() - 1).flags);
        db.update("exercise", v, "id = ?", args(exerciseId));
    }

    // ---------------------------------------------------------------- backup

    /** Exercise rows ordered by id: id, name. */
    Cursor exportExercises() {
        return rd().rawQuery("SELECT id, name FROM exercise ORDER BY id", null);
    }

    /** Every logged set in table order: exercise_id, day, weight, reps. */
    Cursor exportSets() {
        return rd().rawQuery("SELECT exercise_id, day, weight, reps FROM log_set ORDER BY exercise_id, day, idx", null);
    }

    /** Preset rows in display order: id, name. */
    Cursor exportPresets() {
        return rd().rawQuery("SELECT id, name FROM preset ORDER BY pos, id", null);
    }

    List<String> presetExerciseNames(long presetId) {
        List<String> out = new ArrayList<>();
        try (Cursor c = rd().rawQuery("SELECT e.name FROM preset_item i JOIN exercise e ON e.id = i.exercise_id"
                + " WHERE i.preset_id = ? ORDER BY i.pos", args(presetId))) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** Result counts of {@link #importData}. */
    static final class ImportResult {
        int exercises;
        int sessions;
        int presets;
        int presetsSkipped;
    }

    /**
     * Writes a parsed backup in one transaction, so a failure leaves the data untouched.
     * Replace wipes everything first. Merge matches exercises and presets by name: imported days
     * replace the same days, presets get the missing exercises appended, new presets are added
     * while there is room.
     */
    ImportResult importData(Backup.Data data, boolean replace) {
        ImportResult r = new ImportResult();
        SQLiteDatabase db = wr();
        db.beginTransaction();
        try {
            if (replace) {
                db.delete("preset_item", null, null);
                db.delete("preset", null, null);
                db.delete("log_set", null, null);
                db.delete("exercise", null, null);
            }
            SQLiteStatement ins = insertSet(db);
            Map<String, Long> ids = new HashMap<>();
            for (Backup.Ex ex : data.exercises) {
                long id = addExercise(db, ex.name);
                ids.put(key(ex.name), id);
                for (Session s : ex.log) replaceSession(db, ins, id, s);
                refreshStats(db, id);
                r.exercises++;
                r.sessions += ex.log.size();
            }

            Map<String, Long> presets = new HashMap<>();
            int count = 0;
            try (Cursor c = db.rawQuery("SELECT id, name FROM preset", null)) {
                while (c.moveToNext()) {
                    presets.put(key(c.getString(1)), c.getLong(0));
                    count++;
                }
            }
            for (Backup.Pre p : data.presets) {
                Long pid = presets.get(key(p.name));
                if (pid == null) {
                    if (count >= MAX_PRESETS) {
                        r.presetsSkipped++;
                        continue;
                    }
                    pid = insertPreset(db, p.name);
                    presets.put(key(p.name), pid);
                    count++;
                }
                for (String name : p.exercises) {
                    Long eid = ids.get(key(name));
                    if (eid == null) eid = addExercise(db, name);
                    addToPreset(db, pid, eid);
                }
                r.presets++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return r;
    }
}
