package com.omiros.habits;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps exactly one alarm armed: the next moment something is due (a reminder, the end of a
 * timer, the Sunday review or midnight). Each firing handles everything due since the last one,
 * posting at most one notification per habit, and arms the next.
 */
final class Alarms {
    static final String ACTION_ALARM = "com.omiros.habits.ALARM";
    static final String ACTION_DONE = "com.omiros.habits.DONE";
    static final String ACTION_TIMER = "com.omiros.habits.TIMER";
    static final String ACTION_STOP = "com.omiros.habits.STOP_TIMER";
    static final String ACTION_TOGGLE = "com.omiros.habits.TOGGLE";
    static final String EXTRA_ID = "id";

    private static final String CH_NAG = "nag";
    private static final String CH_TIMER = "timer";
    private static final String CH_INFO = "info";
    private static final int ID_TIMER = 2;
    private static final int ID_REVIEW = 3;
    private static final int ID_NAG = 1000;

    /** Minutes before the deadline at which notifications go out, by level. */
    private static final int[] REMIND_AT = {60};
    private static final int[] NAG_AT = {120, 60, 45, 30, 15, 0};

    static final int NAG = 0;
    static final int CHECK = 1;
    static final int REVIEW = 2;
    static final int MIDNIGHT = 3;

    private Alarms() {
    }

    static final class Event {
        final long at;
        final int kind;
        final int habitId;
        /** NAG: minutes left until the deadline. */
        final int left;

        Event(long at, int kind, int habitId, int left) {
            this.at = at;
            this.kind = kind;
            this.habitId = habitId;
            this.left = left;
        }
    }

    private static long millis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** Everything due on {@code day} (always today: rules are never read ahead of time). */
    static List<Event> events(Context c, LocalDate day) {
        List<Event> out = new ArrayList<>();
        Rules.Doc doc = Rules.get(c).on(day);
        MonthStore.Month m = MonthStore.get(c).load(YearMonth.from(day));
        int d = day.getDayOfMonth();
        LocalDateTime start = day.atStartOfDay();
        if (doc.on && !m.isRest(d) && !m.hasPass(d)) {
            for (MonthStore.Habit h : m.habits) {
                Rules.Rule r = doc.rule(h.id);
                if (r.avoid) {
                    if (r.block && r.from > 0) out.add(new Event(millis(start.plusMinutes(r.from)), CHECK, h.id, 0));
                    continue;
                }
                if (!r.active() || h.isDone(d)) continue;
                for (int before : r.level == Rules.REMIND ? REMIND_AT : NAG_AT) {
                    if (r.minute - before < 0) continue;
                    out.add(new Event(millis(start.plusMinutes(r.minute - before)), NAG, h.id, before));
                }
            }
        }
        if (doc.review && Ui.isSunday(day)) out.add(new Event(millis(start.plusHours(21)), REVIEW, 0, 0));
        out.add(new Event(millis(start.plusDays(1).plusSeconds(5)), MIDNIGHT, 0, 0));
        return out;
    }

