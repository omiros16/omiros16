package com.omiros.habits;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSmsManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w400dp-h880dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class LockTest extends Base {
    static final String INSTAGRAM = "com.instagram.android";

    static void seedLock(boolean all) throws Exception {
        seed(YearMonth.of(2026, 10), month(habit(1, "Διάβασμα 20'"), habit(2, "Γυμναστική")));
        seedRules(rules(rule(1, 20 * 60, Rules.LOCK, all, 20)).replace("\"phones\":[]", "\"phones\":[\"6900000000\"]"));
    }

    static void allowSms() {
        shadowOf(ctx()).grantPermissions(Manifest.permission.SEND_SMS);
        shadowOf(ctx().getPackageManager()).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_MESSAGING, true);
    }

    static LockService service() {
        Settings.Secure.putString(ctx().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                PKG + "/" + PKG + ".LockService");
        LockService s = Robolectric.setupService(LockService.class);
        s.onServiceConnected();
        return s;
    }

    static Intent front(LockService s, String pkg) {
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        e.setPackageName(pkg);
        s.onAccessibilityEvent(e);
        return shadowOf(s).getNextStartedActivity();
    }

    @Test
    public void locksEveryAppButTheExceptionsAfterTheDeadline() throws Exception {
        setClock("2026-10-17 19:59:00");
        seedLock(true);
        LockService s = service();
        assertTrue(LockService.isOn(ctx()));
        assertNull("before the deadline", front(s, INSTAGRAM));

        setClock("2026-10-17 20:01:00");
        Engine.Lock lock = Engine.lockAt(ctx(), LocalDateTime.now());
        assertEquals(1, lock.habits.size());
        Intent shown = front(s, INSTAGRAM);
        assertNotNull(shown);
        assertEquals(PKG + ".LockActivity", shown.getComponent().getClassName());
        assertNotNull("any app, not only the list", front(s, "com.android.chrome"));
        assertNull("exception", front(s, "com.google.android.apps.maps"));
        assertNull("calls never lock", front(s, "com.android.dialer"));
        assertNull("settings never lock", front(s, "com.android.settings"));
        assertNull("this app never locks", front(s, PKG));

        // Doing the habit unlocks.
        Engine.setDone(ctx(), 1, LocalDate.now(), true);
        assertNull(Engine.lockAt(ctx(), LocalDateTime.now()));
        assertNull(front(s, INSTAGRAM));
    }

    @Test
    public void listLockOnlyBlocksMyList() throws Exception {
        setClock("2026-10-17 20:30:00");
        seedLock(false);
        LockService s = service();
        assertNotNull(front(s, INSTAGRAM));
        assertNull(front(s, "com.android.chrome"));
    }

    @Test
    public void passUnlocksTellsFriendsAndRunsOut() throws Exception {
        setClock("2026-10-17 20:30:00");
        seedLock(true);
        allowSms();
        assertTrue(Engine.usePass(ctx()));
        assertNull(Engine.lockAt(ctx(), LocalDateTime.now()));
        ShadowSmsManager.TextMultipartParams sms = shadowOf(ctx().getSystemService(android.telephony.SmsManager.class)).getLastSentMultipartTextMessageParams();
        assertEquals("6900000000", sms.getDestinationAddress());
        assertTrue(String.join("", sms.getParts()), String.join("", sms.getParts()).startsWith("Habits: Χρησιμοποίησα πάσο σήμερα, Σάββατο 17/10"));
        MonthStore.Month m = MonthStore.get(ctx()).load(YearMonth.of(2026, 10));
        assertEquals(2, Engine.passesLeft(m));
        assertFalse("one pass a day is enough", Engine.usePass(ctx()));
        m.passes = MonthStore.bit(1) | MonthStore.bit(2) | MonthStore.bit(3);
        assertEquals(0, Engine.passesLeft(m));
    }

    @Test
    public void dayOffOnlyBeforeTheFirstDeadline() throws Exception {
        setClock("2026-10-17 20:01:00");
        seedLock(true);
        assertEquals("Το ρεπό δηλώνεται πριν από το πρώτο όριο της μέρας (20:00).",
                Engine.restBlocker(ctx(), LocalDateTime.now()));
        setClock("2026-10-17 10:00:00");
        assertNull(Engine.restBlocker(ctx(), LocalDateTime.now()));
        Engine.setRest(ctx(), LocalDate.now(), true);
        assertNull(Engine.lockAt(ctx(), LocalDateTime.parse("2026-10-17T21:00")));
        assertEquals("Είναι ήδη ρεπό σήμερα.", Engine.restBlocker(ctx(), LocalDateTime.now()));
    }

    @Test
    public void avoidHabitBlocksItsHoursAndTicksItselfAtMidnight() throws Exception {
        setClock("2026-10-17 10:00:00");
        seed(YearMonth.of(2026, 10), month(habit(4, "Χωρίς social media")));
        seedRules(rules("\"4\":{\"avoid\":true,\"block\":true,\"from\":540,\"until\":1380,\"timer\":0}"));
        Engine.sync(ctx());
        LockService s = service();
        assertNotNull(front(s, INSTAGRAM));
        assertNull(front(s, "com.android.chrome"));
        setClock("2026-10-17 23:30:00");
        assertNull(front(s, INSTAGRAM));

        setClock("2026-10-18 00:00:10");
        Engine.sync(ctx());
        assertTrue(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(4).isDone(17));
    }

    @Test
    public void avoidHabitIsNotTickedWhenTheLockWasOff() throws Exception {
        setClock("2026-10-17 10:00:00");
        seed(YearMonth.of(2026, 10), month(habit(4, "Χωρίς social media")));
        seedRules(rules("\"4\":{\"avoid\":true,\"block\":true,\"from\":540,\"until\":1380,\"timer\":0}"));
        Engine.sync(ctx());
        setClock("2026-10-18 00:00:10");
        Engine.sync(ctx());
        assertFalse(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(4).isDone(17));
    }

    @Test
    public void loosenedYesterdayStillReportsYesterday() throws Exception {
        setClock("2026-10-17 21:00:00");
        seedLock(true);
        allowSms();
        Engine.sync(ctx());
        Rules.Doc want = Rules.get(ctx()).wanted().copy();
        want.habits.get(1).level = Rules.NONE;
        assertTrue(Rules.get(ctx()).edit(LocalDate.now(), want));
        setClock("2026-10-18 00:00:02");
        // An app switch reaches the lock before the midnight alarm closes the day.
        assertNull(Engine.lockAt(ctx(), LocalDateTime.now()));
        Engine.sync(ctx());
        ShadowSmsManager.TextMultipartParams sms = shadowOf(ctx().getSystemService(android.telephony.SmsManager.class)).getLastSentMultipartTextMessageParams();
        assertNotNull(sms);
        assertEquals("Habits: Σάββατο 17/10 δεν έκανα: Διάβασμα 20' (ως 20:00).", String.join("", sms.getParts()));
    }

    @Test
    public void missedLockIsReportedAtMidnight() throws Exception {
        setClock("2026-10-17 21:00:00");
        seedLock(true);
        allowSms();
        Engine.sync(ctx());
        setClock("2026-10-18 00:00:10");
        Engine.sync(ctx());
        ShadowSmsManager.TextMultipartParams sms = shadowOf(ctx().getSystemService(android.telephony.SmsManager.class)).getLastSentMultipartTextMessageParams();
        assertNotNull(sms);
        assertEquals("Habits: Σάββατο 17/10 δεν έκανα: Διάβασμα 20' (ως 20:00).", String.join("", sms.getParts()));
    }

    @Test
    public void lockScreenDoneUnlocks() throws Exception {
        setClock("2026-10-17 20:30:00");
        seedLock(true);
        android.app.Activity a = launch("LockActivity").get();
        assertShows(a, "Κλειδωμένο");
        assertShows(a, "Έπρεπε να γίνει ως τις 20:00.");
        assertShows(a, "Πάσο ως τα μεσάνυχτα (μένουν 3)");
        snap(a, "lock-screen");
        click(a, "Έγινε");
        assertTrue(a.isFinishing());
        assertTrue(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(1).isDone(17));
    }
}
