package com.omiros.gymnotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * {@link Coach} followed for two years by simulated people whose real strength is known, so the
 * advice can be judged against it. Nothing here checks a single example; it checks that across
 * {@value #LIFTERS} people x 6 ways of training x {@value #SESSIONS} sessions the advice is never
 * unsafe or contradictory, stays close to what each person can really do, and gets better with
 * per-set ratings.
 *
 * <p>The model: a true one-rep max that grows, plateaus, drops over a 40-day break and recovers;
 * a personal day-to-day spread (±1.5%, ±3% or ±5%) with a few clearly bad and clearly great days;
 * a little fatigue from set to set; equipment in 2.5 kg, 5 kg or 2 kg steps. Reps follow the same
 * Epley relation the coach uses, so differences come from the advice, not from a formula mismatch.
 * Each person does what they are told as far as they can, and either stops at the target or goes
 * on to the top of the range; they rate no set, about half, or all, and a quarter of the ratings
 * are off by one. Everything is seeded: the numbers are the same on every run.
 */
public class CoachSimTest {
    static final int LIFTERS = 300;
    static final int SESSIONS = 109;
    static final int BREAK_AT = 60;

    static final int STOP_AT_TARGET = 0;
    static final int TO_THE_TOP = 1;
    static final int RATE_NONE = 0;
    static final int RATE_HALF = 1;
    static final int RATE_ALL = 2;
    static final String[] EFFORT = {"stops at the target", "goes to the top"};
    static final String[] RATES = {"rates nothing", "rates half", "rates all"};
    static final double[] SIGMA = {0.015, 0.03, 0.05};
    static final String[] GEAR = {"2.5 kg steps", "5 kg steps", "2 kg dumbbells"};

    /** One person: everything that does not depend on how they train. */
    static final class Person {
        final int id;
        final double sigma;
        final int gear;
        final double step;
        final double fatigue;
        final int sets;
        final double[] strength = new double[SESSIONS];   // true 1RM on an ordinary day
        final double[] dayFactor = new double[SESSIONS];  // that day's form, 1.0 = ordinary
        final int[] special = new int[SESSIONS];          // -1 a clearly bad day, +1 a clearly great one
        final long[] day = new long[SESSIONS];

        Person(int id) {
            this.id = id;
            Random r = new Random(1000 + id);
            sigma = SIGMA[id % 3];
            int g = id % 5;
            gear = g < 3 ? 0 : g == 3 ? 1 : 2;
            step = gear == 0 ? 2.5 : gear == 1 ? 5 : 2;
            double s = gear == 2 ? 11 + r.nextDouble() * 14 : 45 + r.nextDouble() * 95;
            double gain = 0.002 + r.nextDouble() * 0.003;
            fatigue = 0.02 + r.nextDouble() * 0.025;
            sets = 2 + r.nextInt(3);
            long d = 0;
            double beforeBreak = 0;
            for (int t = 0; t < SESSIONS; t++) {
                if (t > 0) d += t == BREAK_AT ? 40 : 3 + r.nextInt(5);
                if (t > 0 && t < 40) s *= 1 + gain;                       // steady progress
                else if (t == BREAK_AT) {                                 // 40 days off
                    beforeBreak = s;
                    s *= 0.92;
                } else if (t > BREAK_AT && s < beforeBreak) s *= 1.014;   // it comes back quickly
                else if (t > BREAK_AT) s *= 1 + gain * 0.4;               // then slower progress
                strength[t] = s;
                day[t] = d;
                double f = Math.max(-2.5, Math.min(2.5, r.nextGaussian())) * sigma;
                double roll = r.nextDouble();
                boolean lastWasSpecial = t > 0 && special[t - 1] != 0;
                if (t >= 3 && !lastWasSpecial && roll < 0.06) {
                    special[t] = -1;
                    f = -(0.09 + r.nextDouble() * 0.06);
                } else if (t >= 3 && !lastWasSpecial && roll < 0.10) {
                    special[t] = 1;
                    f = 0.07 + r.nextDouble() * 0.03;
                }
                dayFactor[t] = 1 + f;
            }
        }

        /** The weight this person can actually load closest to {@code w}. */
        double load(double w) {
            return Math.max(step, Math.round(w / step) * step);
        }

        /** Reps possible on set {@code set} at {@code w} kg with this 1RM. */
        int can(double oneRm, double w, int set) {
            double s = oneRm * (1 - fatigue * set);
            if (w > s) return 0;
            return Math.max(1, (int) Math.floor(30 * (s / w - 1) + 1e-9));
        }
    }

    /** What the simulation measured for one way of training (or one group of people). */
    static final class Stats {
        int advices;
        int unsafe;
        int flipFlop;
        int cutAfterBadDay;
        int cutByStall;
        int cutByRange;
        int cutFlagged;
        int loweredAnyway;
        int badDays;
        double trackingSum;
        int trackingN;
        int over3;
        int over6;
        int rightN;
        int right;
        double progressSum;
        int people;
        final List<String> examples = new ArrayList<>();

        double tracking() {
            return trackingSum / Math.max(1, trackingN);
        }

        double over3() {
            return 100.0 * over3 / Math.max(1, trackingN);
        }

        double over6() {
            return 100.0 * over6 / Math.max(1, trackingN);
        }

        double right() {
            return 100.0 * right / Math.max(1, rightN);
        }

        double progress() {
            return progressSum / Math.max(1, people);
        }

        void note(String what) {
            if (examples.size() < 12) examples.add(what);
        }
    }

    /** Bad-day detection with the learned limits against the fixed starting limits. */
    static final class Detect {
        final int[][] learned = new int[2][2];   // [truth][flagged]
        final int[][] fixed = new int[2][2];

        static double balanced(int[][] m) {
            double tpr = m[1][1] / (double) Math.max(1, m[1][0] + m[1][1]);
            double tnr = m[0][0] / (double) Math.max(1, m[0][0] + m[0][1]);
            return 50 * (tpr + tnr);
        }
    }

    static final Stats[][] BY_STYLE = new Stats[2][3];
    static final Stats[] BY_GEAR = new Stats[3];
    static final Detect[][] DETECT = new Detect[3][2];   // [sigma][early, late]
    static final Stats ALL = new Stats();
    static String report;

    @BeforeClass
    public static void simulate() throws IOException {
        for (Stats[] row : BY_STYLE) for (int i = 0; i < row.length; i++) row[i] = new Stats();
        for (int i = 0; i < BY_GEAR.length; i++) BY_GEAR[i] = new Stats();
        for (Detect[] row : DETECT) for (int i = 0; i < row.length; i++) row[i] = new Detect();
        for (int id = 0; id < LIFTERS; id++) {
            Person p = new Person(id);
            for (int effort = 0; effort < 2; effort++) {
                for (int rate = 0; rate < 3; rate++) run(p, effort, rate);
            }
        }
        report = report();
        System.out.println(report);
        File out = new File("target/coach-sim.txt");
        if (out.getParentFile().isDirectory()) Files.write(out.toPath(), report.getBytes(StandardCharsets.UTF_8));
    }

    static void run(Person p, int effort, int rate) {
        Stats[] to = {ALL, BY_STYLE[effort][rate], BY_GEAR[p.gear]};
        List<Session> h = new ArrayList<>();
        List<Long> weights = new ArrayList<>();
        Coach.Advice previous = null;
        double firstWeight = 0;
        for (int t = 0; t < SESSIONS; t++) {
            double today = p.strength[t] * p.dayFactor[t];
            Coach.Advice a = null;
            double w;
            int[] target;
            if (t == 0) {
                // They start by themselves, somewhere around ten reps.
                w = p.load(p.strength[0] / (1 + 11 / 30.0));
                firstWeight = w;
                target = new int[p.sets];
                Arrays.fill(target, 10);
            } else {
                // Exactly what Db.advice does: the last sessions, with the baseline cached on the exercise.
                Coach.Settings st = Coach.withBaseline(null, h.subList(0, Math.min(3, h.size())), weights);
                List<Session> recent = h.subList(Math.max(0, h.size() - Coach.LOAD), h.size());
                a = Coach.next(recent, st, p.day[t]);
                w = p.load(a.weight);
                target = a.reps;
                judge(p, t, h, a, previous, to, st, effort, rate);
                if (t >= 5 && t != BREAK_AT) detect(p, t - 1, h.get(h.size() - 1), a);
            }
            Session s = perform(p, t, today, w, target, a == null ? 12 : a.high, effort, rate);
            h.add(s);
            Coach.workWeights(s, weights);
            previous = a;
        }
        double gained = Coach.work(h.get(h.size() - 1)).top / firstWeight;
        double real = p.strength[SESSIONS - 1] / p.strength[0];
        for (Stats st : to) {
            st.progressSum += gained / real;
            st.people++;
        }
    }

    /**
     * Session {@code t} as this person does it with {@code oneRm} that day. The random part of the
     * ratings depends only on who, how and when, so the same session can be replayed with another
     * strength (what would have happened on an ordinary day).
     */
    static Session perform(Person p, int t, double oneRm, double w, int[] target, int high, int effort, int rate) {
        Random r = new Random(((77L * p.id + 13 * effort + rate) << 8) + t);
        Session s = new Session(p.day[t]);
        for (int i = 0; i < target.length; i++) {
            int can = p.can(oneRm, w, i);
            int reps = Math.min(effort == STOP_AT_TARGET ? target[i] : Math.max(target[i], high), can);
            int feel = rating(r, rate, can - reps);
            if (reps > 0) s.add(w, reps, feel);
        }
        if (s.n == 0) s.add(w, 1, rating(r, rate, 0));
        return s;
    }

    /** How the set felt, from the reps that were left; a quarter of the answers are one off. */
    static int rating(Random r, int rate, int left) {
        boolean answer = rate == RATE_ALL || (rate == RATE_HALF && r.nextBoolean());
        boolean wrong = r.nextDouble() < 0.25;
        boolean up = r.nextBoolean();
        if (!answer) return 0;
        int feel = left >= 3 ? Coach.EASY : left >= 1 ? Coach.NORMAL : Coach.HARD;
        if (!wrong) return feel;
        if (feel == Coach.NORMAL) return up ? Coach.EASY : Coach.HARD;
        return Coach.NORMAL;
    }

    /** Checks the advice {@code a} for session {@code t} against what the person can really do. */
    static void judge(Person p, int t, List<Session> h, Coach.Advice a, Coach.Advice previous, Stats[] to,
            Coach.Settings st, int effort, int rate) {
        Session last = h.get(h.size() - 1);
        Coach.Work lw = Coach.work(last);
        long gap = p.day[t] - p.day[t - 1];
        String what = "person " + p.id + " (" + GEAR[p.gear] + ", ±" + Math.round(p.sigma * 1000) / 10.0 + "%) session " + t
                + ": did " + Fmt.sets(last) + " -> " + CoachTest.kindName(a.kind) + " " + Fmt.target(a)
                + "  [1RM " + Fmt.num1(p.strength[t]) + "]";

        // Never more than +10% at once, unless it is one step on coarse equipment that was earned
        // with reps far over the range, or one whose predicted reps still fit the range.
        boolean unsafe = false;
        if (a.weight > lw.top * (1 + Coach.MAX_JUMP) + 1e-9 && lw.top > 0) {
            int least = Integer.MAX_VALUE;
            for (int x : lw.reps) least = Math.min(least, x);
            int predicted = Integer.MAX_VALUE;
            for (int x : a.reps) predicted = Math.min(predicted, x);
            boolean oneStep = Strength.centi(a.weight) == Strength.centi(lw.top + a.step);
            unsafe = !oneStep || (least < a.high + 4 && predicted < a.low - 1);
        }
        // Up one session and straight back down the next, although the person did what was asked.
        boolean flipFlop = previous != null && previous.kind == Coach.UP && a.kind == Coach.DOWN && gap < Coach.BREAK_DAYS
                && p.special[t - 1] == 0 && Strength.centi(lw.top) == Strength.centi(p.load(previous.weight));
        // One clearly bad day must never be the reason the weight goes down: lower than both the
        // target and what was lifted, when the same session on an ordinary day would have kept it.
        boolean isolated = p.special[t - 1] == -1 && (t < 2 || p.special[t - 2] != -1) && gap < Coach.BREAK_DAYS;
        boolean lowered = isolated && previous != null && a.weight < Math.min(lw.top, previous.weight) - 1e-9;
        boolean cut = false;
        if (lowered) {
            List<Session> ordinary = new ArrayList<>(h.subList(Math.max(0, h.size() - Coach.LOAD), h.size() - 1));
            ordinary.add(perform(p, t - 1, p.strength[t - 1], lw.top, previous.reps, previous.high, effort, rate));
            cut = Coach.next(ordinary, st, p.day[t]).weight >= lw.top - 1e-9;
        }

        // The 1RM the first set of the target asks for, against the real one on an ordinary day.
        double ratio = Coach.estimate(p.load(a.weight), a.reps[0]) / p.strength[t];
        int margin = p.can(p.strength[t], p.load(a.weight), 0) - a.reps[0];
        for (Stats s : to) {
            s.advices++;
            if (unsafe) {
                s.unsafe++;
                s.note("UNSAFE " + what);
            }
            if (flipFlop) {
                s.flipFlop++;
                s.note("UP THEN DOWN " + what);
            }
            if (isolated) s.badDays++;
            if (lowered && !cut) s.loweredAnyway++;
            if (cut) {
                s.cutAfterBadDay++;
                if (a.reason.contains("χωρίς πρόοδο")) s.cutByStall++;
                if (a.reason.contains("κάτω από το εύρος")) s.cutByRange++;
                if (a.dayKind == -1) s.cutFlagged++;
                s.note("CUT AFTER A BAD DAY " + what + " form " + Fmt.num1(a.form * 100) + " limit " + Fmt.num1(a.badLimit * 100)
                        + " day " + Fmt.num1(p.dayFactor[t - 1] * 100) + " :: " + a.reason);
            }
            if (t >= 10 && gap < Coach.BREAK_DAYS) {
                s.trackingSum += ratio;
                s.trackingN++;
                if (ratio > 1.03) s.over3++;
                if (ratio > 1.06) s.over6++;
            }
            if (t >= 12 && t < 30) {
                s.rightN++;
                if (margin >= 0 && margin <= 3) s.right++;
            }
        }
    }

    /** Was session {@code t} flagged as a weak day, with the learned limits and with the fixed ones? */
    static void detect(Person p, int t, Session last, Coach.Advice a) {
        boolean easy = false;
        Coach.Work w = Coach.work(last);
        for (int f : w.feel) easy |= f == Coach.EASY;
        boolean learned = a.dayKind == -1;
        double fixedLimit = 1 - Math.max(0.03, Math.min(0.09, 1.5 * Coach.SPREAD_PRIOR));
        boolean fixed = a.dayKind != 1 && a.form > 0 && a.form <= fixedLimit && !easy;
        int truth = p.special[t] == -1 ? 1 : 0;
        Detect d = DETECT[sigmaIndex(p)][t < 20 ? 0 : 1];
        d.learned[truth][learned ? 1 : 0]++;
        d.fixed[truth][fixed ? 1 : 0]++;
    }

    static int sigmaIndex(Person p) {
        return p.id % 3;
    }

    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%d people x 6 ways of training x %d sessions = %d sessions, %d advices%n",
                LIFTERS, SESSIONS, LIFTERS * 6 * SESSIONS, ALL.advices));
        sb.append(String.format(Locale.ROOT, "unsafe jumps %d, up-then-down %d, weight lowered because of one bad day %d of %d"
                + " (lowered after one, but due anyway: %d)%n%n", ALL.unsafe, ALL.flipFlop, ALL.cutAfterBadDay, ALL.badDays,
                ALL.loweredAnyway));
        sb.append("way of training                        target/real 1RM   >3% over   >6% over   target within 0-3 reps   weight gain/real gain\n");
        for (int e = 0; e < 2; e++) {
            for (int r = 0; r < 3; r++) sb.append(line(EFFORT[e] + ", " + RATES[r], BY_STYLE[e][r]));
        }
        sb.append('\n');
        for (int g = 0; g < 3; g++) sb.append(line(GEAR[g], BY_GEAR[g]));
        for (int g = 0; g < 3; g++) {
            Stats x = BY_GEAR[g];
            sb.append(String.format(Locale.ROOT, "  %s: up-then-down %d, cut after a bad day %d of %d (stall %d, below range %d, flagged weak %d)%n",
                    GEAR[g], x.flipFlop, x.cutAfterBadDay, x.badDays, x.cutByStall, x.cutByRange, x.cutFlagged));
        }
        sb.append("\nweak days recognised (balanced accuracy, %): learned limits vs the fixed starting limits\n");
        for (int s = 0; s < 3; s++) {
            sb.append(String.format(Locale.ROOT, "  spread ±%.1f%%: sessions 5-19 %.1f vs %.1f, later %.1f vs %.1f%n", SIGMA[s] * 100,
                    Detect.balanced(DETECT[s][0].learned), Detect.balanced(DETECT[s][0].fixed),
                    Detect.balanced(DETECT[s][1].learned), Detect.balanced(DETECT[s][1].fixed)));
        }
        for (String x : ALL.examples) sb.append(x).append('\n');
        return sb.toString();
    }

    static String line(String name, Stats s) {
        return String.format(Locale.ROOT, "%-38s %8.3f %14.1f%% %9.1f%% %17.0f%% %22.2f%n", name, s.tracking(), s.over3(),
                s.over6(), s.right(), s.progress());
    }

    // ---------------------------------------------------------------- what must hold

    @Test
    public void neverAnUnsafeJump() {
        assertEquals(report, 0, ALL.unsafe);
    }

    @Test
    public void neverUpAndStraightBackDown() {
        assertEquals(report, 0, ALL.flipFlop);
    }

    @Test
    public void oneBadDayAlmostNeverLowersTheWeight() {
        // Not exactly never: when the bad day's numbers look like an ordinary session (someone who
        // stops at the target anyway), nothing can tell them apart. Under 0.3% of bad days.
        assertTrue(report, ALL.badDays > 5000);
        assertTrue(report, ALL.cutAfterBadDay * 1000 <= ALL.badDays * 3);
    }

    @Test
    public void theTargetStaysCloseToRealStrength() {
        for (int e = 0; e < 2; e++) {
            for (int r = 0; r < 3; r++) {
                Stats s = BY_STYLE[e][r];
                String what = EFFORT[e] + ", " + RATES[r] + "\n" + report;
                assertTrue(what, s.tracking() >= 0.89 && s.tracking() <= 0.98);
                assertTrue(what, s.over6() <= 1.0);
            }
        }
    }

    @Test
    public void theWeightFollowsTheRealProgress() {
        for (int g = 0; g < 3; g++) {
            assertTrue(GEAR[g] + "\n" + report, BY_GEAR[g].progress() >= 0.90);
        }
    }

    @Test
    public void ratingTheSetsGivesBetterTargets() {
        double none = BY_STYLE[STOP_AT_TARGET][RATE_NONE].right();
        double half = BY_STYLE[STOP_AT_TARGET][RATE_HALF].right();
        double all = BY_STYLE[STOP_AT_TARGET][RATE_ALL].right();
        assertTrue(report, half >= none + 5);
        assertTrue(report, all >= half + 5);
    }

    @Test
    public void learnedLimitsAreNeverClearlyWorseThanFixedOnes() {
        for (int s = 0; s < 3; s++) {
            for (int period = 0; period < 2; period++) {
                double learned = Detect.balanced(DETECT[s][period].learned);
                double fixed = Detect.balanced(DETECT[s][period].fixed);
                assertTrue("spread ±" + SIGMA[s] * 100 + "%, period " + period + "\n" + report, learned > fixed - 3);
            }
        }
    }
}
