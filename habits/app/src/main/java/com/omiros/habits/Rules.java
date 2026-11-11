package com.omiros.habits;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How hard the app pushes: per habit a deadline and a level, plus the main switch, app lists and
 * friends. Changes follow one rule: today the stricter of the old and the new rules applies, from
 * tomorrow the new ones. So anything that makes things stricter works at once and anything that
 * lets you off starts tomorrow, never in the moment it would let you escape.
 */
final class Rules {
    static final int NONE = 0;
    static final int REMIND = 1;
    static final int NAG = 2;
    static final int LOCK = 3;

    static final int PASSES_PER_MONTH = 3;
    static final int REST_PER_MONTH = 4;
    static final int MAX_FRIENDS = 3;

    private static final Rule NO_RULE = new Rule();
    private static Rules instance;

    private final SharedPreferences prefs;
    private Doc now;
    private Doc next;
    private LocalDate nextFrom;

    static final class Rule {
        /** A habit of not doing something ("Χωρίς social media") rather than doing it. */
        boolean avoid;
        /** Do-habits: deadline in minutes after midnight, -1 for none. */
        int minute = -1;
        int level = NONE;
        /** LOCK: every app but the exceptions, or only the apps in my list. */
        boolean all = true;
        /** Minutes of the timer, 0 for none. */
        int timer;
        /** Avoid-habits: block my list between {@code from} and {@code until} (minutes after midnight). */
        boolean block;
        int from;
        int until = 24 * 60;

        /** Whether the rule puts any pressure on at all. */
        boolean active() {
            return avoid ? block : level > NONE && minute >= 0;
        }

        Rule copy() {
            return fromJson(toJson());
        }

        JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                if (avoid) o.put("avoid", true).put("block", block).put("from", from).put("until", until);
                else o.put("min", minute).put("level", level).put("all", all);
                return o.put("timer", timer);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        static Rule fromJson(JSONObject o) {
            Rule r = new Rule();
            r.avoid = o.optBoolean("avoid");
            r.minute = o.optInt("min", -1);
            r.level = Math.max(NONE, Math.min(LOCK, o.optInt("level")));
            r.all = o.optBoolean("all", true);
            r.timer = Math.max(0, o.optInt("timer"));
            r.block = o.optBoolean("block");
            r.from = o.optInt("from");
            r.until = o.optInt("until", 24 * 60);
            return r;
        }
    }

    /** The whole set of rules at one moment. */
    static final class Doc {
        /** Main switch: off turns the app back into a plain tracker. */
        boolean on = true;
        final TreeMap<Integer, Rule> habits = new TreeMap<>();
        /** "My list": apps blocked by list locks and avoid-habits. */
        final TreeSet<String> block = new TreeSet<>();
        /** Apps that stay open even when a lock covers every app. */
        final TreeSet<String> allow = new TreeSet<>();
        final TreeSet<String> phones = new TreeSet<>();
        final TreeSet<String> emails = new TreeSet<>();
        /** Sunday-evening summary. */
        boolean review = true;
        /** Habits to take off the list when these rules start (deleted while their rules still held). */
        final TreeSet<Integer> drop = new TreeSet<>();

        Rule rule(int habitId) {
            Rule r = habits.get(habitId);
            return r != null ? r : NO_RULE;
        }

        boolean hasFriends() {
            return !phones.isEmpty() || !emails.isEmpty();
        }

        Doc copy() {
            return fromJson(toJson());
        }

