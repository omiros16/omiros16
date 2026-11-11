package com.omiros.habits;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.time.LocalDate;
import java.time.YearMonth;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w400dp-h880dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoreTest extends Base {
    static final YearMonth OCT = YearMonth.of(2026, 10);

    @Test
    public void firstRunAddTickAndSetARule() throws Exception {
        setClock("2026-10-17 18:00:00");
        Activity a = main();
        assertShows(a, "Ξεκίνα από τα μικρά");
        snap(a, "1-empty");
        addHabit(a, "Διάβασμα 20'");
        addHabit(a, "Γυμναστική");
        assertShows(a, "0 από 2");
        row(a, "Γυμναστική").performClick();
        idle();
        assertTrue(doneToday(a, "Γυμναστική"));
        assertTrue(read(OCT).contains("\"name\":\"Γυμναστική\",\"days\":[17],\"since\":17"));

        // Long press, then deadline 20:00, "Πρήξιμο", 20' timer.
        android.widget.PopupMenu pm = longPress(a, "Διάβασμα 20'");
        choose(pm, "Ώρα, ειδοποιήσεις, κλείδωμα");
        AlertDialog rules = dialog();
        clickInDialog("Χωρίς ώρα");
        TimePickerDialog tp = (TimePickerDialog) ShadowDialog.getLatestDialog();
        tp.updateTime(20, 0);
        tp.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        click(rules, "Πρήξιμο");
        click(rules, "20'");
        assertNotNull(find(rules.getWindow().getDecorView(), "Ειδοποιήσεις 2 ώρες πριν, 1 ώρα πριν και μετά κάθε 15 λεπτά ως την ώρα."));
        snapDialog(a, rules, "2-rules");
        rules.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        Rules.Rule r = Rules.get(ctx()).on(LocalDate.now()).rule(1);
        assertEquals(Rules.NAG, r.level);
        assertEquals(20 * 60, r.minute);
        assertEquals(20, r.timer);

        // The hero now counts down to it; the timer button starts the countdown.
        assertShows(a, "2:00");
        assertShows(a, "μένουν, ως τις 20:00");
        snap(a, "3-hero");
        click(a, "Χρονόμετρο 20'");
        assertShows(a, "Τικάρεται μόλις τελειώσει.");
        snap(a, "4-timer");
        click(a, "Έγινε τώρα");
        assertTrue(doneToday(a, "Διάβασμα 20'"));
        assertEquals(0, Alarms.timerEnd(ctx()));
        assertShows(a, "Έγιναν όλες");
    }

    static void click(AlertDialog d, String text) {
        View v = find(d.getWindow().getDecorView(), text);
        assertNotNull(text, v);
        while (!v.isClickable()) v = (View) v.getParent();
        v.performClick();
        idle();
    }

    /** Screenshot with a given dialog on top (not the latest one). */
    static void snapDialog(Activity a, AlertDialog d, String name) throws Exception {
        idle();
        View root = a.getWindow().getDecorView();
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        root.draw(c);
        c.drawColor(0x80000000);
        View dv = d.getWindow().getDecorView();
        c.translate((bmp.getWidth() - dv.getWidth()) / 2f, (bmp.getHeight() - dv.getHeight()) / 2f);
        dv.draw(c);
        save(bmp, name);
    }

    @Test
    public void lockedHeroAndPass() throws Exception {
        setClock("2026-10-17 20:30:00");
        seed(OCT, month(habit(1, "Διάβασμα 20'", range(1, 16, 3, 9)), habit(2, "Γυμναστική", range(1, 17, 4, 11))));
        seedRules(rules(rule(1, 20 * 60, Rules.LOCK, true, 20)));
        Activity a = main();
        assertShows(a, "Κλειδωμένο");
        assertShows(a, "έληξε και δεν έχει γίνει");
        assertShows(a, "Πάσο (3)");
        snap(a, "5-locked");
        click(a, "Πάσο (3)");
        dialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        idle();
        assertNull(Engine.lockAt(ctx(), java.time.LocalDateTime.now()));
        assertTrue(read(OCT).contains("\"passes\":[17]"));
        assertNull(find(a, "Κλειδωμένο"));
    }

    @Test
    public void dayOffKeepsTheStreakAndSilences() throws Exception {
        setClock("2026-10-17 10:00:00");
        seed(OCT, month(habit(1, "Διάβασμα 20'", 14, 15, 16), habit(2, "Γυμναστική", 15, 16)));
        seedRules(rules(rule(1, 20 * 60, Rules.LOCK, true, 0)));
        Activity a = main();
        click(a, "Ρεπό σήμερα (μένουν 4)");
        dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertShows(a, "Ρεπό");
        assertTrue(read(OCT).contains("\"rest\":[17]"));
        snap(a, "6-rest");
        // Tomorrow the streak carries on across the day off.
        setClock("2026-10-18 09:00:00");
        Engine.setDone(ctx(), 1, LocalDate.now(), true);
        assertEquals(4, MonthStore.get(ctx()).streak(1, LocalDate.now(), MonthStore.get(ctx()).load(OCT)));
        assertNull(Engine.lockAt(ctx(), java.time.LocalDateTime.parse("2026-10-17T21:00")));
    }

    @Test
    public void deletingAHabitWithRulesWaitsUntilTomorrow() throws Exception {
        setClock("2026-10-17 10:00:00");
        seed(OCT, month(habit(1, "Διάβασμα 20'"), habit(2, "Γυμναστική")));
        seedRules(rules(rule(1, 20 * 60, Rules.LOCK, true, 0)));
        ActivityController<? extends Activity> c = launch("MainActivity");
        Activity a = c.get();
        choose(longPress(a, "Διάβασμα 20'"), "Διαγραφή");
        dialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        idle();
        assertShows(a, "Διαγράφεται αύριο");
        assertTrue(Rules.get(ctx()).on(LocalDate.now()).rule(1).active());

        choose(longPress(a, "Γυμναστική"), "Διαγραφή");
        dialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        idle();
        assertNull("no rules: gone at once", find(a, "Γυμναστική"));

        setClock("2026-10-18 08:00:00");
        c.pause().resume();
        idle();
        assertNull(find(a, "Διάβασμα 20'"));
        assertFalse(Rules.get(ctx()).on(LocalDate.now()).rule(1).active());
        assertEquals("{\"habits\":[]}", read(OCT));
    }

    @Test
    public void ticksStartOverEveryDayCountsEveryMonth() throws Exception {
        setClock("2026-10-17 21:00:00");
        seed(OCT, month(habit(1, "Γυμναστική", range(1, 17, 4, 7, 11, 14)), habit(2, "Διάβασμα", 16)));
        ActivityController<? extends Activity> c = launch("MainActivity");
        Activity a = c.get();
        assertTrue(doneToday(a, "Γυμναστική"));
        assertShows(a, "13 φορές, σερί 3");
        setClock("2026-10-18 08:00:00");
        c.pause().resume();
        idle();
        assertShows(a, "Κυριακή 18");
        assertFalse(doneToday(a, "Γυμναστική"));
        assertShows(a, "13 φορές, σερί 3");
        setClock("2026-11-01 09:00:00");
        c.pause().resume();
        idle();
        assertShows(a, "Κυριακή 1");
        assertShows(a, "Νοέμβριος");
        assertShows(a, "Ο Οκτώβριος έκλεισε στο 22%. Δες τον");
        assertFalse(file(YearMonth.of(2026, 11)).exists());
        row(a, "Διάβασμα").performClick();
        idle();
        assertTrue(read(YearMonth.of(2026, 11)).contains(habit(2, "Διάβασμα", 1)));
        // The first tick of a month that had no file yet shows on screen, and survives the next edit.
        assertTrue(doneToday(a, "Διάβασμα"));
        addHabit(a, "Νερό");
        assertTrue(read(YearMonth.of(2026, 11)).contains(habit(2, "Διάβασμα", 1)));
    }

    @Test
    public void historyAndCalendar() throws Exception {
        setClock("2026-11-02 12:00:00");
        seed(OCT, month(habit(1, "Γυμναστική", range(1, 31, 4, 11, 18, 25))));
        seed(YearMonth.of(2026, 11), month(habit(1, "Γυμναστική", 1)));
        Activity a = main();
        clickByDescription(a, "Προηγούμενοι μήνες");
        assertShows(a, "Οκτώβριος");
        assertShows(a, "27 από 31 μέρες");
        snap(a, "7-history");
        row(a, "Γυμναστική").performClick();
        idle();
        snap(a, "8-calendar");
        clickInDialog("4");
        assertTrue(read(OCT).contains("\"days\":[1,2,3,4,"));
        dialog().dismiss();
        a.onBackPressed();
        idle();
        assertShows(a, "Δευτέρα 2");
    }

    @Test
    public void testMessageSaysWhatReallyHappened() throws Exception {
        setClock("2026-10-17 10:00:00");
        seedRules(rules().replace("\"phones\":[]", "\"phones\":[\"6900000000\"]").replace("\"emails\":[]", "\"emails\":[\"a@example.gr\"]"));
        Activity s = launch("SettingsActivity").get();
        click(s, "Στείλε δοκιμαστικό μήνυμα");
        assertTrue(org.robolectric.shadows.ShadowToast.showedToast("SMS: δεν έχει δοθεί η άδεια για SMS"));
        assertTrue(org.robolectric.shadows.ShadowToast.showedToast("Email: δεν έχει ρυθμιστεί το Gmail"));
        assertEquals("a test never waits in the outbox", "[]", Engine.state(ctx()).getString("outbox", "[]"));
    }

    @Test
    public void settingsScreen() throws Exception {
        setClock("2026-10-17 10:00:00");
        Activity s = launch("SettingsActivity").get();
        assertShows(s, "Ειδοποιήσεις και κλειδώματα");
        assertShows(s, "Δεν έχει ενεργοποιηθεί στο κινητό ακόμα.");
        assertShows(s, "Πάσα τον Οκτώβριο: μένουν 3 από 3. Ρεπό: μένουν 4 από 4.");
        snap(s, "9-settings");
        click(s, "Ενεργοποίηση κλειδώματος");
        snap(s, "10-lock-setup");
        dialog().dismiss();
        android.widget.Switch main = null;
        java.util.ArrayList<View> found = new java.util.ArrayList<>();
        s.getWindow().getDecorView().findViewsWithText(found, "Ειδοποιήσεις και κλειδώματα", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View v : found) if (v instanceof android.widget.Switch) main = (android.widget.Switch) v;
        assertNotNull(main);
        main.performClick();
        idle();
        assertShows(s, "Κλείνουν από αύριο. Σήμερα ισχύουν ακόμα.");
        assertTrue(Rules.get(ctx()).on(LocalDate.now()).on);
        assertFalse(Rules.get(ctx()).wanted().on);
    }
}