    static void reschedule(Context c) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Event e : events(c, LocalDate.now())) {
            if (e.at > now) next = Math.min(next, e.at);
        }
        long timer = timerEnd(c);
        if (timer > 0) next = Math.min(next, Math.max(timer, now + 1000));
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = broadcast(c, ACTION_ALARM, 0);
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    /** An alarm went off: handle everything due since the last one, then arm the next. */
    static void onAlarm(Context c) {
        Engine.sync(c);
        SharedPreferences st = Engine.state(c);
        long now = System.currentTimeMillis();
        long last = st.getLong("last_alarm", now - 60_000);
        if (last > now) last = now - 60_000;
        LocalDate today = LocalDate.now();
        List<Event> due = new ArrayList<>();
        boolean review = false;
        for (Event e : events(c, today)) {
            if (e.at <= last || e.at > now) continue;
            if (e.kind == REVIEW) review = true;
            if (e.kind != NAG) continue;
            // Only the latest reminder per habit, so a phone that was off does not get a burst.
            for (int i = 0; i < due.size(); i++) {
                if (due.get(i).habitId == e.habitId) {
                    if (due.get(i).at < e.at) due.set(i, e);
                    e = null;
                    break;
                }
            }
            if (e != null) due.add(e);
        }
        st.edit().putLong("last_alarm", now).apply();
        for (Event e : due) postNag(c, e);
        if (review) postReview(c, today);
        long timer = timerEnd(c);
        if (timer > 0 && timer <= now) finishTimer(c);
        else Engine.changed(c);
    }

    // ------------------------------------------------------------------ notifications

    private static NotificationManager channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_NAG) == null) {
            NotificationChannel nag = new NotificationChannel(CH_NAG, "Υπενθυμίσεις", NotificationManager.IMPORTANCE_HIGH);
            nag.setDescription("Πριν και όταν λήγει η ώρα μιας συνήθειας");
            nm.createNotificationChannel(nag);
            NotificationChannel timer = new NotificationChannel(CH_TIMER, "Χρονόμετρο", NotificationManager.IMPORTANCE_LOW);
            timer.setDescription("Όσο τρέχει το χρονόμετρο μιας συνήθειας");
            nm.createNotificationChannel(timer);
            NotificationChannel info = new NotificationChannel(CH_INFO, "Ανασκόπηση", NotificationManager.IMPORTANCE_DEFAULT);
            info.setDescription("Η ανασκόπηση της Κυριακής");
            nm.createNotificationChannel(info);
        }
        return nm;
    }

    private static Notification.Builder builder(Context c, String channel) {
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(Theme.ACCENT)
                .setContentIntent(open);
    }

    private static void postNag(Context c, Event e) {
        LocalDate today = LocalDate.now();
        MonthStore.Habit h = MonthStore.get(c).load(YearMonth.from(today)).find(e.habitId);
        if (h == null) return;
        Rules.Rule r = Rules.get(c).on(today).rule(h.id);
        String text;
        if (e.left > 0) {
            text = "Μένουν " + Ui.duration(e.left) + ", ως τις " + Ui.time(r.minute) + ".";
        } else if (r.level == Rules.LOCK && LockService.isOn(c)) {
            text = "Έληξε η ώρα. Το κινητό κλειδώνει μέχρι να το κάνεις.";
        } else {
            text = "Έληξε η ώρα (" + Ui.time(r.minute) + ") και δεν έχει γίνει.";
        }
        Notification.Builder b = builder(c, CH_NAG)
                .setContentTitle(h.name)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Έγινε", broadcast(c, ACTION_DONE, h.id)).build());
        if (r.timer > 0) {
            b.addAction(new Notification.Action.Builder(null, "Χρονόμετρο " + r.timer + "'", broadcast(c, ACTION_TIMER, h.id)).build());
        }
        channels(c).notify(ID_NAG + h.id, b.build());
    }

    static void cancelNag(Context c, int habitId) {
        c.getSystemService(NotificationManager.class).cancel(ID_NAG + habitId);
    }

    static void cancelAllNags(Context c, MonthStore.Month m) {
        for (MonthStore.Habit h : m.habits) cancelNag(c, h.id);
    }

    /** The Sunday review: the last seven days, habit by habit. */
    static String[] review(Context c, LocalDate sunday) {
        MonthStore store = MonthStore.get(c);
        MonthStore.Month cur = store.load(YearMonth.from(sunday));
        StringBuilder lines = new StringBuilder();
        int done = 0;
        int possible = 0;
        int passes = 0;
        String worst = null;
        int worstShare = 101;
        for (MonthStore.Habit h : cur.habits) {
            int hd = 0;
            int hp = 0;
            for (LocalDate d = sunday.minusDays(6); !d.isAfter(sunday); d = d.plusDays(1)) {
                MonthStore.Month m = store.load(YearMonth.from(d));
                MonthStore.Habit x = m.find(h.id);
                int day = d.getDayOfMonth();
                if (x == null || x.since > day || m.isRest(day)) continue;
                hp++;
                if (x.isDone(day)) hd++;
            }
            if (hp == 0) continue;
            done += hd;
            possible += hp;
            if (lines.length() > 0) lines.append('\n');
            lines.append(h.name).append(": ").append(hd).append(" από ").append(hp);
            int share = hd * 100 / hp;
            if (share < worstShare) {
                worstShare = share;
                worst = h.name;
            }
        }
        for (LocalDate d = sunday.minusDays(6); !d.isAfter(sunday); d = d.plusDays(1)) {
            if (store.load(YearMonth.from(d)).hasPass(d.getDayOfMonth())) passes++;
        }
        if (passes > 0) lines.append("\nΠάσα: ").append(passes);
        if (worst != null && worstShare < 100) lines.append("\nΠιο πίσω έμεινε: ").append(worst).append('.');
        return new String[]{"Η εβδομάδα σου: " + done + " από " + possible, lines.toString()};
    }

    private static void postReview(Context c, LocalDate sunday) {
        String[] r = review(c, sunday);
        if (r[1].isEmpty()) return;
        Notification n = builder(c, CH_INFO)
                .setContentTitle(r[0])
                .setContentText(r[1].split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(r[1]))
                .setAutoCancel(true)
                .build();
        channels(c).notify(ID_REVIEW, n);
    }

    // ------------------------------------------------------------------ timer

    /** End of the running timer in epoch millis, or 0 when none runs. */
    static long timerEnd(Context c) {
        return Engine.state(c).getLong("timer_end", 0);
    }

    static int timerHabit(Context c) {
        return Engine.state(c).getInt("timer_id", -1);
    }

    /** Starts the habit's timer (one at a time); when it ends the habit is ticked for the day it began. */
    static void startTimer(Context c, int habitId) {
        LocalDate today = LocalDate.now();
        MonthStore.Habit h = MonthStore.get(c).load(YearMonth.from(today)).find(habitId);
        Rules.Rule r = Rules.get(c).on(today).rule(habitId);
        if (h == null || r.timer <= 0) return;
        long end = System.currentTimeMillis() + r.timer * 60_000L;
        Engine.state(c).edit().putInt("timer_id", habitId).putString("timer_day", today.toString())
                .putLong("timer_end", end).commit();
        cancelNag(c, habitId);
        Notification n = builder(c, CH_TIMER)
                .setContentTitle(h.name)
                .setContentText("Τικάρεται μόλις τελειώσει.")
                .setOngoing(true)
                .setWhen(end)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .addAction(new Notification.Action.Builder(null, "Σταμάτα", broadcast(c, ACTION_STOP, 0)).build())
                .build();
        channels(c).notify(ID_TIMER, n);
        Engine.changed(c);
    }

    static void stopTimer(Context c) {
        Engine.state(c).edit().remove("timer_id").remove("timer_day").remove("timer_end").commit();
        c.getSystemService(NotificationManager.class).cancel(ID_TIMER);
        Engine.changed(c);
    }

    static void finishTimer(Context c) {
        SharedPreferences st = Engine.state(c);
        int id = st.getInt("timer_id", -1);
        String day = st.getString("timer_day", null);
        st.edit().remove("timer_id").remove("timer_day").remove("timer_end").commit();
        if (id < 0 || day == null) {
            Engine.changed(c);
            return;
        }
        LocalDate d = LocalDate.parse(day);
        MonthStore.Habit h = MonthStore.get(c).load(YearMonth.from(d)).find(id);
        Engine.setDone(c, id, d, true);
        if (h != null) {
            Notification n = builder(c, CH_TIMER)
                    .setContentTitle(h.name)
                    .setContentText("Τέλος χρόνου. Τικαρίστηκε.")
                    .setAutoCancel(true)
                    .build();
            channels(c).notify(ID_TIMER, n);
        }
    }

    static PendingIntent broadcast(Context c, String action, int habitId) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action).putExtra(EXTRA_ID, habitId);
        // One PendingIntent per action and habit: the request code keeps them apart.
        return PendingIntent.getBroadcast(c, action.hashCode() * 31 + habitId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
