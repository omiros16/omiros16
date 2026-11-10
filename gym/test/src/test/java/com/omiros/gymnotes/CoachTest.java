package com.omiros.gymnotes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The rules of {@link Coach}, one situation per test. Sessions are a week apart unless a test is
 * about breaks, and the advice is asked for a week after the last one.
 */
public class CoachTest {
    static final int E = Coach.EASY;
    static final int N = Coach.NORMAL;
    static final int H = Coach.HARD;

    static Session s(long day, double weight, int... reps) {
        Session s = new Session(day);
        for (int r : reps) s.add(weight, r);
        return s;
    }

    static Session rated(long day, double weight, int[] reps, int[] feel) {
        Session s = new Session(day);
        for (int i = 0; i < reps.length; i++) s.add(weight, reps[i], feel[i]);
        return s;
    }

    static int[] r(int... v) {
        return v;
    }

    static List<Session> list(Session... sessions) {
        return new ArrayList<>(Arrays.asList(sessions));
    }

    /** {@code count} identical weekly sessions starting on day 0. */
    static List<Session> repeat(int count, double weight, int... reps) {
        List<Session> h = new ArrayList<>();
        for (int i = 0; i < count; i++) h.add(s(7L * i, weight, reps));
        return h;
    }

    static Coach.Settings range(int low, int high) {
        Coach.Settings st = new Coach.Settings();
        st.low = low;
        st.high = high;
        return st;
    }

    static Coach.Settings range(int low, int high, double step) {
        Coach.Settings st = range(low, high);
        st.step = step;
        return st;
    }

    static Coach.Advice next(List<Session> h, Coach.Settings st) {
        return Coach.next(h, st, h.get(h.size() - 1).day + 7);
    }

    static void assertAdvice(Coach.Advice a, int kind, double weight, int... reps) {
        String got = kindName(a.kind) + " " + Fmt.kg(a.weight) + " × " + Arrays.toString(a.reps) + "  (" + a.reason + ")";
        assertEquals(got, kindName(kind), kindName(a.kind));
        assertEquals(got, weight, a.weight, 1e-9);
        assertArrayEquals(got, reps, a.reps);
    }

    static String kindName(int kind) {
        return kind == Coach.UP ? "UP" : kind == Coach.MORE_REPS ? "MORE_REPS" : kind == Coach.SAME ? "SAME"
                : kind == Coach.DOWN ? "DOWN" : "?" + kind;
    }

    static void assertContains(String text, String part) {
        assertTrue("«" + part + "» not in: " + text, text.contains(part));
    }

    // ---------------------------------------------------------------- double progression

    @Test
    public void noHistoryNoAdvice() {
        assertNull(Coach.next(new ArrayList<Session>(), null, 100));
    }

    @Test
    public void belowTheTopEverySetGetsOneMoreRep() {
        Coach.Advice a = next(list(s(0, 50, 10, 9, 8)), null);
        assertAdvice(a, Coach.MORE_REPS, 50, 11, 10, 9);
        assertEquals(8, a.low);
        assertEquals(12, a.high);
        assertTrue(a.autoRange);
        assertEquals(2.5, a.step, 1e-9);
        assertTrue(a.autoStep);
        assertEquals(0, a.dayKind);
        assertEquals("Μείνε στα 50 kg και βάλε +1 rep σε κάθε set. Όταν πιάσεις τα 12 σε όλα, ανεβαίνεις 2.5 kg.", a.reason);
    }

    @Test
    public void repsStopAtTheTopOfTheRange() {
        assertAdvice(next(list(s(0, 50, 12, 12, 11)), null), Coach.MORE_REPS, 50, 12, 12, 12);
    }

    @Test
    public void theTopOnEverySetEarnsOneWeightStep() {
        Coach.Advice a = next(list(s(0, 50, 12, 12, 12)), null);
        // 50 x 12 is worth 52.5 x 10 by the same estimate.
        assertAdvice(a, Coach.UP, 52.5, 10, 10, 10);
        assertContains(a.reason, "Έπιασες τα 12 reps σε όλα τα sets με 50 kg");
        assertContains(a.reason, "ανέβα 2.5 kg");
    }

    @Test
    public void theUsersRangeAndStepWin() {
        Coach.Advice a = next(list(s(0, 50, 12, 12, 12)), range(10, 15, 5));
        assertAdvice(a, Coach.MORE_REPS, 50, 13, 13, 13);
        assertEquals(10, a.low);
        assertEquals(15, a.high);
        assertFalse(a.autoRange);
        assertEquals(5, a.step, 1e-9);
        assertFalse(a.autoStep);
        assertContains(a.reason, "Όταν πιάσεις τα 15 σε όλα, ανεβαίνεις 5 kg.");
        // A range that makes no sense is ignored.
        Coach.Advice b = next(list(s(0, 50, 12, 12, 12)), range(12, 8));
        assertTrue(b.autoRange);
        assertEquals(8, b.low);
        assertEquals(12, b.high);
    }

