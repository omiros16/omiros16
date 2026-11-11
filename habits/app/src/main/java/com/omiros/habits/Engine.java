package com.omiros.habits;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * What the rules mean at a given moment: which apps are locked, whether a day off or a pass is
 * allowed, and what happens when a day ends. Shared by the screen, the notifications, the lock
 * service and the widget.
 */
final class Engine {
    private Engine() {
    }

    /** What is locked right now and why. */
    static final class Lock {
        /** Do-habits past their deadline: ticking them is what unlocks. */
        final List<MonthStore.Habit> habits = new ArrayList<>();
        /** Avoid-habits blocking my list right now. */
        final List<MonthStore.Habit> avoids = new ArrayList<>();
        /** Some lock covers every app but the exceptions. */
        boolean all;
        /** Latest end of the avoid blocks, minutes after midnight. */
        int until;

        boolean blocks(Rules.Doc doc, String pkg) {
            return all && !doc.allow.contains(pkg) || doc.block.contains(pkg);
        }
    }

    static SharedPreferences state(Context c) {
        return c.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    static int minuteOf(LocalDateTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    /** The lock in force at {@code t}, or null when nothing is locked. */
    static Lock lockAt(Context c, LocalDateTime t) {
        LocalDate day = t.toLocalDate();
        Rules.Doc doc = Rules.get(c).on(day);
        if (!doc.on) return null;
        MonthStore.Month m = MonthStore.get(c).load(YearMonth.from(day));
        int d = day.getDayOfMonth();
        if (m.isRest(d) || m.hasPass(d)) return null;
        int min = minuteOf(t);
        Lock lock = new Lock();
        for (MonthStore.Habit h : m.habits) {
            Rules.Rule r = doc.rule(h.id);
            if (!r.avoid && r.level == Rules.LOCK && r.minute >= 0 && min >= r.minute && !h.isDone(d)) {
                lock.habits.add(h);
                if (r.all) lock.all = true;
            } else if (r.avoid && r.block && min >= r.from && min < r.until) {
                lock.avoids.add(h);
                lock.until = Math.max(lock.until, r.until);
            }
        }
        return lock.habits.isEmpty() && lock.avoids.isEmpty() ? null : lock;
    }

    static void setDone(Context c, int habitId, LocalDate day, boolean done) {
        MonthStore store = MonthStore.get(c);
        MonthStore.Month m = store.load(YearMonth.from(day));
        MonthStore.Habit h = m.find(habitId);
        if (h == null) return;
        h.setDone(day.getDayOfMonth(), done);
        store.save(m);
        if (done) Alarms.cancelNag(c, habitId);
        changed(c);
    }

    /**
     * "Done" for today from any button. A habit whose timer has to be followed gets its timer started
     * instead, and is ticked when it runs out. Returns null if ticked, otherwise what to tell the person.
     */
    static String tick(Context c, int habitId) {
        LocalDate today = LocalDate.now();
        Rules.Doc doc = Rules.get(c).on(today);
        if (!doc.timerOnly(habitId)) {
            setDone(c, habitId, today, true);
            return null;
        }
        // A timer that ran out but has not been handled yet (late alarm) counts as finished.
        if (Alarms.timerHabit(c) >= 0 && Alarms.timerEnd(c) <= System.currentTimeMillis()) Alarms.finishTimer(c);
        int running = Alarms.timerHabit(c);
        if (running == habitId) return "Το χρονόμετρο τρέχει ήδη. Τικάρεται μόλις τελειώσει.";
        MonthStore.Habit other = running < 0 ? null : MonthStore.get(c).load(YearMonth.from(today)).find(running);
        // One timer at a time, and a stray tap must not throw away the one that runs.
        if (other != null) return "Τρέχει το χρονόμετρο για «" + other.name + "». Σταμάτησέ το πρώτα.";
        Alarms.startTimer(c, habitId);
        return "Χρονόμετρο " + doc.rule(habitId).timer + "'. Τικάρεται μόλις τελειώσει.";
    }

    /** After any change: re-arm the next notification, redraw the widget, recheck the app in front. */
    static void changed(Context c) {
        Alarms.reschedule(c);
        Widget.update(c);
        LockService.recheck();
    }

    static int passesLeft(MonthStore.Month m) {
        return Math.max(0, Rules.PASSES_PER_MONTH - Integer.bitCount(m.passes));
    }

    static int restLeft(MonthStore.Month m) {
        return Math.max(0, Rules.REST_PER_MONTH - Integer.bitCount(m.rest));
    }

    /** Spends a pass: everything unlocks until midnight and friends are told. False if none is left. */
    static boolean usePass(Context c) {
        LocalDate today = LocalDate.now();
        MonthStore store = MonthStore.get(c);
        MonthStore.Month m = store.load(YearMonth.from(today));
        int d = today.getDayOfMonth();
        if (m.hasPass(d) || passesLeft(m) == 0) return false;
        m.passes |= MonthStore.bit(d);
        store.save(m);
        Rules.Doc doc = Rules.get(c).on(today);
        Friends.send(c, doc, "Habits: πάσο",
                "Habits: Χρησιμοποίησα πάσο σήμερα, " + Ui.weekday(today) + " " + today.getDayOfMonth() + "/"
                        + today.getMonthValue() + ", για να ξεκλειδώσω το κινητό χωρίς να κάνω τις συνήθειές μου. "
                        + "Μου μένουν " + passesLeft(m) + " τον " + Ui.monthAcc(today.getMonthValue()) + ".");
        changed(c);
        return true;
    }

    /**
     * Null if today can be taken off now, otherwise the reason it cannot. A day off must be declared
     * before the day's first deadline, so it can never be used to get out of a lock.
     */
    static String restBlocker(Context c, LocalDateTime t) {
        LocalDate day = t.toLocalDate();
        MonthStore.Month m = MonthStore.get(c).load(YearMonth.from(day));
        if (m.isRest(day.getDayOfMonth())) return "Είναι ήδη ρεπό σήμερα.";
        if (restLeft(m) == 0) return "Τελείωσαν τα ρεπό του " + Ui.MONTHS_GEN[day.getMonthValue() - 1] + ".";
        Rules.Doc doc = Rules.get(c).on(day);
        int first = -1;
        for (MonthStore.Habit h : m.habits) {
            Rules.Rule r = doc.rule(h.id);
            if (!r.avoid && r.active() && (first < 0 || r.minute < first)) first = r.minute;
        }
        if (doc.on && first >= 0 && minuteOf(t) >= first) {
            return "Το ρεπό δηλώνεται πριν από το πρώτο όριο της μέρας (" + Ui.time(first) + ").";
        }
        return null;
    }

    static void setRest(Context c, LocalDate day, boolean rest) {
        MonthStore store = MonthStore.get(c);
        MonthStore.Month m = store.load(YearMonth.from(day));
        if (rest) m.rest |= MonthStore.bit(day.getDayOfMonth());
        else m.rest &= ~MonthStore.bit(day.getDayOfMonth());
        store.save(m);
        if (rest) Alarms.cancelAllNags(c, m);
        changed(c);
    }

    /**
     * Brings everything up to date: closes the days that ended since last time, starts rules that
     * begin today (dropping habits deleted while their rules held) and retries unsent messages.
     */
    static void sync(Context c) {
        LocalDate today = LocalDate.now();
        SharedPreferences st = state(c);
        String saved = st.getString("closed", null);
        LocalDate closed = saved == null ? today.minusDays(1) : LocalDate.parse(saved);
        if (!closed.isBefore(today)) closed = today.minusDays(1);
        for (LocalDate d = closed.plusDays(1); d.isBefore(today); d = d.plusDays(1)) {
            // Only yesterday is reported to friends; older days (phone off for days) close quietly.
            closeDay(c, d, d.equals(today.minusDays(1)));
        }
        if (saved == null || closed.isBefore(today.minusDays(1))) {
            st.edit().putString("closed", today.minusDays(1).toString()).commit();
        }

        Rules rules = Rules.get(c);
        Rules.Doc doc = rules.on(today);
        if (!doc.drop.isEmpty()) {
            MonthStore store = MonthStore.get(c);
            MonthStore.Month m = store.load(YearMonth.from(today));
            for (Iterator<MonthStore.Habit> it = m.habits.iterator(); it.hasNext(); ) {
                if (doc.drop.contains(it.next().id)) it.remove();
            }
            store.save(m);
            rules.dropped();
        }
        Friends.retry(c);
    }

    /**
     * End of {@code day}: avoid-habits that blocked their apps count as done unless a pass was used,
     * and locked habits left undone are reported to friends.
     */
    private static void closeDay(Context c, LocalDate day, boolean report) {
        Rules.Doc doc = Rules.get(c).on(day);
        if (!doc.on) return;
        MonthStore store = MonthStore.get(c);
        MonthStore.Month m = store.load(YearMonth.from(day));
        int d = day.getDayOfMonth();
        if (m.isRest(d)) return;
        boolean changed = false;
        StringBuilder missed = new StringBuilder();
        for (MonthStore.Habit h : m.habits) {
            if (h.since > d || h.isDone(d)) continue;
            Rules.Rule r = doc.rule(h.id);
            if (r.avoid && r.block && !m.hasPass(d)) {
                h.setDone(d, true);
                changed = true;
            } else if (!r.avoid && r.level == Rules.LOCK && r.minute >= 0) {
                if (missed.length() > 0) missed.append(", ");
                missed.append(h.name).append(" (ως ").append(Ui.time(r.minute)).append(')');
            }
        }
        if (changed) store.save(m);
        if (report && missed.length() > 0) {
            Friends.send(c, doc, "Habits: δεν τα έκανα",
                    "Habits: " + Ui.weekday(day) + " " + d + "/" + day.getMonthValue() + " δεν έκανα: " + missed + ".");
        }
    }
}
