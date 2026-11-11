package com.omiros.habits;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RulesTest extends Base {
    static Rules.Rule lock(int minute) {
        Rules.Rule r = new Rules.Rule();
        r.minute = minute;
        r.level = Rules.LOCK;
        r.timer = 20;
        return r;
    }

    @Test
    public void stricterAtOnceLooserFromTomorrow() throws Exception {
        setClock("2026-10-17 10:00:00");
        LocalDate today = LocalDate.now();
        Rules rules = Rules.get(ctx());
        Rules.Doc w = rules.wanted().copy();
        w.habits.put(1, lock(20 * 60));
        assertFalse("a new lock applies at once", rules.edit(today, w));
        assertEquals(Rules.LOCK, rules.on(today).rule(1).level);

        w = rules.wanted().copy();
        w.habits.get(1).level = Rules.NAG;
        w.habits.get(1).minute = 21 * 60;
        w.habits.get(1).all = false;
        assertTrue("loosening waits", rules.edit(today, w));
        Rules.Rule t = rules.on(today).rule(1);
        assertEquals(Rules.LOCK, t.level);
        assertEquals(20 * 60, t.minute);
        assertTrue(t.all);
        assertEquals(Rules.NAG, rules.wanted().rule(1).level);

        // A stricter edit on top: today takes the earlier time at once, the waiting change keeps it too.
        w = rules.wanted().copy();
        w.habits.get(1).minute = 19 * 60;
        rules.edit(today, w);
        assertEquals(19 * 60, rules.on(today).rule(1).minute);
        assertEquals(Rules.LOCK, rules.on(today).rule(1).level);

        Rules.Rule tomorrow = rules.on(today.plusDays(1)).rule(1);
        assertEquals(Rules.NAG, tomorrow.level);
        assertEquals(19 * 60, tomorrow.minute);
        assertFalse(rules.hasPending());
    }

    @Test
    public void mainSwitchOffWaitsOnIsImmediate() throws Exception {
        setClock("2026-10-17 10:00:00");
        LocalDate today = LocalDate.now();
        Rules rules = Rules.get(ctx());
        Rules.Doc w = rules.wanted().copy();
        w.on = false;
        assertTrue(rules.edit(today, w));
        assertTrue(rules.on(today).on);
        assertFalse(rules.wanted().on);
        w = rules.wanted().copy();
        w.on = true;
        assertFalse(rules.edit(today, w));
        assertTrue(rules.on(today.plusDays(1)).on);
    }

    @Test
    public void listsAndFriends() throws Exception {
        setClock("2026-10-17 10:00:00");
        LocalDate today = LocalDate.now();
        Rules rules = Rules.get(ctx());
        Rules.Doc w = rules.wanted().copy();
        w.block.add("com.instagram.android");
        w.phones.add("6900000000");
        assertFalse("more blocked apps and more friends: at once", rules.edit(today, w));

        w = rules.wanted().copy();
        w.block.remove("com.instagram.android");
        w.phones.clear();
        w.allow.add("com.whatsapp");
        assertTrue(rules.edit(today, w));
        Rules.Doc now = rules.on(today);
        assertTrue(now.block.contains("com.instagram.android"));
        assertTrue(now.phones.contains("6900000000"));
        assertFalse(now.allow.contains("com.whatsapp"));
        Rules.Doc tomorrow = rules.on(today.plusDays(1));
        assertFalse(tomorrow.block.contains("com.instagram.android"));
        assertTrue(tomorrow.phones.isEmpty());
        assertTrue(tomorrow.allow.contains("com.whatsapp"));
    }

    @Test
    public void yesterdayKeepsItsRulesWhenTodaysAreAskedFirst() throws Exception {
        seedRules(rules(rule(1, 20 * 60, Rules.LOCK, true, 0)));
        Rules rs = Rules.get(ctx());
        LocalDate day = LocalDate.of(2026, 10, 17);
        Rules.Doc want = rs.wanted().copy();
        want.habits.get(1).level = Rules.NONE;
        assertTrue(rs.edit(day, want));
        // Just past midnight the lock service asks for today's rules before the day is closed.
        assertEquals(Rules.NONE, rs.on(day.plusDays(1)).rule(1).level);
        assertEquals(Rules.LOCK, rs.on(day).rule(1).level);
        resetSingletons();
        assertEquals("kept across a restart", Rules.LOCK, Rules.get(ctx()).on(day).rule(1).level);
        assertEquals(Rules.NONE, Rules.get(ctx()).on(day.plusDays(1)).rule(1).level);
    }

    @Test
    public void avoidWindowOnlyWidensToday() throws Exception {
        setClock("2026-10-17 10:00:00");
        LocalDate today = LocalDate.now();
        Rules rules = Rules.get(ctx());
        Rules.Doc w = rules.wanted().copy();
        Rules.Rule a = new Rules.Rule();
        a.avoid = true;
        a.block = true;
        a.from = 9 * 60;
        a.until = 23 * 60;
        w.habits.put(4, a);
        assertFalse(rules.edit(today, w));
        w = rules.wanted().copy();
        w.habits.get(4).from = 12 * 60;
        w.habits.get(4).until = 24 * 60;
        assertTrue(rules.edit(today, w));
        Rules.Rule t = rules.on(today).rule(4);
        assertEquals(9 * 60, t.from);
        assertEquals(24 * 60, t.until);
    }

    @Test
    public void pendingSurvivesRestart() throws Exception {
        setClock("2026-10-17 10:00:00");
        LocalDate today = LocalDate.now();
        Rules rules = Rules.get(ctx());
        Rules.Doc w = rules.wanted().copy();
        w.habits.put(1, lock(20 * 60));
        rules.edit(today, w);
        w = rules.wanted().copy();
        w.habits.remove(1);
        assertTrue(rules.edit(today, w));
        resetSingletons();
        Rules again = Rules.get(ctx());
        assertTrue(again.hasPending());
        assertEquals(Rules.LOCK, again.on(today).rule(1).level);
        assertFalse(again.on(today.plusDays(1)).rule(1).active());
    }

    @Test
    public void defaultExceptionsOnFirstRun() throws Exception {
        Rules.Doc d = Rules.get(ctx()).wanted();
        assertTrue(d.on);
        assertTrue(d.review);
        assertTrue(d.block.isEmpty());
    }
}