    // ---------------------------------------------------------------- how each set felt

    @Test
    public void eachSetMovesByHowItFelt() {
        Coach.Advice a = next(list(rated(0, 50, r(10, 10, 9), r(E, N, H))), range(8, 12));
        assertAdvice(a, Coach.MORE_REPS, 50, 12, 11, 9);
        assertEquals("Μείνε στα 50 kg: +2 reps στα χαλαρά sets, +1 στα μέτρια, ίδια στα «στο όριο». "
                + "Όταν πιάσεις τα 12 σε όλα, ανεβαίνεις 2.5 kg.", a.reason);
    }

    @Test
    public void aSetThatWasNotRatedCountsAsNormal() {
        assertAdvice(next(list(rated(0, 50, r(10, 10), r(0, N))), range(8, 12)), Coach.MORE_REPS, 50, 11, 11);
    }

    @Test
    public void whenEverySetWasAtTheLimitOnlyTheWeakestGetsARep() {
        Coach.Advice a = next(list(rated(0, 50, r(10, 9, 8), r(H, H, H))), range(8, 12));
        assertAdvice(a, Coach.MORE_REPS, 50, 10, 9, 9);
        assertContains(a.reason, "Όλα ήταν στο όριο: κράτα τα ίδια και βάλε +1 rep μόνο στο πιο αδύναμο set.");
        // A set already at the top does not count against the others.
        Coach.Advice b = next(list(rated(0, 50, r(12, 10, 9), r(N, H, H))), range(8, 12));
        assertAdvice(b, Coach.MORE_REPS, 50, 12, 10, 10);
        assertContains(b.reason, "Όσα έμειναν κάτω από τα 12 ήταν στο όριο");
    }

    @Test
    public void easySetsCountWithTheRepsTheyHadLeft() {
        // 11, 10, 10 with 3+ left on each would have been 12 everywhere: time to go up.
        Coach.Advice a = next(list(rated(0, 50, r(11, 10, 10), r(E, E, E))), range(8, 12));
        assertAdvice(a, Coach.UP, 52.5, 10, 10, 10);
        assertContains(a.reason, "Με όσα reps σου περίσσευαν, θα έπιανες τα 12 σε όλα τα sets με 50 kg");
        // One easy set does not carry the others.
        assertAdvice(next(list(rated(0, 50, r(11, 10, 10), r(E, N, N))), range(8, 12)), Coach.MORE_REPS, 50, 12, 11, 11);
    }

    @Test
    public void wayOverTheTopSkipsAStep() {
        Coach.Advice a = next(list(s(0, 50, 14, 14, 14)), range(8, 12));
        assertAdvice(a, Coach.UP, 55, 10, 10, 10);
        assertContains(a.reason, "πολύ πάνω από το εύρος σου: ανέβα 5 kg");
        Coach.Advice b = next(list(rated(0, 50, r(12, 12, 12), r(E, E, E))), range(8, 12));
        assertAdvice(b, Coach.UP, 55, 10, 10, 10);
        assertContains(b.reason, "και ήταν χαλαρά: ανέβα 5 kg");
        // Two steps would be +12.5% at 40 kg: one step only.
        assertAdvice(next(list(s(0, 40, 14, 14, 14)), range(8, 12)), Coach.UP, 42.5, 11, 11, 11);
    }

    // ---------------------------------------------------------------- good and bad days

    static List<Session> steadyThenLast(Session last) {
        return list(s(0, 50, 10, 10, 9), s(7, 50, 11, 10, 9), s(14, 50, 11, 10, 10), last);
    }

    @Test
    public void aWeakDayRepeatsTheTargetInsteadOfLoweringIt() {
        // The target for the last day was 50 x 12, 11, 11; it went 7% below the usual level.
        Coach.Advice a = next(steadyThenLast(s(21, 50, 8, 7, 6)), range(8, 12));
        assertAdvice(a, Coach.SAME, 50, 12, 11, 11);
        assertEquals(-1, a.dayKind);
        assertEquals(0.927, a.form, 0.001);
        assertEquals("Η τελευταία φορά ήταν 7% κάτω από τα συνηθισμένα σου, μάλλον κακή μέρα. "
                + "Μην κατεβάσεις κιλά: ξαναδοκίμασε τον ίδιο στόχο.", a.reason);
    }

