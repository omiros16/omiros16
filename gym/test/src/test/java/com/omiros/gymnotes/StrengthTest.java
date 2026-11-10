package com.omiros.gymnotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** What counts as a personal record ({@link Strength}), and how sets are written out ({@link Fmt}). */
public class StrengthTest {
    static Session session(long day, double... weightReps) {
        Session s = new Session(day);
        for (int i = 0; i < weightReps.length; i += 2) s.add(weightReps[i], (int) weightReps[i + 1]);
        return s;
    }

    static Strength.Bests bests(double... weightReps) {
        Strength.Bests b = new Strength.Bests();
        for (int i = 0; i < weightReps.length; i += 2) b.add(weightReps[i], (int) weightReps[i + 1]);
        return b;
    }

    @Test
    public void estimatedOneRepMaxIsEpleyCappedAtTwelveReps() {
        assertEquals(100, Strength.e1rm(100, 1), 1e-9);
        assertEquals(100 * (1 + 5 / 30.0), Strength.e1rm(100, 5), 1e-9);
        assertEquals(50 * 1.4, Strength.e1rm(50, 12), 1e-9);
        // Long sets do not keep inflating the estimate.
        assertEquals(Strength.e1rm(50, 12), Strength.e1rm(50, 20), 1e-9);
        assertEquals(Strength.e1rm(50, 12), Strength.e1rm(50, 100), 1e-9);
        // Bodyweight and empty sets have no estimate.
        assertEquals(0, Strength.e1rm(0, 15), 1e-9);
        assertEquals(0, Strength.e1rm(50, 0), 1e-9);
    }

    @Test
    public void volumeIsNeverAPersonalRecord() {
        // The user's own example: 1 kg for a hundred reps must not beat real work.
        Strength.Bests b = bests(100, 5, 100, 5, 100, 4);
        assertEquals(0, b.judge(1, 100));
        assertEquals(0, b.judge(20, 50));
        // Not even at a light weight that was used before (a warm-up weight).
        b.add(40, 10);
        assertEquals("warm-up weight, far below 75% of the heaviest", 0, b.judge(40, 30));
    }

    @Test
    public void heavierThanEverIsAWeightRecord() {
        Strength.Bests b = bests(50, 10, 50, 9, 50, 8);
        // 52.5 x 9 is heavier and its estimate (68.25) also beats 50 x 10 (66.67).
        assertEquals(Strength.WEIGHT | Strength.E1RM, b.judge(52.5, 9));
        // 52.5 x 6 is heavier, but as an estimate (63.0) it is below the best: weight record only.
        assertEquals(Strength.WEIGHT, b.judge(52.5, 6));
        // The same weight and reps again: nothing.
        assertEquals(0, b.judge(50, 10));
        assertEquals(0, b.judge(50, 9));
    }

    @Test
    public void moreRepsAtAWorkingWeightIsARepRecord() {
        Strength.Bests b = bests(50, 10, 50, 9, 47.5, 8);
        // 11 reps at 50 beats the 10 done there: more reps, and a better estimate.
        assertEquals(Strength.REPS | Strength.E1RM, b.judge(50, 11));
        // 47.5 x 11: more than ever at 47.5 or heavier (10), but the estimate (64.9) stays below 66.67.
        assertEquals(Strength.REPS, b.judge(47.5, 11));
        // 47.5 x 10 only matches what was done with a heavier weight.
        assertEquals(0, b.judge(47.5, 10));
        // A weight never used before has no record to beat.
        assertEquals(0, b.judge(45, 12));
        assertEquals(0, b.judge(48, 10));
        // Long sets: the estimate stops growing at 12 reps, the rep record does not.
        b.add(50, 12);
        assertEquals(Strength.REPS, b.judge(50, 14));
    }

    @Test
    public void repRecordsNeedAtLeastThreeQuartersOfTheHeaviest() {
        Strength.Bests b = bests(100, 5, 80, 8, 75, 8, 70, 10);
        assertEquals(Strength.REPS, b.judge(80, 9));
        assertEquals("75 is exactly 75% of 100", Strength.REPS, b.judge(75, 9));
        assertEquals("70 is below 75% of 100", 0, b.judge(70, 20));
    }

    @Test
    public void bodyweightCountsReps() {
        Strength.Bests b = bests(0, 10, 0, 8);
        assertEquals(Strength.REPS, b.judge(0, 11));
        assertEquals(0, b.judge(0, 10));
        assertEquals(10, b.repsAtOrAbove(0));
        // Adding weight to a bodyweight exercise is a weight record (no estimate without a weighted base).
        assertEquals(Strength.WEIGHT, b.judge(5, 5));
    }

    @Test
    public void theFirstSessionIsTheBaseline() {
        assertEquals("no history, no record", 0, new Strength.Bests().judge(100, 5));

        List<Session> h = new ArrayList<>();
        h.add(session(1, 50, 8, 55, 8, 60, 6));     // first day: heavier set after set, still no PR
        h.add(session(8, 50, 8, 55, 8, 60, 6));     // the same again
        h.add(session(15, 60, 7, 62.5, 5));         // more reps at 60, then heavier
        h.add(session(22, 40, 15));                  // a light day
        Strength.Bests b = Strength.markPrs(h);
        assertEquals(0, h.get(0).flags);
        assertEquals(0, h.get(1).flags);
        // 60 x 7 = 74.0 beats 60 x 6 = 72.0 (reps + estimate); 62.5 x 5 = 72.9 is heavier but no better estimate.
        assertEquals(Strength.REPS | Strength.E1RM | Strength.WEIGHT, h.get(2).flags);
        assertEquals(0, h.get(3).flags);
        assertEquals(62.5, b.maxWeight, 1e-9);
        assertEquals(5, b.maxWeightReps);
        assertEquals(60, b.bestE1rmWeight, 1e-9);
        assertEquals(7, b.bestE1rmReps);
        assertEquals(60 * (1 + 7 / 30.0), b.bestE1rm, 1e-9);
    }

