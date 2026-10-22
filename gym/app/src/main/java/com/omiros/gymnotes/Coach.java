package com.omiros.gymnotes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Suggests the next session of an exercise from its own recent sessions.
 *
 * <p>The core is double progression, the usual way to progress in the gym: keep the weight and add
 * reps until every working set reaches the top of the rep range, then add one weight step (the
 * smallest increment the person actually uses on that machine / dumbbell rack). On top of that:
 * <ul>
 * <li>Per set, an optional rating of how many reps were left: {@link #EASY} (3+), {@link #NORMAL}
 *     (1-2, also assumed when not rated), {@link #HARD} (0). Next time each set gets +2 reps after
 *     easy, +1 after normal, the same after hard. The weight goes up when every work set reached
 *     the top of the range, or would have with the reps it had left, and the reps expected at the
 *     new weight come from what each set could have done.</li>
 * <li>Day-to-day form: each session is compared with the usual level of the sessions before it
 *     (estimated 1RM of the top set). A clearly weaker day never lowers the target: the previous
 *     target is simply tried again. A clearly stronger day never earns more than one step. What
 *     "clearly" means is learned per exercise: the limits start at about −5% / +6% and follow how
 *     much this person's results normally vary, as sessions accumulate ({@link #spread}).</li>
 * <li>No progress for {@link #STALL_SESSIONS} sessions at the same weight: go ~10% lighter and build
 *     back up. After a long break: start ~10% lighter; after a shorter one: repeat.</li>
 * <li>Never more than {@link #MAX_JUMP} in one go; a weight step too big for the current reps
 *     (light dumbbells) is earned with extra reps first.</li>
 * </ul>
 */
final class Coach {
    static final int EASY = 1;
    static final int NORMAL = 2;
    static final int HARD = 3;

    static final int UP = 1;
    static final int MORE_REPS = 2;
    static final int SAME = 3;
    static final int DOWN = 4;

    static final int[][] RANGES = {{1, 3}, {3, 5}, {4, 6}, {5, 8}, {6, 10}, {8, 12}, {10, 15}, {12, 15}, {15, 20}};
    static final double[] STEPS = {0.5, 1, 1.25, 2, 2.5, 5, 10};

    /** Sessions that decide the advice: recent form matters. */
    static final int RECENT = 8;
    /**
     * Sessions that teach the day-to-day spread. Callers load one more ({@link #LOAD}), so that the
     * target given before the last session can be rebuilt from exactly what it was built from.
     */
    static final int HISTORY = 16;
    static final int LOAD = HISTORY + 1;
    static final int BREAK_DAYS = 14;
    static final int LONG_BREAK_DAYS = 28;
    static final int STALL_SESSIONS = 4;
    /** Day-to-day spread assumed before there is data: a bad day at about −5%, a great one at +6%. */
    static final double SPREAD_PRIOR = 0.033;
    /** The assumed spread counts as this many observed sessions. */
    static final int PRIOR_WEIGHT = 6;
    static final double MAX_JUMP = 0.10;
    static final double DELOAD = 0.10;
    /** Sets at least this share of the session's top weight are work sets; lighter ones are warm-ups. */
    private static final double WORKING = 0.85;
    private static final int EXTRA_REPS = 4;

    private Coach() {
    }

    /** Reps to add to a set to read it like a normal one (1-2 left): easy +2, at the limit −1. */
    static int extra(int feel) {
        return feel == EASY ? 2 : feel == HARD ? -1 : 0;
    }

    /** Per-exercise choices; 0 means automatic. */
    static final class Settings {
        int low;
        int high;
        double step;
        /**
         * The automatic rep range, taken from the first sessions of the exercise. It must not follow
         * the recent sessions: those follow the range, and the range would creep up (8-12, 10-15...).
         */
        int autoLow;
        int autoHigh;
        /** The automatic weight step, from every weight ever used (a recent window can miss steps). */
        double autoStep;
    }

    /**
     * {@code s} (or defaults) plus the automatic choices: the rep range from the exercise's first
     * sessions and the weight step from all the weights (centi-kg) ever used in it.
     */
    static Settings withBaseline(Settings s, List<Session> earliest, Collection<Long> weights) {
        Settings out = new Settings();
        if (s != null) {
            out.low = s.low;
            out.high = s.high;
            out.step = s.step;
        }
        if (!earliest.isEmpty()) {
            int[] r = inferRange(earliest);
            out.autoLow = r[0];
            out.autoHigh = r[1];
        }
        if (!weights.isEmpty()) out.autoStep = inferStep(new TreeSet<>(weights));
        return out;
    }

    static final class Advice {
        int kind;
        double weight;
        int[] reps;
        int low;
        int high;
        boolean autoRange;
        double step;
        boolean autoStep;
        /** Last session's strength against the usual level (1.0 = usual), 0 if unknown. */
        double form;
        /** -1 a weak day, 1 a great day, 0 otherwise or unknown. */
        int dayKind;
        /** Limits for a weak / great day, from {@link #spread}. */
        double badLimit;
        double goodLimit;
        /** Typical day-to-day spread of this exercise (0.03 = ±3%) and how many sessions it was learned from. */
        double spread;
        int learned;
        /** How many sessions the advice looked at. */
        int sessions;
        String reason;
    }

    /** The top weight of a session, the reps done with it, and how many sets were real work. */
    static final class Work {
        double top;
        int[] reps;
        int[] feel;
        int sets;
    }

    static Work work(Session s) {
        Work w = new Work();
        long top = 0;
        for (int i = 0; i < s.n; i++) top = Math.max(top, Strength.centi(s.weight[i]));
        int[] r = new int[s.n];
        int[] f = new int[s.n];
        int count = 0;
        for (int i = 0; i < s.n; i++) {
            long c = Strength.centi(s.weight[i]);
            if (c == top) {
                f[count] = s.feel[i];
                r[count++] = s.reps[i];
            }
            if (c >= WORKING * top) w.sets++;
        }
        w.top = top / 100.0;
        w.reps = Arrays.copyOf(r, count);
        w.feel = Arrays.copyOf(f, count);
        return w;
    }

    /** A set this much lighter than the session's top weight is a warm-up. */
    static boolean isWarmUp(double weight, double top) {
        return Strength.centi(weight) < WORKING * Strength.centi(top);
    }

    /** Epley without the PR cap: it only compares an exercise with itself. */
    static double estimate(double w, int r) {
        return r <= 1 ? w : w * (1 + Math.min(r, 30) / 30.0);
    }

    /**
     * Strength shown in a session: estimated 1RM of the best work set, or max reps for bodyweight.
     * A set counts with the reps it could have done ({@link #extra}): someone who stops at the target
     * only shows how strong the day was through the ratings.
     */
    static double score(Session s) {
        Work w = work(s);
        double best = 0;
        for (int i = 0; i < s.n; i++) {
            int could = Math.max(1, s.reps[i] + extra(s.feel[i]));
            if (w.top <= 0) best = Math.max(best, could);
            else if (Strength.centi(s.weight[i]) >= WORKING * Strength.centi(w.top)) {
                best = Math.max(best, estimate(s.weight[i], could));
            }
        }
        return best;
    }

    /** Reps expected at {@code to} kg from {@code r} reps at {@code from} kg (at least 1). */
    static int predict(double from, int r, double to) {
        if (to <= 0) return r;
        return Math.max(1, (int) Math.floor(30 * (estimate(from, r) / to - 1) + 1e-6));
    }

    // ---------------------------------------------------------------- inference

    /** Rep range from the reps usually done with the top weight (last 6 sessions). */
    static int[] inferRange(List<Session> h) {
        List<Integer> all = new ArrayList<>();
        for (int k = Math.max(0, h.size() - 6); k < h.size(); k++) {
            for (int r : work(h.get(k)).reps) all.add(r);
        }
        if (all.isEmpty()) return new int[]{8, 12};
        Collections.sort(all);
        int m = all.get(all.size() / 2);
        if (m <= 3) return new int[]{1, 3};
        if (m <= 5) return new int[]{3, 5};
        if (m <= 7) return new int[]{5, 8};
        if (m <= 12) return new int[]{8, 12};
        if (m <= 15) return new int[]{10, 15};
        return new int[]{15, 20};
    }

    /**
     * The weight increment actually used: the most common gap between the weights used. A step
     * above 2.5 kg needs two gaps agreeing, since one 5 kg gap may just be two 2.5 kg steps (or a
     * deload).
     */
    static double inferStep(List<Session> h, double top) {
        TreeSet<Long> weights = new TreeSet<>();
        for (Session s : h) {
            for (int i = 0; i < s.n; i++) weights.add(Strength.centi(s.weight[i]));
        }
        double step = inferStep(weights);
        return step > 0 ? step : top > 0 && top < 10 ? 1 : 2.5;
    }

    /** The step from distinct weights (centi-kg), or 0 if they show none. */
    static double inferStep(TreeSet<Long> weights) {
        weights = new TreeSet<>(weights.tailSet(1L));
        long[] candidates = {500, 250, 200, 125, 100, 50};
        int[] votes = new int[candidates.length];
        boolean allQuarter = true;
        Long prev = null;
        for (long c : weights) {
            if (prev != null) {
                long d = c - prev;
                allQuarter &= d % 250 == 0;
                for (int k = 0; k < candidates.length; k++) {
                    if (d % candidates[k] == 0) {
                        votes[k]++;
                        break;
                    }
                }
            }
            prev = c;
        }
        int best = -1;
        for (int k = 0; k < candidates.length; k++) {
            // Ties go to the smaller step (later in the list).
            if (votes[k] > 0 && (best < 0 || votes[k] >= votes[best])) best = k;
        }
        if (best >= 0) {
            if (candidates[best] > 250 && votes[best] < 2 && allQuarter) return 2.5;
            return candidates[best] / 100.0;
        }
        return 0;
    }

    /**
     * About {@link #DELOAD} lighter, on the step grid; the same weight if it cannot go lower by at
     * most 20% (one step can be a lot on a light dumbbell).
     */
    static double lighter(double top, double step) {
        long t = Strength.centi(top);
        long st = Strength.centi(step);
        long k = Math.max(1, Math.round(top * DELOAD / step));
        long w = t - k * st;
        return w > 0 && w >= t * 0.8 ? w / 100.0 : top;
    }

    /** The heaviest weight on the step grid below {@code top} where {@code best} reps there predict {@code low}. */
    private static double intoRange(double top, int best, int low, double step) {
        long t = Strength.centi(top);
        long st = Strength.centi(step);
        for (long c = t - st; c >= t / 2; c -= st) {
            if (predict(top, best, c / 100.0) >= low) return c / 100.0;
        }
        return top;
    }

    // ---------------------------------------------------------------- advice

    /**
     * What to do next time, from the sessions (oldest first, up to {@link #LOAD}) for a session on
     * day {@code today}. The last {@link #RECENT} decide; those before the last one teach the
     * day-to-day spread. Null without history.
     */
    static Advice next(List<Session> all, Settings settings, long today) {
        if (all.isEmpty()) return null;
        if (all.size() > LOAD) all = all.subList(all.size() - LOAD, all.size());
        long gap = Math.max(0, today - all.get(all.size() - 1).day);
        return decide(all, all.size(), settings, gap, true);
    }

    /** The advice after session {@code end - 1} of {@code all}, from exactly what was known then. */
    private static Advice decide(List<Session> all, int end, Settings settings, long gap, boolean judgeForm) {
        List<Session> h = all.subList(Math.max(0, end - RECENT), end);
        // Learned from the sessions before the last one: a weak day must not hide itself by widening the spread.
        double[] spread = spread(all.subList(Math.max(0, end - HISTORY), end - 1));
        Session last = h.get(h.size() - 1);
        Work w = work(last);
        Advice a = new Advice();
        a.sessions = h.size();
        if (settings != null && settings.low > 0 && settings.high >= settings.low) {
            a.low = settings.low;
            a.high = settings.high;
        } else if (settings != null && settings.autoLow > 0) {
            a.low = settings.autoLow;
            a.high = settings.autoHigh;
            a.autoRange = true;
        } else {
            int[] r = inferRange(h);
            a.low = r[0];
            a.high = r[1];
            a.autoRange = true;
        }
        if (settings != null && settings.step > 0) {
            a.step = settings.step;
        } else if (settings != null && settings.autoStep > 0) {
            a.step = settings.autoStep;
            a.autoStep = true;
        } else {
            a.step = inferStep(h, w.top);
            a.autoStep = true;
        }
        a.spread = spread[0];
        a.learned = (int) spread[1];
        a.badLimit = 1 - clamp(1.5 * a.spread, 0.03, 0.09);
        a.goodLimit = 1 + clamp(1.75 * a.spread, 0.04, 0.10);
        double[] f = form(h, a.low);
        boolean easy = contains(w.feel, EASY);
        boolean good = f[1] > 0 && f[1] >= a.goodLimit;
        // Fewer reps on sets rated easy were a choice, not a weak day.
        boolean bad = !good && f[0] > 0 && f[0] <= a.badLimit && !easy;
        a.form = good ? f[1] : f[0];
        a.dayKind = good ? 1 : bad ? -1 : 0;
        int sets = Math.max(1, w.sets);
        int[] base = extend(w.reps, sets);
        // What each set could have done; on a great day only what it did, which is already above the usual.
        int[] cap = good ? w.reps.clone() : capacities(w);
        boolean atTop = w.reps.length >= sets;
        for (int i = 0; i < w.reps.length; i++) {
            if (w.reps[i] + Math.max(0, extra(w.feel[i])) < a.high) atTop = false;
        }

        if (gap >= LONG_BREAK_DAYS && w.top > 0 && lighter(w.top, a.step) < w.top) {
            set(a, DOWN, lighter(w.top, a.step), base);
            a.reason = "Πέρασαν " + gap + " μέρες από την τελευταία φορά. Ξεκίνα περίπου 10% πιο ελαφριά· "
                    + "η δύναμη γυρίζει γρήγορα και ξαναχτίζεις από εκεί.";
            return a;
        }
        if (atTop) {
            if (gap >= BREAK_DAYS) {
                set(a, SAME, w.top, base);
                a.reason = "Πέρασαν " + gap + " μέρες από την τελευταία φορά. Κάνε πρώτα ξανά τα ίδια και μετά ανεβαίνεις.";
                return a;
            }
            up(a, w, cap, good, sets);
            return a;
        }
        if (bad && judgeForm && end >= 2) {
            // A weak day: give again the target that was set for it. Checked before a stall, so one
            // odd day is never read as stagnation nor becomes the base of the next target.
            // The target as it was for that day: with the gap that came before it (a break counts).
            Advice before = decide(all, end - 1, settings, last.day - all.get(end - 2).day, false);
            before.form = a.form;
            before.dayKind = -1;
            before.sessions = a.sessions;
            // It was already time to go lighter: that advice stands, unless this was that lighter session.
            if (before.kind == DOWN && Strength.centi(before.weight) != Strength.centi(w.top)) return before;
            before.kind = SAME;
            long drop = Math.round((1 - a.form) * 100);
            if (Strength.centi(before.weight) < Strength.centi(w.top)) {
                // They had gone heavier than the target on their own and it did not work out.
                before.reason = "Με τα " + Fmt.kg(w.top) + " kg η απόδοση ήταν " + drop + "% κάτω από τα συνηθισμένα "
                        + "σου, βαρύ για τώρα. Γύρνα στον στόχο που είχες.";
            } else {
                before.reason = "Η τελευταία φορά ήταν " + drop + "% κάτω από τα συνηθισμένα σου, μάλλον κακή μέρα. "
                        + "Μην κατεβάσεις κιλά: ξαναδοκίμασε τον ίδιο στόχο.";
            }
            return before;
        }
        if (!easy && stalled(h)) {
            double to = lighter(w.top, a.step);
            if (to < w.top) {
                Work first = work(h.get(h.size() - STALL_SESSIONS));
                set(a, DOWN, to, base);
                a.reason = STALL_SESSIONS + " φορές στα " + Fmt.kg(w.top) + " kg χωρίς πρόοδο (από "
                        + reps(first.reps) + " σε " + reps(w.reps) + " reps). Κατέβα στα " + Fmt.kg(to) + " kg (−"
                        + Math.round((1 - to / w.top) * 100) + "%) για 1–2 φορές και ξαναχτίσε· "
                        + "έτσι ξεκολλάς συνήθως.";
                return a;
            }
        }
        int bestCap = max(cap);
        if (w.top > 0 && bestCap < a.low - 1) {
            // Strength was normal, but the weight is too heavy for the rep range: back into it.
            double to = intoRange(w.top, bestCap, a.low, a.step);
            if (to < w.top) {
                int[] r = new int[sets];
                for (int i = 0; i < sets; i++) r[i] = Math.min(a.high, predict(w.top, i < cap.length ? cap[i] : cap[cap.length - 1], to));
                set(a, DOWN, to, r);
                a.reason = "Με τα " + Fmt.kg(w.top) + " kg έκανες " + max(w.reps) + " reps, κάτω από το εύρος σου ("
                        + rangeLabel(a.low, a.high) + "). Πήγαινε στα " + Fmt.kg(to) + " kg για περίπου " + r[0]
                        + " reps και ανέβαινε από εκεί.";
                return a;
            }
        }
        if (gap >= BREAK_DAYS) {
            set(a, SAME, w.top, base);
            a.reason = "Πέρασαν " + gap + " μέρες από την τελευταία φορά. Κάνε πρώτα ξανά τα ίδια.";
            return a;
        }
        moreReps(a, w, good, sets);
        return a;
    }

    /** Every work set reached the top of the range (or would have): add weight. */
    private static void up(Advice a, Work w, int[] cap, boolean good, int sets) {
        if (w.top <= 0) {
            int[] r = new int[sets];
            Arrays.fill(r, a.low);
            set(a, UP, a.step, r);
            a.reason = "Έπιασες τα " + a.high + " reps σε όλα τα sets με το σωματικό σου βάρος. Πρόσθεσε "
                    + Fmt.kg(a.step) + " kg (ζώνη ή αλτήρα) ή πήγαινε σε πιο δύσκολη παραλλαγή.";
            return;
        }
        boolean wayOver = min(cap) >= a.high + 2;
        int steps = wayOver && !good ? 2 : 1;
        while (steps > 1 && (steps * a.step > w.top * MAX_JUMP + 1e-9
                || min(predictAll(w.top, cap, steps * a.step)) < a.low)) {
            steps--;
        }
        double to = w.top + steps * a.step;
        int[] r = predictAll(w.top, cap, steps * a.step);
        if (steps == 1 && min(r) < a.low - 1 && min(w.reps) < a.high + EXTRA_REPS) {
            // The next weight is a big jump for these reps (light dumbbells): earn it with extra reps first.
            int[] more = new int[sets];
            for (int i = 0; i < sets; i++) more[i] = Math.min(a.high + EXTRA_REPS, w.reps[i] + 1);
            set(a, MORE_REPS, w.top, more);
            a.reason = "Το επόμενο βάρος (" + Fmt.kg(to) + " kg) είναι +" + Math.round(a.step / w.top * 100)
                    + "%, μεγάλο άλμα για τώρα. Ανέβασε πρώτα τα reps με τα " + Fmt.kg(w.top) + " kg (έως "
                    + (a.high + EXTRA_REPS) + ") και μετά ανεβαίνεις.";
            return;
        }
        for (int i = 0; i < r.length; i++) r[i] = Math.min(r[i], a.high);
        set(a, UP, to, r);
        StringBuilder sb = new StringBuilder();
        if (good) {
            sb.append("Πολύ καλή μέρα (+").append(Math.round((a.form - 1) * 100))
                    .append("% από τα συνηθισμένα σου), οπότε ανεβαίνεις ένα βήμα, όχι παραπάνω. ");
        }
        if (min(w.reps) >= a.high) {
            sb.append("Έπιασες τα ").append(a.high).append(" reps σε όλα τα sets με ").append(Fmt.kg(w.top)).append(" kg");
        } else {
            sb.append("Με όσα reps σου περίσσευαν, θα έπιανες τα ").append(a.high).append(" σε όλα τα sets με ")
                    .append(Fmt.kg(w.top)).append(" kg");
        }
        if (steps == 2) sb.append(contains(w.feel, EASY) ? " και ήταν χαλαρά" : ", πολύ πάνω από το εύρος σου");
        sb.append(": ανέβα ").append(Fmt.kg(steps * a.step)).append(" kg. Τα reps θα πέσουν λίγο· τα ξαναχτίζεις ως τα ")
                .append(a.high).append('.');
        a.reason = sb.toString();
    }

    /** Below the top of the range: same weight, more reps, set by set. */
    private static void moreReps(Advice a, Work w, boolean good, int sets) {
        int[] r = extend(w.reps, sets);
        int done = w.reps.length;
        String with = w.top > 0 ? "με " + Fmt.kg(w.top) + " kg" : "με το σωματικό σου βάρος";
        String stay = w.top > 0 ? "στα " + Fmt.kg(w.top) + " kg" : "με το σωματικό σου βάρος";
        int[] add = new int[done];
        boolean rated = false;
        for (int i = 0; i < done; i++) {
            // Easy +2, normal (or not rated) +1, at the limit +0; a great day already was the +1.
            add[i] = clamp(1 + extra(w.feel[i]), 0, 2);
            if (good) add[i] = Math.max(0, add[i] - 1);
            int target = Math.max(r[i], Math.min(a.high, r[i] + add[i]));
            add[i] = target - r[i];
            r[i] = target;
            rated |= w.feel[i] != 0;
        }
        boolean pushed = false;
        boolean limitBelowTop = false;
        boolean allLimit = true;
        for (int i = 0; i < done; i++) {
            if (r[i] < a.high) limitBelowTop |= w.feel[i] == HARD;
            if (r[i] < a.high && w.feel[i] != HARD) allLimit = false;
        }
        if (!good && max(add) == 0 && limitBelowTop && allLimit) {
            // Every set short of the top was at the limit: keep them, and add one rep only to the weakest.
            int weakest = -1;
            for (int i = 0; i < done; i++) {
                if (r[i] < a.high && (weakest < 0 || r[i] < r[weakest])) weakest = i;
            }
            if (weakest >= 0) {
                r[weakest]++;
                add[weakest] = 1;
                pushed = true;
            }
        }
        set(a, MORE_REPS, w.top, r);

        StringBuilder sb = new StringBuilder();
        if (good) {
            sb.append("Πολύ καλή μέρα (+").append(Math.round((a.form - 1) * 100)).append("% από τα συνηθισμένα σου). ");
        }
        boolean same = true;
        for (int i = 1; i < done; i++) same &= add[i] == add[0] && w.feel[i] == w.feel[0];
        if (pushed) {
            if (done < sets) sb.append("Κάνε και τα ").append(sets).append(" sets ").append(with).append(". ");
            boolean everySet = true;
            for (int i = 0; i < done; i++) everySet &= w.feel[i] == HARD;
            sb.append(done == 1 ? "Ήταν στο όριο" : everySet ? "Όλα ήταν στο όριο" : "Όσα έμειναν κάτω από τα " + a.high
                    + " ήταν στο όριο").append(": κράτα τα ίδια και βάλε +1 rep μόνο στο πιο αδύναμο set.");
        } else if (max(add) == 0) {
            if (done < sets) sb.append("Κάνε και τα ").append(sets).append(" sets ").append(with).append(". ");
            sb.append(good ? "Κράτα τα ίδια και ξαναπιάσε αυτά τα νούμερα· μετά συνεχίζεις με +1 rep." : "Κράτα τα ίδια.");
        } else if (same) {
            String plus = "+" + add[0] + (add[0] == 1 ? " rep" : " reps");
            String why = rated ? " (" + (done == 1 ? "ήταν " : "ήταν όλα ") + Fmt.feel(w.feel[0]) + ")" : "";
            sb.append(done < sets
                    ? "Κάνε και τα " + sets + " sets " + with + ", με " + plus + " σε όσα έκανες ήδη " + with + why + "."
                    : "Μείνε " + stay + " και βάλε " + plus + " σε κάθε set" + why + ".");
        } else {
            // Said by how the sets felt, not by number: the numbers of last time and of the target differ.
            if (done < sets) sb.append("Κάνε και τα ").append(sets).append(" sets ").append(with).append(". ");
            sb.append("Μείνε ").append(stay).append(": ");
            String[] groups = {"στα χαλαρά sets", "στα μέτρια", "στα «στο όριο»", "σε όσα δεν βαθμολόγησες"};
            int[] order = {EASY, NORMAL, HARD, 0};
            boolean first = true;
            boolean capped = false;
            for (int g = 0; g < order.length; g++) {
                boolean present = false;
                for (int i = 0; i < done; i++) present |= w.feel[i] == order[g];
                if (!present) continue;
                int nominal = clamp(1 + extra(order[g]), 0, 2);
                if (good) nominal = Math.max(0, nominal - 1);
                for (int i = 0; i < done; i++) capped |= w.feel[i] == order[g] && add[i] < nominal;
                sb.append(first ? "" : ", ").append(nominal > 0 ? "+" + nominal + (first ? (nominal == 1 ? " rep " : " reps ") : " ") : "ίδια ")
                        .append(groups[g]);
                first = false;
            }
            if (capped) sb.append(" (ως τα ").append(a.high).append(")");
            sb.append('.');
        }
        sb.append(" Όταν πιάσεις τα ").append(a.high).append(" σε όλα, ");
        sb.append(w.top > 0 ? "ανεβαίνεις " + Fmt.kg(a.step) + " kg." : "προσθέτεις βάρος.");
        a.reason = sb.toString();
    }

    static double[] form(List<Session> h) {
        return form(h, 0);
    }

    /**
     * Last session against the usual level: {its score over the median of up to 3 sessions before
     * it, over their best}. The second is for a great day, so steady progress is not mistaken for
     * luck. Zeros when unknown, or when the last session was lighter on purpose (a deload is not a
     * weak day) unless even that went far below the rep range ({@code low}).
     */
    static double[] form(List<Session> h, int low) {
        int n = h.size();
        double[] none = {0, 0};
        if (n < 3) return none;
        Session last = h.get(n - 1);
        Work lw = work(last);
        if (Strength.centi(lw.top) < Strength.centi(work(h.get(n - 2)).top)) {
            int could = 0;
            for (int i = 0; i < lw.reps.length; i++) could = Math.max(could, lw.reps[i] + extra(lw.feel[i]));
            if (low <= 0 || could >= low - 1) return none;
        }
        List<Double> prev = new ArrayList<>();
        for (int k = n - 2; k >= 0 && prev.size() < 3; k--) {
            Session s = h.get(k);
            if ((work(s).top > 0) == (lw.top > 0)) prev.add(score(s));
        }
        if (prev.size() < 2) return none;
        Collections.sort(prev);
        double median = prev.get(prev.size() / 2);
        double best = prev.get(prev.size() - 1);
        if (median <= 0) return none;
        double now = score(last);
        return new double[]{now / median, now / best};
    }

    /**
     * How much this person's results in this exercise normally move from one session to the next:
     * {spread, sessions learned from}. Each session's form (against the median before it) is a
     * sample; the spread is their robust deviation (MAD, so a few odd days do not count much),
     * blended with {@link #SPREAD_PRIOR} as if that were {@link #PRIOR_WEIGHT} sessions. With 5
     * sessions the prior still dominates; with 10-16 the person's own spread does.
     */
    static double[] spread(List<Session> all) {
        List<Double> samples = new ArrayList<>();
        for (int k = 2; k < all.size(); k++) {
            double f = form(all.subList(0, k + 1))[0];
            if (f > 0) samples.add(f - 1);
        }
        int n = samples.size();
        if (n == 0) return new double[]{SPREAD_PRIOR, 0};
        double med = median(samples);
        List<Double> dev = new ArrayList<>();
        for (double x : samples) dev.add(Math.abs(x - med));
        double own = clamp(1.4826 * median(dev), 0.005, 0.12);
        return new double[]{(n * own + PRIOR_WEIGHT * SPREAD_PRIOR) / (n + PRIOR_WEIGHT), n};
    }

    /**
     * Same top weight for {@link #STALL_SESSIONS} sessions and none after the first did better than
     * it (one good session in between is progress; a bad day alone is not a stall). Not again while
     * an earlier drop in weight (a deload or a come-back) is still among the recent sessions: that
     * needs time to pay off.
     */
    static boolean stalled(List<Session> h) {
        int n = h.size();
        if (n < STALL_SESSIONS) return false;
        for (int k = 1; k < n; k++) {
            if (Strength.centi(work(h.get(k)).top) < Strength.centi(work(h.get(k - 1)).top)) return false;
        }
        long top = Strength.centi(work(h.get(n - 1)).top);
        for (int k = n - STALL_SESSIONS; k < n; k++) {
            if (Strength.centi(work(h.get(k)).top) != top) return false;
        }
        double first = avg(work(h.get(n - STALL_SESSIONS)).reps);
        for (int k = n - STALL_SESSIONS + 1; k < n; k++) {
            if (avg(work(h.get(k)).reps) > first + 1e-9) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    /** What each set could have done: its reps read with the reps it had left. */
    private static int[] capacities(Work w) {
        int[] c = new int[w.reps.length];
        for (int i = 0; i < c.length; i++) c[i] = Math.max(1, w.reps[i] + extra(w.feel[i]));
        return c;
    }

    private static int[] predictAll(double top, int[] cap, double add) {
        int[] r = new int[cap.length];
        for (int i = 0; i < r.length; i++) r[i] = predict(top, cap[i], top + add);
        return r;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) return true;
        }
        return false;
    }

    private static int max(int[] r) {
        int m = 0;
        for (int x : r) m = Math.max(m, x);
        return m;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    /** Reps per work set: the ones done with the top weight, padded with the last of them. */
    private static int[] extend(int[] reps, int sets) {
        int[] r = new int[Math.max(sets, reps.length)];
        for (int i = 0; i < r.length; i++) r[i] = i < reps.length ? reps[i] : reps[reps.length - 1];
        return r;
    }

    private static void set(Advice a, int kind, double weight, int[] reps) {
        a.kind = kind;
        a.weight = weight;
        a.reps = reps;
    }

    private static int min(int[] r) {
        int m = Integer.MAX_VALUE;
        for (int x : r) m = Math.min(m, x);
        return r.length == 0 ? 0 : m;
    }

    private static double avg(int[] r) {
        if (r.length == 0) return 0;
        double s = 0;
        for (int x : r) s += x;
        return s / r.length;
    }

    private static String reps(int[] r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length; i++) sb.append(i == 0 ? "" : ",").append(r[i]);
        return sb.toString();
    }

    static String rangeLabel(int low, int high) {
        return low == high ? String.valueOf(low) : String.format(Locale.ROOT, "%d–%d", low, high);
    }
}