    @Test
    public void goingHeavierThanTheTargetAndFailingReturnsToTheTarget() {
        Coach.Advice a = next(steadyThenLast(s(21, 55, 5, 4, 4)), range(8, 12));
        assertAdvice(a, Coach.SAME, 50, 12, 11, 11);
        assertEquals(-1, a.dayKind);
        assertContains(a.reason, "Με τα 55 kg η απόδοση ήταν 6% κάτω από τα συνηθισμένα σου");
        assertContains(a.reason, "Γύρνα στον στόχο που είχες.");
    }

    @Test
    public void aSmallDipIsAnOrdinaryDay() {
        // 11, 10, 10 -> 10, 10, 9 is 2.4% down: inside the normal spread, so the usual +1.
        Coach.Advice a = next(steadyThenLast(s(21, 50, 10, 10, 9)), range(8, 12));
        assertAdvice(a, Coach.MORE_REPS, 50, 11, 11, 10);
        assertEquals(0, a.dayKind);
    }

    @Test
    public void fewerRepsOnSetsRatedEasyAreNotAWeakDay() {
        List<Session> h = repeat(3, 50, 10, 10, 10);
        h.add(rated(21, 50, r(8, 8, 8), r(E, E, E)));
        Coach.Advice a = next(h, range(8, 12));
        assertAdvice(a, Coach.MORE_REPS, 50, 10, 10, 10);
        assertEquals(0, a.dayKind);
        // The same reps at the limit are a weak day: the earlier target (11, 11, 11) again.
        h.set(3, rated(21, 50, r(8, 8, 8), r(H, H, H)));
        Coach.Advice b = next(h, range(8, 12));
        assertAdvice(b, Coach.SAME, 50, 11, 11, 11);
        assertEquals(-1, b.dayKind);
    }

    @Test
    public void aLighterDayOnPurposeIsNotAWeakDay() {
        Coach.Advice a = next(steadyThenLast(s(21, 45, 10, 10, 10)), range(8, 12));
        assertAdvice(a, Coach.MORE_REPS, 45, 11, 11, 11);
        assertEquals(0, a.dayKind);
    }

    @Test
    public void aGreatDayEarnsOneStepNotTwo() {
        List<Session> h = repeat(3, 50, 9, 9, 8);
        h.add(s(21, 50, 14, 14, 14));
        Coach.Advice a = next(h, range(8, 12));
        // Without the history this is "way over the top" and two steps (see wayOverTheTopSkipsAStep).
        assertAdvice(a, Coach.UP, 52.5, 11, 11, 11);
        assertEquals(1, a.dayKind);
        assertContains(a.reason, "Πολύ καλή μέρα (+13% από τα συνηθισμένα σου), οπότε ανεβαίνεις ένα βήμα, όχι παραπάνω.");
    }

    @Test
    public void aGreatDayBelowTheTopKeepsItsReps() {
        List<Session> h = repeat(3, 50, 8, 8, 7);
        h.add(s(21, 50, 11, 10, 10));
        Coach.Advice a = next(h, range(8, 12));
        assertAdvice(a, Coach.MORE_REPS, 50, 11, 10, 10);
        assertEquals(1, a.dayKind);
        assertContains(a.reason, "Πολύ καλή μέρα (+8% από τα συνηθισμένα σου). Κράτα τα ίδια και ξαναπιάσε αυτά τα νούμερα");
    }

    // ---------------------------------------------------------------- stalls

    @Test
    public void fourSessionsWithoutProgressGoTenPercentLighter() {
        assertAdvice(next(repeat(3, 50, 10, 9, 8), range(8, 12)), Coach.MORE_REPS, 50, 11, 10, 9);
        Coach.Advice a = next(repeat(4, 50, 10, 9, 8), range(8, 12));
        assertAdvice(a, Coach.DOWN, 45, 10, 9, 8);
        assertContains(a.reason, "4 φορές στα 50 kg χωρίς πρόοδο (από 10,9,8 σε 10,9,8 reps). Κατέβα στα 45 kg (−10%)");
    }

    @Test
    public void oneBetterSessionInBetweenIsProgress() {
        List<Session> h = list(s(0, 50, 10, 9, 8), s(7, 50, 11, 10, 8), s(14, 50, 10, 9, 8), s(21, 50, 10, 9, 8));
        assertAdvice(next(h, range(8, 12)), Coach.MORE_REPS, 50, 11, 10, 9);
    }

