package com.omiros.habits;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AlarmsTest extends Base {
    static long at(String dateTime) {
        return LocalDateTime.parse(dateTime.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static List<Notification> notifications() {
        return shadowOf(ctx().getSystemService(NotificationManager.class)).getAllNotifications();
    }

    static void seedNag(int level) throws Exception {
        seed(YearMonth.of(2026, 10), month(habit(1, "Διάβασμα 20'"), habit(2, "Γυμναστική")));
        seedRules(rules(rule(1, 20 * 60, level, true, 20)));
    }

    @Test
    public void nagEventsTightenTowardsTheDeadline() throws Exception {
        setClock("2026-10-17 17:00:00");
        seedNag(Rules.NAG);
        List<Long> nags = new ArrayList<>();
        for (Alarms.Event e : Alarms.events(ctx(), LocalDate.now())) if (e.kind == Alarms.NAG) nags.add(e.at);
        assertEquals(6, nags.size());
        assertTrue(nags.contains(at("2026-10-17 18:00:00")));
        assertTrue(nags.contains(at("2026-10-17 19:45:00")));
        assertTrue(nags.contains(at("2026-10-17 20:00:00")));
        Alarms.reschedule(ctx());
        AlarmManager am = ctx().getSystemService(AlarmManager.class);
        assertEquals(at("2026-10-17 18:00:00"), shadowOf(am).getNextScheduledAlarm().triggerAtTime);
    }

    @Test
    public void alarmPostsOneReminderWithDoneAndTimer() throws Exception {
        setClock("2026-10-17 17:00:00");
        seedNag(Rules.NAG);
        Engine.state(ctx()).edit().putLong("last_alarm", at("2026-10-17 17:00:00")).commit();
        setClock("2026-10-17 19:16:00");
        Alarms.onAlarm(ctx());
        List<Notification> ns = notifications();
        assertEquals(1, ns.size());
        Notification n = ns.get(0);
        assertEquals("Διάβασμα 20'", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Μένουν 45 λεπτά, ως τις 20:00.", n.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(2, n.actions.length);
        assertEquals("Έγινε", n.actions[0].title.toString());
        assertEquals("Χρονόμετρο 20'", n.actions[1].title.toString());

        // "Έγινε" on the notification ticks the habit and clears the reminder.
        new AlarmReceiver().onReceive(ctx(), new Intent(Alarms.ACTION_DONE).putExtra(Alarms.EXTRA_ID, 1));
        assertTrue(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(1).isDone(17));
        assertTrue(notifications().isEmpty());
        for (Alarms.Event e : Alarms.events(ctx(), LocalDate.now())) assertNotEquals(Alarms.NAG, e.kind);
    }

    @Test
    public void reminderLevelIsOneNotificationAnHourBefore() throws Exception {
        setClock("2026-10-17 10:00:00");
        seedNag(Rules.REMIND);
        List<Alarms.Event> nags = new ArrayList<>();
        for (Alarms.Event e : Alarms.events(ctx(), LocalDate.now())) if (e.kind == Alarms.NAG) nags.add(e);
        assertEquals(1, nags.size());
        assertEquals(at("2026-10-17 19:00:00"), nags.get(0).at);
    }

    @Test
    public void dayOffPassAndMainSwitchSilenceEverything() throws Exception {
        setClock("2026-10-17 10:00:00");
        seedNag(Rules.LOCK);
        MonthStore.Month m = MonthStore.get(ctx()).load(YearMonth.of(2026, 10));
        m.rest |= MonthStore.bit(17);
        MonthStore.get(ctx()).save(m);
        for (Alarms.Event e : Alarms.events(ctx(), LocalDate.now())) assertEquals(Alarms.MIDNIGHT, e.kind);
        assertNull(Engine.lockAt(ctx(), LocalDateTime.parse("2026-10-17T21:00")));
    }

    @Test
    public void timerTicksWhenItEnds() throws Exception {
        setClock("2026-10-17 18:00:00");
        seedNag(Rules.NAG);
        Alarms.startTimer(ctx(), 1);
        assertEquals(1, Alarms.timerHabit(ctx()));
        assertEquals(at("2026-10-17 18:20:00"), Alarms.timerEnd(ctx()), 2000);
        Notification ongoing = notifications().get(0);
        assertTrue((ongoing.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        setClock("2026-10-17 18:21:00");
        Alarms.onAlarm(ctx());
        assertTrue(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(1).isDone(17));
        assertEquals(0, Alarms.timerEnd(ctx()));
        assertEquals("Τέλος χρόνου. Τικαρίστηκε.", notifications().get(0).extras.getString(Notification.EXTRA_TEXT));
    }

    @Test
    public void sundayReview() throws Exception {
        setClock("2026-10-18 20:00:00");
        seed(YearMonth.of(2026, 10), "{\"habits\":[" + habit(1, "Γυμναστική", 12, 13, 14, 15, 16, 17, 18) + ","
                + habit(2, "Διάβασμα", 12, 14, 18) + "],\"rest\":[13],\"passes\":[15]}");
        String[] r = Alarms.review(ctx(), LocalDate.of(2026, 10, 18));
        assertEquals("Η εβδομάδα σου: 9 από 12", r[0]);
        assertEquals("Γυμναστική: 6 από 6\nΔιάβασμα: 3 από 6\nΠάσα: 1\nΠιο πίσω έμεινε: Διάβασμα.", r[1]);
        boolean due = false;
        for (Alarms.Event e : Alarms.events(ctx(), LocalDate.now())) due |= e.kind == Alarms.REVIEW && e.at == at("2026-10-18 21:00:00");
        assertTrue(due);
    }
}