    @Test
    public void laterSetsOfTheDayAreJudgedAgainstTheEarlierOnes() {
        List<Session> h = new ArrayList<>();
        h.add(session(1, 50, 10));
        h.add(session(8, 52.5, 8, 52.5, 8));
        Strength.markPrs(h);
        // The first 52.5 x 8 is the weight record; the second one only repeats it.
        assertEquals(Strength.WEIGHT, h.get(1).flags);
        Strength.Bests b = bests(50, 10);
        assertEquals(Strength.WEIGHT, b.judge(52.5, 8));
        b.add(52.5, 8);
        assertEquals(0, b.judge(52.5, 8));
        assertEquals(Strength.REPS | Strength.E1RM, b.judge(52.5, 9));
    }

    @Test
    public void tinyDifferencesAreNotRecords() {
        // 100 x 6 and 90 x 10 both estimate 120.0 kg.
        Strength.Bests b = bests(100, 6);
        assertEquals("the same estimate is no record", 0, b.judge(90, 10) & Strength.E1RM);
        assertTrue((b.judge(92.5, 10) & Strength.E1RM) != 0);
        // Weights compare in hundredths of a kg, so 52.5 typed twice is one weight.
        Strength.Bests c = bests(52.5, 8);
        assertEquals(0, c.judge(52.5000001, 8));
        assertEquals(5250, Strength.centi(52.5));
        assertEquals(125, Strength.centi(1.25));
    }

    @Test
    public void copiesAreIndependent() {
        Strength.Bests b = bests(50, 10);
        Strength.Bests c = b.copy();
        c.add(60, 10);
        assertEquals(50, b.maxWeight, 1e-9);
        assertEquals(60, c.maxWeight, 1e-9);
        assertEquals(-1, b.repsAtOrAbove(60));
        assertEquals(10, c.repsAtOrAbove(60));
        assertEquals(1, b.sets);
    }

    @Test
    public void sessionsGrowAndIgnoreInvalidRatings() {
        Session s = new Session(1);
        for (int i = 0; i < 20; i++) s.add(50, 10 + i, i % 5);
        assertEquals(20, s.n);
        assertEquals(29, s.reps[19]);
        assertEquals(Coach.EASY, s.feel[1]);
        assertEquals(Coach.HARD, s.feel[3]);
        assertEquals("4 is not a rating", 0, s.feel[4]);
        assertTrue(s.rated());
        assertTrue(s.weighted());
        assertEquals(29, s.maxReps());
        assertEquals(Strength.e1rm(50, 12), s.bestE1rm(), 1e-9);
        assertFalse(session(1, 0, 10).weighted());
        assertFalse(session(1, 50, 10).rated());
    }

    @Test
    public void setsAreWrittenGroupedByWeight() {
        assertEquals("50 kg × 10, 10 · 55 kg × 8 · BW × 12", Fmt.sets(session(1, 50, 10, 50, 10, 55, 8, 0, 12)));
        assertEquals("52.5 kg × 9", Fmt.sets(session(1, 52.5, 9)));
        assertEquals("1.25 kg × 20", Fmt.sets(session(1, 1.25, 20)));
        assertEquals("50", Fmt.kg(50));
        assertEquals("52.5", Fmt.kg(52.5));
        assertEquals("1.25", Fmt.kg(1.25));
        assertEquals("0.05", Fmt.kg(0.05));
        assertEquals("BW", Fmt.load(0));
        assertEquals("68.3", Fmt.num1(68.25));
        assertEquals("66", Fmt.num1(66.0));
        assertEquals("+1.6", Fmt.signed1(1.58));
        assertEquals("−2", Fmt.signed1(-2));
        assertEquals("PR βάρους, PR 1RM", Fmt.badges(Strength.WEIGHT | Strength.E1RM));
        assertEquals("PR reps", Fmt.badges(Strength.REPS));
    }

    @Test
    public void typedNumbersAreParsedLeniently() {
        assertEquals(52.5, Fmt.parseKg("52,5"), 1e-9);
        assertEquals(52.5, Fmt.parseKg(" 52.5 "), 1e-9);
        assertEquals(0, Fmt.parseKg(""), 1e-9);
        assertEquals(0, Fmt.parseKg("abc"), 1e-9);
        assertEquals(0, Fmt.parseKg("-5"), 1e-9);
        assertEquals(2000, Fmt.parseKg("99999"), 1e-9);
        assertEquals(12, Fmt.parseReps("12"));
        assertEquals(0, Fmt.parseReps(""));
        assertEquals(0, Fmt.parseReps("1.5"));
        assertEquals(1000, Fmt.parseReps("5000"));
    }

    @Test
    public void namesMatchWhateverTheCaseOrAccents() {
        assertEquals(Db.key("Πιέσεις"), Db.key("ΠΙΕΣΕΙΣ"));
        assertEquals(Db.key("Πιέσεις"), Db.key("  πιεσεις "));
        assertEquals(Db.key("Lat  pulldown"), Db.key("lat PULLDOWN"));
        assertFalse(Db.key("Lat pulldown").equals(Db.key("Lat pull down")));
        assertEquals("Lat pulldown", Db.clean("  Lat   pulldown "));
    }
}