    @Test
    public void oneBadDayIsNotAStall() {
        // Without the weak-day rule these four sessions at 50 kg would read as "no progress".
        List<Session> h = repeat(3, 50, 10, 9, 8);
        h.add(s(21, 50, 5, 4, 4));
        Coach.Advice a = next(h, range(8, 12));
        assertAdvice(a, Coach.SAME, 50, 11, 10, 9);
        assertEquals(-1, a.dayKind);
    }

    @Test
    public void setsRatedEasyAreNeverAStall() {
        List<Session> h = repeat(3, 50, 10, 9, 8);
        h.add(rated(21, 50, r(10, 9, 8), r(N, N, E)));
        assertAdvice(next(h, range(8, 12)), Coach.MORE_REPS, 50, 11, 10, 10);
    }

    @Test
    public void noSecondDeloadWhileTheFirstIsStillRecent() {
        List<Session> h = repeat(4, 50, 10, 9, 8);
        for (int i = 4; i < 8; i++) h.add(s(7L * i, 45, 10, 9, 8));
        assertAdvice(next(h, range(8, 12)), Coach.MORE_REPS, 45, 11, 10, 9);
    }

    @Test
    public void aDeloadThatWentBadlyIsRepeatedNotDeepened() {
        List<Session> h = repeat(4, 50, 10, 9, 8);
        assertAdvice(next(h, range(8, 12)), Coach.DOWN, 45, 10, 9, 8);
        // The lighter session itself was far below the range: a weak day, so the same again.
        h.add(s(28, 45, 5, 5, 4));
        Coach.Advice a = next(h, range(8, 12));
        assertAdvice(a, Coach.SAME, 45, 10, 9, 8);
        assertEquals(-1, a.dayKind);
        assertContains(a.reason, "μάλλον κακή μέρα");
    }

    @Test
    public void adviceToGoLighterStandsIfItWasIgnoredOnAWeakDay() {
        List<Session> h = repeat(4, 50, 10, 9, 8);
        h.add(s(28, 50, 6, 5, 5));
        Coach.Advice a = next(h, range(8, 12));
        assertAdvice(a, Coach.DOWN, 45, 10, 9, 8);
        assertContains(a.reason, "χωρίς πρόοδο");
    }

    // ---------------------------------------------------------------- breaks

    @Test
    public void breaks() {
        List<Session> h = list(s(0, 50, 10, 9, 8));
        assertAdvice(Coach.next(h, range(8, 12), 13), Coach.MORE_REPS, 50, 11, 10, 9);
        Coach.Advice two = Coach.next(h, range(8, 12), 15);
        assertAdvice(two, Coach.SAME, 50, 10, 9, 8);
        assertEquals("Πέρασαν 15 μέρες από την τελευταία φορά. Κάνε πρώτα ξανά τα ίδια.", two.reason);
        assertAdvice(Coach.next(h, range(8, 12), 27), Coach.SAME, 50, 10, 9, 8);
        Coach.Advice four = Coach.next(h, range(8, 12), 28);
        assertAdvice(four, Coach.DOWN, 45, 10, 9, 8);
        assertContains(four.reason, "Πέρασαν 28 μέρες");
        assertContains(four.reason, "10% πιο ελαφριά");
    }

    @Test
    public void afterABreakTheWeightDoesNotGoUpYet() {
        Coach.Advice a = Coach.next(list(s(0, 50, 12, 12, 12)), range(8, 12), 15);
        assertAdvice(a, Coach.SAME, 50, 12, 12, 12);
        assertContains(a.reason, "Κάνε πρώτα ξανά τα ίδια και μετά ανεβαίνεις.");
    }

    // ---------------------------------------------------------------- range and weight

    @Test
    public void tooHeavyForTheRangeComesBackIntoIt() {
        Coach.Advice a = next(list(s(0, 60, 4, 4, 3)), range(8, 12));
        assertAdvice(a, Coach.DOWN, 52.5, 8, 8, 7);
        assertEquals("Με τα 60 kg έκανες 4 reps, κάτω από το εύρος σου (8–12). Πήγαινε στα 52.5 kg για περίπου 8 reps "
                + "και ανέβαινε από εκεί.", a.reason);
        // One rep under the range is close enough to build from.
        assertAdvice(next(list(s(0, 60, 7, 6, 6)), range(8, 12)), Coach.MORE_REPS, 60, 8, 7, 7);
    }

