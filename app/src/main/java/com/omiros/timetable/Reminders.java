package com.omiros.timetable;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Keeps exactly one alarm armed: the start of the next block (today or tomorrow), while notifications are on.
 * Each firing posts its notification and arms the following one.
 */
final class Reminders {
    static final String EXTRA_DATE = "date";
    static final String EXTRA_SLOT = "slot";
    private static final String CHANNEL = "blocks";
    private static final int NOTIFICATION_ID = 1;

    private Reminders() {
    }

    static void reschedule(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        DayStore store = new DayStore(context);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        if (!store.notificationsOn()) {
            am.cancel(alarmIntent(context, today, 0));
            return;
        }

        for (LocalDate d = today; !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
            DayStore.Day day = store.load(d);
            for (int i = 0; i < DayStore.SLOTS; i++) {
                if (!isBlockStart(day, i)) continue;
                LocalDateTime at = d.atStartOfDay().plusMinutes(DayStore.START_MIN + i * DayStore.SLOT_MIN);
                if (!at.isAfter(now)) continue;
                PendingIntent pi = alarmIntent(context, d, i);
                long millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi);
                }
                return;
            }
        }
        // Nothing left today or tomorrow: wake up just after midnight to look at the new "tomorrow",
        // so plans written further ahead still get their notifications without opening the app.
        long recheck = today.plusDays(1).atStartOfDay().plusMinutes(1)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, recheck, alarmIntent(context, today, -1));
    }

    /** Called when an alarm fires: notify if the block is still there, then arm the next one. */
    static void onAlarm(Context context, Intent intent) {
        String date = intent.getStringExtra(EXTRA_DATE);
        int slot = intent.getIntExtra(EXTRA_SLOT, -1);
        if (date != null && slot >= 0 && slot < DayStore.SLOTS) {
            LocalDate d = LocalDate.parse(date);
            DayStore store = new DayStore(context);
            DayStore.Day day = store.load(d);
            if (store.notificationsOn() && isBlockStart(day, slot)) {
                int end = slot + 1;
                while (end < DayStore.SLOTS && day.text[slot].equals(day.text[end])) end++;
                // Skip a late alarm (phone was off) for an activity that is already over.
                LocalDateTime endsAt = d.atStartOfDay().plusMinutes(DayStore.slotStart(end));
                if (LocalDateTime.now().isBefore(endsAt)) {
                    notify(context, day.text[slot], DayStore.formatMin(DayStore.slotStart(slot))
                            + " – " + DayStore.formatMin(DayStore.slotStart(end)));
                }
            }
        }
        reschedule(context);
    }

    private static boolean isBlockStart(DayStore.Day day, int i) {
        return day.text[i] != null && (i == 0 || !day.text[i].equals(day.text[i - 1]));
    }

    private static PendingIntent alarmIntent(Context context, LocalDate d, int slot) {
        Intent i = new Intent(context, ReminderReceiver.class)
                .putExtra(EXTRA_DATE, d.toString())
                .putExtra(EXTRA_SLOT, slot);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notify(Context context, String title, String text) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Πρόγραμμα", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Ειδοποίηση στην αρχή κάθε δραστηριότητας");
        nm.createNotificationChannel(ch);

        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(Theme.ACCENT)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }
}