        JSONObject toJson() {
            try {
                JSONObject h = new JSONObject();
                for (Map.Entry<Integer, Rule> e : habits.entrySet()) h.put(String.valueOf(e.getKey()), e.getValue().toJson());
                JSONArray drops = new JSONArray();
                for (int id : drop) drops.put(id);
                return new JSONObject().put("on", on).put("habits", h).put("block", new JSONArray(block))
                        .put("allow", new JSONArray(allow)).put("phones", new JSONArray(phones))
                        .put("emails", new JSONArray(emails)).put("review", review).put("drop", drops);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        static Doc fromJson(JSONObject o) {
            Doc d = new Doc();
            d.on = o.optBoolean("on", true);
            d.review = o.optBoolean("review", true);
            JSONObject h = o.optJSONObject("habits");
            if (h != null) {
                for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject r = h.optJSONObject(k);
                    try {
                        if (r != null) d.habits.put(Integer.parseInt(k), Rule.fromJson(r));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            strings(o.optJSONArray("block"), d.block);
            strings(o.optJSONArray("allow"), d.allow);
            strings(o.optJSONArray("phones"), d.phones);
            strings(o.optJSONArray("emails"), d.emails);
            JSONArray drops = o.optJSONArray("drop");
            for (int i = 0; drops != null && i < drops.length(); i++) d.drop.add(drops.optInt(i));
            return d;
        }

        private static void strings(JSONArray a, Set<String> into) {
            for (int i = 0; a != null && i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) into.add(s);
            }
        }

        boolean sameAs(Doc other) {
            return toJson().toString().equals(other.toJson().toString());
        }
    }

    private Rules(Context context) {
        prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE);
        now = parse(prefs.getString("now", null));
        if (now == null) {
            now = new Doc();
            now.allow.addAll(LockService.defaultExceptions(context));
        }
        next = parse(prefs.getString("next", null));
        String from = prefs.getString("next_from", null);
        nextFrom = from == null ? null : LocalDate.parse(from);
        if (next == null || nextFrom == null) {
            next = null;
            nextFrom = null;
        }
    }

    static synchronized Rules get(Context context) {
        if (instance == null) instance = new Rules(context.getApplicationContext());
        return instance;
    }

    private static Doc parse(String json) {
        if (json == null) return null;
        try {
            return Doc.fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    /** The rules in force on {@code day}; reaching the day a pending change starts makes it current. */
    Doc on(LocalDate day) {
        if (next != null && !day.isBefore(nextFrom)) {
            now = next;
            next = null;
            nextFrom = null;
            persist();
        }
        return now;
    }

    /** What the person last asked for (what settings screens show): tomorrow's rules if any are waiting. */
    Doc wanted() {
        return next != null ? next : now;
    }

    /** True if part of what was asked for only starts tomorrow. */
    boolean hasPending() {
        return next != null;
    }

    /**
     * Applies {@code want}: today the stricter of it and the current rules, from tomorrow {@code want}
     * itself. Returns true if some of it (anything that lets you off) waits until tomorrow.
     */
    boolean edit(LocalDate today, Doc want) {
        Doc current = on(today);
        Doc strict = stricter(current, want);
        if (strict.sameAs(want)) {
            now = want.copy();
            next = null;
            nextFrom = null;
        } else {
            now = strict;
            next = want.copy();
            nextFrom = today.plusDays(1);
        }
        persist();
        return next != null;
    }

    /** Clears the habits waiting to be dropped once they have been taken off the list. */
    void dropped() {
        if (now.drop.isEmpty()) return;
        now.drop.clear();
        persist();
    }

    /** Field by field, the stricter of {@code a} (in force) and {@code b} (asked for); otherwise b. */
    static Doc stricter(Doc a, Doc b) {
        Doc t = b.copy();
        t.drop.clear();
        t.on = a.on || b.on;
        for (Map.Entry<Integer, Rule> e : a.habits.entrySet()) {
            Rule old = e.getValue();
            Rule want = b.habits.get(e.getKey());
            if (want == null || want.avoid != old.avoid) {
                // Removed, or turned into the other kind of habit: today keeps the old pressure.
                if (old.active()) t.habits.put(e.getKey(), old.copy());
                continue;
            }
            Rule r = want.copy();
            if (!old.avoid && old.active()) {
                r.level = Math.max(old.level, want.level);
                r.minute = want.minute < 0 || want.level == NONE ? old.minute : Math.min(old.minute, want.minute);
                r.all = old.level == LOCK && old.all || want.level == LOCK && want.all;
            } else if (old.avoid && old.block) {
                r.block = true;
                r.from = want.block ? Math.min(old.from, want.from) : old.from;
                r.until = want.block ? Math.max(old.until, want.until) : old.until;
            }
            t.habits.put(e.getKey(), r);
        }
        t.block.addAll(a.block);
        t.allow.retainAll(a.allow);
        t.phones.addAll(a.phones);
        t.emails.addAll(a.emails);
        return t;
    }

    private void persist() {
        SharedPreferences.Editor e = prefs.edit().putString("now", now.toJson().toString());
        if (next != null) e.putString("next", next.toJson().toString()).putString("next_from", nextFrom.toString());
        else e.remove("next").remove("next_from");
        // Written straight away: the rules decide what gets locked.
        e.commit();
    }
}