    @Test
    public void aBigJumpOnLightWeightsIsEarnedWithRepsFirst() {
        // Dumbbells in 2 kg steps: 6 -> 8 kg is +33%, and 12 reps at 6 kg are worth about 1 at 8 kg.
        Coach.Advice a = next(list(s(0, 6, 12, 12, 12)), range(8, 12, 2));
        assertAdvice(a, Coach.MORE_REPS, 6, 13, 13, 13);
        assertEquals("Το επόμενο βάρος (8 kg) είναι +33%, μεγάλο άλμα για τώρα. Ανέβασε πρώτα τα reps με τα 6 kg "
                + "(έως 16) και μετά ανεβαίνεις.", a.reason);
        assertAdvice(next(list(s(0, 6, 15, 15, 15)), range(8, 12, 2)), Coach.MORE_REPS, 6, 16, 16, 16);
        // At 16 the jump is made, with the few reps it allows.
        assertAdvice(next(list(s(0, 6, 16, 16, 16)), range(8, 12, 2)), Coach.UP, 8, 4, 4, 4);
        // A 1 kg step from 6 kg needs fewer extra reps: 14 predict 7 at 7 kg.
        assertAdvice(next(list(s(0, 6, 13, 13, 13)), range(8, 12, 1)), Coach.MORE_REPS, 6, 14, 14, 14);
        assertAdvice(next(list(s(0, 6, 14, 14, 14)), range(8, 12, 1)), Coach.UP, 7, 7, 7, 7);
    }

