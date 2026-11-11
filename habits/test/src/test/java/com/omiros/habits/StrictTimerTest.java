package com.omiros.habits;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w400dp-h880dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StrictTimerTest extends Base {
    static final YearMonth OCT = YearMonth.of(2026, 10);

    static String strict(int id, int minute, int level, int timer) {
        return "\"" + id + "\":{\"min\":" + minute + ",\"level\":" + level + ",\"all\":true,\"timer\":" + timer + ",\"strict\":true}";
    }

    static void seedStrict() throws Exception {
        seed(OCT, month(habit(1, "Διάβασμα 20'"), habit(2, "Γυμναστική"), habit(3, "Διαλογισμός")));
        seedRules(rules(strict(1, 20 * 60, Rules.LOCK, 20), rule(2, -1, Rules.NONE, true, 0), strict(3, -1, Rules.NONE, 10)));
    }

    static boolean done(int id) {
        return MonthStore.get(ctx()).load(OCT).find(id).isDone(17);
    }

    static List<Notification> notifications() {
        return shadowOf(ctx().getSystemService(NotificationManager.class)).getAllNotifications();
    }

    @Test
    public void tickStartsTheTimerAndOnlyTheTimerTicks() throws Exception {
        setClock("2026-10-17 18:00:00");
        seedStrict();
        Activity a = main();
        // The hero offers only the timer, no plain "Έγινε".
        assertShows(a, "Χρονόμετρο 20'");
        assertNull(find(a, "Έγινε"));
        snap(a, "s1-strict-hero");

        row(a, "Διάβασμα 20'").performClick();
        idle();
        assertFalse(done(1));
        assertEquals(1, Alarms.timerHabit(ctx()));
        long end = Alarms.timerEnd(ctx());
        assertEquals("Χρονόμετρο 20'. Τικάρεται μόλις τελειώσει.", ShadowToast.getTextOfLatestToast());
        assertShows(a, "Τικάρεται μόλις τελειώσει.");
        assertNull("no way round the timer", find(a, "Έγινε τώρα"));
        snap(a, "s2-strict-running");

        // Tapping again neither ticks nor restarts it.
        row(a, "Διάβασμα 20'").performClick();
        idle();
        assertFalse(done(1));
        assertEquals(end, Alarms.timerEnd(ctx()));
        assertEquals("Το χρονόμετρο τρέχει ήδη. Τικάρεται μόλις τελειώσει.", ShadowToast.getTextOfLatestToast());

        // A stray tap on another strict habit does not throw the running timer away.
        row(a, "Διαλογισμός").performClick();
        idle();
        assertEquals(1, Alarms.timerHabit(ctx()));
        assertFalse(done(3));
        assertEquals("Τρέχει το χρονόμετρο για «Διάβασμα 20'». Σταμάτησέ το πρώτα.", ShadowToast.getTextOfLatestToast());

        // A habit without the option still ticks at once, and unticking is always allowed.
        row(a, "Γυμναστική").performClick();
        idle();
        assertTrue(done(2));

        // The calendar cannot tick today either.
        choose(longPress(a, "Διαλογισμός"), "Ημερολόγιο μήνα");
        clickInDialog("17");
        assertFalse(done(3));
        assertEquals("Σήμερα τικάρεται μόνο με το χρονόμετρο.", ShadowToast.getTextOfLatestToast());
        clickInDialog("16");
        assertTrue(MonthStore.get(ctx()).load(OCT).find(3).isDone(16));
        dialog().dismiss();

        setClock("2026-10-17 18:21:00");
        Alarms.onAlarm(ctx());
        assertTrue(done(1));
        assertEquals(0, Alarms.timerEnd(ctx()));
    }

    @Test
    public void notificationWidgetAndLockGoThroughTheTimer() throws Exception {
        setClock("2026-10-17 17:00:00");
        seedStrict();
        Engine.state(ctx()).edit().putLong("last_alarm", AlarmsTest.at("2026-10-17 17:00:00")).commit();
        setClock("2026-10-17 19:16:00");
        Alarms.onAlarm(ctx());
        Notification n = notifications().get(0);
        assertEquals(1, n.actions.length);
        assertEquals("Χρονόμετρο 20'", n.actions[0].title.toString());

        // An old notification's "Έγινε" (or the widget) starts the timer instead of ticking.
        new AlarmReceiver().onReceive(ctx(), new Intent(Alarms.ACTION_DONE).putExtra(Alarms.EXTRA_ID, 1));
        assertFalse(done(1));
        assertEquals(1, Alarms.timerHabit(ctx()));
        new AlarmReceiver().onReceive(ctx(), new Intent(Alarms.ACTION_TOGGLE).putExtra(Alarms.EXTRA_ID, 3));
        assertFalse(done(3));
        assertEquals(1, Alarms.timerHabit(ctx()));
        new AlarmReceiver().onReceive(ctx(), new Intent(Alarms.ACTION_TOGGLE).putExtra(Alarms.EXTRA_ID, 2));
        assertTrue(done(2));

        // Locked at 20:00: the lock screen offers only the timer.
        Alarms.stopTimer(ctx());
        setClock("2026-10-17 20:05:00");
        assertNotNull(Engine.lockAt(ctx(), java.time.LocalDateTime.now()));
        Activity lock = launch("LockActivity").get();
        assertNull(find(lock, "Έγινε"));
        assertShows(lock, "Χρονόμετρο 20'");

        // With the main switch off it is a plain tracker again: "Έγινε" ticks at once.
        seedRules(rules(strict(1, 20 * 60, Rules.LOCK, 20), strict(3, -1, Rules.NONE, 10)).replace("\"on\":true", "\"on\":false"));
        resetSingletons();
        assertNull(Engine.tick(ctx(), 3));
    }

    @Test
    public void turningItOffWaitsUntilTomorrow() throws Exception {
        setClock("2026-10-17 18:00:00");
        seed(OCT, month(habit(1, "Διάβασμα 20'")));
        seedRules(rules(rule(1, -1, Rules.NONE, true, 20)));
        Rules rs = Rules.get(ctx());
        LocalDate today = LocalDate.now();
        assertFalse(rs.on(today).timerOnly(1));

        Rules.Doc want = rs.wanted().copy();
        want.habits.get(1).strict = true;
        assertFalse("stricter: at once", rs.edit(today, want));
        assertTrue(rs.on(today).timerOnly(1));

        want = rs.wanted().copy();
        want.habits.get(1).strict = false;
        want.habits.get(1).timer = 10;
        assertTrue("looser: tomorrow", rs.edit(today, want));
        assertTrue(rs.on(today).timerOnly(1));
        assertEquals(20, rs.on(today).rule(1).timer);
        assertFalse(rs.on(today.plusDays(1)).timerOnly(1));
        assertEquals(10, rs.on(today.plusDays(1)).rule(1).timer);
    }

    @Test
    public void rulesDialogOffersIt() throws Exception {
        setClock("2026-10-17 18:00:00");
        seed(OCT, month(habit(1, "Διάβασμα 20'")));
        Activity a = main();
        choose(longPress(a, "Διάβασμα 20'"), "Ώρα, ειδοποιήσεις, κλείδωμα");
        AlertDialog d = dialog();
        assertNull(find(d.getWindow().getDecorView(), "Μόνο με χρονόμετρο"));
        CoreTest.click(d, "20'");
        CoreTest.click(d, "Μόνο με χρονόμετρο");
        assertNotNull(find(d.getWindow().getDecorView(), "Το τικ ξεκινά το χρονόμετρο. Τικάρεται μόνο όταν τελειώσει."));
        CoreTest.snapDialog(a, d, "s3-strict-rules");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(Rules.get(ctx()).on(LocalDate.now()).timerOnly(1));
    }

    @Test
    public void anyTimerLength() throws Exception {
        setClock("2026-10-17 18:00:00");
        seed(OCT, month(habit(1, "Διάβασμα")));
        Activity a = main();
        choose(longPress(a, "Διάβασμα"), "Ώρα, ειδοποιήσεις, κλείδωμα");
        AlertDialog rules = dialog();
        CoreTest.click(rules, "Άλλο");
        AlertDialog ask = dialog();
        assertNotSame(rules, ask);
        // Out of range: refused, nothing changes.
        findEdit(ask.getWindow().getDecorView()).setText("0");
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals("Από 1 ως 240 λεπτά.", ShadowToast.getTextOfLatestToast());
        assertNotNull(find(rules.getWindow().getDecorView(), "Άλλο"));

        CoreTest.click(rules, "Άλλο");
        ask = dialog();
        findEdit(ask.getWindow().getDecorView()).setText("13");
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertNull(find(rules.getWindow().getDecorView(), "Άλλο"));
        assertNotNull(find(rules.getWindow().getDecorView(), "13'"));
        assertNotNull("strict choice shows for a custom length", find(rules.getWindow().getDecorView(), "Μόνο με χρονόμετρο"));
        CoreTest.snapDialog(a, rules, "s4-custom-timer");
        rules.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(13, Rules.get(ctx()).on(LocalDate.now()).rule(1).timer);

        // Reopened, the custom length is the selected chip and can be changed again.
        choose(longPress(a, "Διάβασμα"), "Ώρα, ειδοποιήσεις, κλείδωμα");
        rules = dialog();
        CoreTest.click(rules, "13'");
        ask = dialog();
        assertEquals("13", findEdit(ask.getWindow().getDecorView()).getText().toString());
        findEdit(ask.getWindow().getDecorView()).setText("5");
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        rules.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(5, Rules.get(ctx()).on(LocalDate.now()).rule(1).timer);

        Alarms.startTimer(ctx(), 1);
        assertEquals(AlarmsTest.at("2026-10-17 18:05:00"), Alarms.timerEnd(ctx()), 2000);
    }

    @Test
    public void aTapOnTheDotsIsATapOnTheHabit() throws Exception {
        setClock("2026-10-17 18:00:00");
        seed(OCT, month(habit(1, "Γυμναστική", 15, 16)));
        Activity a = main();
        DotStrip strip = findStrip(a.getWindow().getDecorView());
        assertNotNull(strip);
        assertFalse(strip.isClickable());
        int[] at = new int[2];
        strip.getLocationInWindow(at);
        float x = at[0] + strip.getWidth() * 0.3f;
        float y = at[1] + strip.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        View root = a.getWindow().getDecorView();
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0));
        idle();
        Object latest = ShadowDialog.getLatestDialog();
        assertTrue("no calendar", latest == null || !((android.app.Dialog) latest).isShowing());
        assertTrue(doneToday(a, "Γυμναστική"));
    }

    static DotStrip findStrip(View v) {
        if (v instanceof DotStrip) return (DotStrip) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                DotStrip s = findStrip(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }
}