    @Test
    public void aWeightJumpOverTenPercentIsOneStepAndEarned() {
        double[] weights = {4, 5, 6, 8, 10, 12.5, 15, 20, 25, 40, 50, 100};
        double[] steps = {0.5, 1, 2, 2.5, 5, 10};
        int[][] ranges = {{5, 8}, {8, 12}, {10, 15}};
        int checked = 0;
        for (double w : weights) {
            for (double step : steps) {
                for (int[] range : ranges) {
                    for (int reps = range[0]; reps <= range[1] + 8; reps++) {
                        for (int feel = 0; feel <= 3; feel++) {
                            Coach.Advice a = next(list(rated(0, w, r(reps, reps, reps), r(feel, feel, feel))),
                                    range(range[0], range[1], step));
                            String what = Fmt.kg(w) + " kg x " + reps + " feel " + feel + " step " + step + " -> "
                                    + Fmt.kg(a.weight) + " x " + Arrays.toString(a.reps);
                            assertTrue(what, a.weight <= w + 2 * step + 1e-9);
                            if (a.weight > w * (1 + Coach.MAX_JUMP) + 1e-9) {
                                assertEquals(what, w + step, a.weight, 1e-9);
                                int predicted = Integer.MAX_VALUE;
                                for (int x : a.reps) predicted = Math.min(predicted, x);
                                assertTrue(what, reps >= range[1] + 4 || predicted >= range[0] - 1);
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 2000);
    }

    @Test
    public void bodyweight() {
        Coach.Advice a = next(list(s(0, 0, 8, 7, 6)), null);
        assertEquals(5, a.low);
        assertEquals(8, a.high);
        assertAdvice(a, Coach.MORE_REPS, 0, 8, 8, 7);
        assertContains(a.reason, "με το σωματικό σου βάρος");
        assertContains(a.reason, "Όταν πιάσεις τα 8 σε όλα, προσθέτεις βάρος.");
        Coach.Advice b = next(list(s(0, 0, 12, 12, 12)), range(8, 12));
        assertAdvice(b, Coach.UP, 2.5, 8, 8, 8);
        assertContains(b.reason, "Πρόσθεσε 2.5 kg (ζώνη ή αλτήρα)");
    }

    @Test
    public void warmUpsAreNotWorkSets() {
        Session day = new Session(0);
        day.add(20, 10);
        day.add(35, 5);
        day.add(50, 10);
        day.add(50, 9);
        day.add(50, 8);
        Coach.Work w = Coach.work(day);
        assertEquals(50, w.top, 1e-9);
        assertArrayEquals(r(10, 9, 8), w.reps);
        assertEquals(3, w.sets);
        assertTrue(Coach.isWarmUp(35, 50));
        assertFalse(Coach.isWarmUp(45, 50));
        assertAdvice(next(list(day), null), Coach.MORE_REPS, 50, 11, 10, 9);
    }

    @Test
    public void backOffSetsAreBroughtUpToTheTopWeight() {
        Session day = new Session(0);
        day.add(60, 8);
        day.add(55, 8);
        day.add(55, 7);
        Coach.Advice a = next(list(day), null);
        assertAdvice(a, Coach.MORE_REPS, 60, 9, 8, 8);
        assertContains(a.reason, "Κάνε και τα 3 sets με 60 kg, με +1 rep σε όσα έκανες ήδη με 60 kg.");
    }

    // ---------------------------------------------------------------- automatic range and step

    @Test
    public void theRangeComesFromTheUsualReps() {
        assertArrayEquals(r(1, 3), Coach.inferRange(list(s(0, 100, 3, 3, 2))));
        assertArrayEquals(r(3, 5), Coach.inferRange(list(s(0, 100, 5, 5, 4))));
        assertArrayEquals(r(5, 8), Coach.inferRange(list(s(0, 80, 6, 6, 6))));
        assertArrayEquals(r(8, 12), Coach.inferRange(list(s(0, 50, 10, 9, 8))));
        assertArrayEquals(r(8, 12), Coach.inferRange(list(s(0, 50, 12, 12, 12))));
        assertArrayEquals(r(10, 15), Coach.inferRange(list(s(0, 30, 14, 13, 13))));
        assertArrayEquals(r(15, 20), Coach.inferRange(list(s(0, 20, 18, 18, 16))));
        assertArrayEquals(r(8, 12), Coach.inferRange(new ArrayList<Session>()));
    }

    @Test
    public void theAutomaticRangeDoesNotCreepUpWithTheReps() {
        List<Session> h = list(s(0, 50, 10, 9, 8), s(7, 50, 11, 10, 9), s(14, 50, 12, 11, 10));
        List<Long> weights = new ArrayList<>(Arrays.asList(5000L));
        Coach.Settings baseline = Coach.withBaseline(null, h.subList(0, 3), weights);
        assertEquals(8, baseline.autoLow);
        assertEquals(12, baseline.autoHigh);
        // Someone who keeps the weight and piles on reps for weeks.
        int[][] later = {{13, 13, 12}, {14, 13, 13}, {14, 14, 13}, {14, 14, 13}, {14, 14, 14}, {14, 14, 14}};
        for (int i = 0; i < later.length; i++) h.add(s(21 + 7L * i, 50, later[i]));
        // From the recent sessions alone the range would have followed them...
        assertArrayEquals(r(10, 15), Coach.inferRange(h));
        assertEquals(10, next(h, null).low);
        // ...with the range of the first sessions they are told to add weight.
        Coach.Advice a = next(h, baseline);
        assertEquals(8, a.low);
        assertEquals(12, a.high);
        assertTrue(a.autoRange);
        assertEquals(Coach.UP, a.kind);
        assertTrue(a.weight > 50);
    }

    static TreeSet<Long> kilos(double... kg) {
        TreeSet<Long> out = new TreeSet<>();
        for (double k : kg) out.add(Strength.centi(k));
        return out;
    }

    @Test
    public void theWeightStepComesFromTheWeightsUsed() {
        assertEquals(2.5, Coach.inferStep(kilos(40, 42.5, 45)), 1e-9);
        assertEquals(2, Coach.inferStep(kilos(10, 12, 14)), 1e-9);
        assertEquals(5, Coach.inferStep(kilos(20, 25, 30)), 1e-9);
        assertEquals(1, Coach.inferStep(kilos(5, 6, 7)), 1e-9);
        assertEquals(1.25, Coach.inferStep(kilos(10, 11.25, 12.5)), 1e-9);
        assertEquals(0.5, Coach.inferStep(kilos(7, 7.5, 8)), 1e-9);
        // One 5 kg gap may be two 2.5 kg steps, or a deload.
        assertEquals(2.5, Coach.inferStep(kilos(20, 25)), 1e-9);
        assertEquals(2.5, Coach.inferStep(kilos(45, 50, 52.5, 55)), 1e-9);
        // Nothing to learn from one weight; bodyweight (0) is not a weight.
        assertEquals(0, Coach.inferStep(kilos(50)), 1e-9);
        assertEquals(0, Coach.inferStep(kilos(0, 50)), 1e-9);
        // Then the default: 2.5 kg, or 1 kg for light weights.
        assertEquals(2.5, Coach.inferStep(list(s(0, 50, 10)), 50), 1e-9);
        assertEquals(1, Coach.inferStep(list(s(0, 6, 10)), 6), 1e-9);
        assertEquals(2.5, Coach.inferStep(list(s(0, 0, 10)), 0), 1e-9);
        // The cached automatic step is what the advice uses.
        Coach.Settings st = Coach.withBaseline(null, list(s(0, 10, 10, 9, 8)), new ArrayList<>(kilos(10, 12, 14)));
        assertEquals(2, st.autoStep, 1e-9);
        Coach.Advice a = next(list(s(0, 14, 10, 9, 8)), st);
        assertEquals(2, a.step, 1e-9);
        assertTrue(a.autoStep);
    }

    @Test
    public void goingLighterStaysOnTheStepGridAndWithinTwentyPercent() {
        assertEquals(45, Coach.lighter(50, 2.5), 1e-9);
        assertEquals(90, Coach.lighter(100, 5), 1e-9);
        assertEquals(20, Coach.lighter(22.5, 2.5), 1e-9);
        assertEquals(17.5, Coach.lighter(20, 2.5), 1e-9);
        assertEquals(9, Coach.lighter(10, 1), 1e-9);
        assertEquals(8, Coach.lighter(10, 2), 1e-9);
        // One 2 kg step down from 6 or 8 kg would be more than 20%: stay.
        assertEquals(8, Coach.lighter(8, 2), 1e-9);
        assertEquals(6, Coach.lighter(6, 2), 1e-9);
        // So a stall or a long break there does not send the weight down.
        assertAdvice(next(repeat(4, 6, 10, 9, 8), range(8, 12, 2)), Coach.MORE_REPS, 6, 11, 10, 9);
        assertAdvice(Coach.next(list(s(0, 6, 10, 9, 8)), range(8, 12, 2), 40), Coach.SAME, 6, 10, 9, 8);
    }

    @Test
    public void repsAtAnotherWeightFollowTheSameEstimate() {
        assertEquals(100, Coach.estimate(100, 1), 1e-9);
        assertEquals(70, Coach.estimate(50, 12), 1e-9);
        assertEquals("unlike the PR estimate, not capped at 12", 50 * (1 + 20 / 30.0), Coach.estimate(50, 20), 1e-9);
        assertEquals(100, Coach.estimate(50, 40), 1e-9);
        assertEquals(10, Coach.predict(50, 12, 52.5));
        assertEquals(10, Coach.predict(50, 10, 50));
        assertEquals(8, Coach.predict(60, 4, 52.5));
        assertEquals("never below one rep", 1, Coach.predict(50, 1, 100));
        assertEquals(10, Coach.predict(50, 10, 0));
    }

    // ---------------------------------------------------------------- learning the person's spread

    /** Sixteen weekly sessions at 100 kg whose best set has these reps (10 reps = the usual level). */
    static List<Session> at100(int... best) {
        List<Session> h = new ArrayList<>();
        for (int i = 0; i < best.length; i++) h.add(s(7L * i, 100, best[i], best[i] - 1, best[i] - 1));
        return h;
    }

    static final int[] STEADY = {10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10};
    static final int[] NOISY = {10, 8, 12, 9, 11, 10, 12, 8, 11, 9, 12, 10, 8, 9, 11, 10};

    @Test
    public void theSpreadStartsFromAnAssumptionAndMovesToThePersonsOwn() {
        // Samples: one per session from the third on, not counting the session being judged.
        int[] sessions = {3, 5, 10, 16};
        int[] samples = {0, 2, 7, 13};
        for (int i = 0; i < sessions.length; i++) {
            Coach.Advice a = next(at100(Arrays.copyOf(STEADY, sessions[i])), range(8, 12));
            int n = samples[i];
            assertEquals(sessions[i] + " sessions", n, a.learned);
            // A perfectly steady person: their own spread is the minimum, 0.5%.
            assertEquals((n * 0.005 + Coach.PRIOR_WEIGHT * Coach.SPREAD_PRIOR) / (n + Coach.PRIOR_WEIGHT), a.spread, 1e-9);
        }
        // Never more than 16 sessions back, however long the history.
        List<Session> longer = repeat(40, 100, 10, 9, 9);
        Coach.Advice a = next(longer, range(8, 12));
        assertEquals(13, a.learned);
        assertEquals(Coach.RECENT, a.sessions);
    }

    @Test
    public void theLimitsOfAWeakOrGreatDayFollowTheSpread() {
        Coach.Advice first = next(at100(10, 10), range(8, 12));
        assertEquals(Coach.SPREAD_PRIOR, first.spread, 1e-9);
        assertEquals(0.9505, first.badLimit, 1e-9);
        assertEquals(1.05775, first.goodLimit, 1e-9);

        Coach.Advice steady = next(at100(STEADY), range(8, 12));
        Coach.Advice noisy = next(at100(NOISY), range(8, 12));
        assertTrue(steady.spread < Coach.SPREAD_PRIOR);
        assertTrue(noisy.spread > Coach.SPREAD_PRIOR);
        // Limits are bounded: a weak day is never closer than 3% nor further than 9% below the usual.
        assertEquals(0.97, steady.badLimit, 1e-9);
        assertEquals(1.04, steady.goodLimit, 1e-9);
        assertTrue(noisy.badLimit < 0.95 && noisy.badLimit >= 0.91);
        assertTrue(noisy.goodLimit > 1.06 && noisy.goodLimit <= 1.10);
    }

    @Test
    public void theSameDipIsAWeakDayOnlyForASteadyPerson() {
        // 8 reps instead of the usual 10: 5% down.
        List<Session> steady = at100(STEADY);
        steady.add(s(7 * 16, 100, 8, 8, 7));
        Coach.Advice a = next(steady, range(8, 12));
        assertEquals(0.95, a.form, 1e-9);
        assertEquals(-1, a.dayKind);
        // (Sixteen identical sessions are also a stall, so what stands is the advice to go lighter.)
        assertEquals(Coach.DOWN, a.kind);

        List<Session> noisy = at100(NOISY);
        noisy.add(s(7 * 16, 100, 8, 8, 7));
        Coach.Advice b = next(noisy, range(8, 12));
        assertEquals(0.95, b.form, 1e-9);
        assertEquals("for them 5% down is an ordinary day", 0, b.dayKind);
        assertAdvice(b, Coach.MORE_REPS, 100, 9, 9, 8);

        // With only five sessions the steady person is not known yet: the standard limit (-4.9%) still catches it...
        List<Session> early = at100(Arrays.copyOf(STEADY, 5));
        early.add(s(35, 100, 8, 8, 7));
        assertEquals(-1, next(early, range(8, 12)).dayKind);
        // ...but a 2.5% dip (9 reps) is ordinary for everyone, steady or not.
        steady.set(16, s(7 * 16, 100, 9, 9, 8));
        assertEquals(0, next(steady, range(8, 12)).dayKind);
    }

    @Test
    public void aWeakDayDoesNotWidenItsOwnLimit() {
        List<Session> h = at100(STEADY);
        Coach.Advice before = next(h, range(8, 12));
        h.add(s(7 * 16, 100, 6, 6, 5));
        Coach.Advice after = next(h, range(8, 12));
        assertEquals(-1, after.dayKind);
        assertEquals(before.badLimit, after.badLimit, 1e-9);
        assertTrue(after.spread <= before.spread + 1e-9);
    }

    /** Someone who always does exactly what they are told, for {@code count} sessions from 50 x 10, 9, 8. */
    static List<Session> obedient(int count, Coach.Settings st) {
        List<Session> h = list(s(0, 50, 10, 9, 8));
        while (h.size() < count) {
            long day = h.get(h.size() - 1).day + 7;
            Coach.Advice a = Coach.next(h, st, day);
            h.add(s(day, a.weight, a.reps));
        }
        return h;
    }

    @Test
    public void afterAWeakDayTheTargetIsExactlyTheOneGivenBefore() {
        Coach.Settings st = range(8, 12, 2.5);
        for (int count : new int[]{3, 4, 6, 9, 13, 17, 18, 22, 30}) {
            List<Session> h = obedient(count, st);
            long day = h.get(h.size() - 1).day + 7;
            Coach.Advice target = Coach.next(h, st, day);
            int[] weak = new int[target.reps.length];
            for (int i = 0; i < weak.length; i++) weak[i] = Math.max(1, target.reps[i] - 4);
            h.add(s(day, target.weight, weak));
            Coach.Advice again = Coach.next(h, st, day + 7);
            String what = count + " sessions, target " + Fmt.target(target) + ", did " + Arrays.toString(weak) + ": "
                    + kindName(again.kind) + " " + Fmt.target(again);
            assertEquals(what, -1, again.dayKind);
            assertEquals(what, Coach.SAME, again.kind);
            assertEquals(what, target.weight, again.weight, 1e-9);
            assertArrayEquals(what, target.reps, again.reps);
        }
    }

    @Test
    public void followingTheAdviceClimbsWithoutEverDropping() {
        List<Session> h = obedient(40, range(8, 12, 2.5));
        double top = 0;
        for (int i = 0; i < h.size(); i++) {
            double w = Coach.work(h.get(i)).top;
            assertTrue("session " + i + ": " + Fmt.sets(h.get(i)), w >= top);
            top = w;
        }
        // 50 x 10, 9, 8 -> 12, 12, 12 takes 5 sessions; then 52.5 x 10 -> 12 takes 3 each time.
        assertEquals("50 kg × 12, 12, 12", Fmt.sets(h.get(4)));
        assertEquals("52.5 kg × 10, 10, 10", Fmt.sets(h.get(5)));
        assertEquals("52.5 kg × 12, 12, 12", Fmt.sets(h.get(7)));
        assertEquals("55 kg × 10, 10, 10", Fmt.sets(h.get(8)));
        assertTrue(top >= 75);
    }
}
