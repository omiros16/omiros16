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
 * <li>Day-to-day form: each session is compared with the usual level of the sessions before it
 *     (estimated 1RM of the top set). A clearly weaker day ({@link #BAD_DAY}) never lowers the
 *     target: the previous target is simply tried again. A clearly stronger day ({@link #GOOD_DAY})
 *     never earns more than one step.</li>
 * <li>No progress for {@link #STALL_SESSIONS} sessions at the same weight: go ~10% lighter and build
 *     back up. After a long break: start ~10% lighter; after a shorter one: repeat.</li>
 * <li>An optional effort rating (easy / normal / at the limit) speeds up or slows down the step.</li>
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

    /** Sessions looked at: recent form matters, and the cost stays the same after years. */
    static final int RECENT = 8;
    static final int BREAK_DAYS = 14;
    static final int LONG_BREAK_DAYS = 28;
    static final int STALL_SESSIONS = 4;
    static final double BAD_DAY = 0.95;
    static final double GOOD_DAY = 1.06;
    static final double MAX_JUMP = 0.10;
    static final double DELOAD = 0.10;
    /** Sets at least this share of the session's top weight are work sets; lighter ones are warm-ups. */
    private static final double WORKING = 0.85;
    private static final int EXTRA_REPS = 4;

    private Coach() {
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
        /** How many sessions the advice looked at. */
        int sessions;
        String reason;
    }

    /** The top weight of a session, the reps done with it, and how many sets were real work. */
    static final class Work {
        double top;
        int[] reps;
        int sets;
    }

    static Work work(Session s) {
        Work w = new Work();
        long top = 0;
        for (int i = 0; i < s.n; i++) top = Math.max(top, Strength.centi(s.weight[i]));
        int[] r = new int[s.n];
        int count = 0;
        for (int i = 0; i < s.n; i++) {
            long c = Strength.centi(s.weight[i]);
            if (c == top) r[count++] = s.reps[i];
            if (c >= WORKING * top) w.sets++;
        }
        w.top = top / 100.0;
        w.reps = Arrays.copyOf(r, count);
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

    /** Strength shown in a session: estimated 1RM of the best work set, or max reps for bodyweight. */
    static double score(Session s) {
        Work w = work(s);
        double best = 0;
        for (int i = 0; i < s.n; i++) {
            if (w.top <= 0) best = Math.max(best, s.reps[i]);
            else if (Strength.centi(s.weight[i]) >= WORKING * Strength.centi(w.top)) {
                best = Math.max(best, estimate(s.weight[i], s.reps[i]));
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

    /** The heaviest weight on the step grid below {@code top} where the best set predicts {@code low} reps. */
    private static double intoRange(Work w, int low, double step) {
        int best = 0;
        for (int r : w.reps) best = Math.max(best, r);
        long t = Strength.centi(w.top);
        long st = Strength.centi(step);
        for (long c = t - st; c >= t / 2; c -= st) {
            if (predict(w.top, best, c / 100.0) >= low) return c / 100.0;
        }
        return w.top;
    }

    // ---------------------------------------------------------------- advice

    /**
     * What to do next time, from the sessions (oldest first; only the last {@link #RECENT} are used)
     * for a session on day {@code today}. Null without history.
     */
    static Advice next(List<Session> all, Settings settings, long today) {
        if (all.isEmpty()) return null;
        List<Session> h = all.size() > RECENT ? all.subList(all.size() - RECENT, all.size()) : all;
        long gap = Math.max(0, today - h.get(h.size() - 1).day);
        return decide(h, settings, gap, true);
    }

    private static Advice decide(List<Session> h, Settings settings, long gap, boolean judgeForm) {
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
        a.form = form(h);
        boolean good = a.form >= GOOD_DAY;
        boolean bad = a.form > 0 && a.form <= BAD_DAY;
        int sets = Math.max(1, w.sets);
        int[] base = extend(w.reps, sets);
        boolean atTop = w.reps.length >= sets && min(w.reps) >= a.high;

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
            up(a, w, last.effort, good, sets);
            return a;
        }
        if (stalled(h)) {
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
        if (bad && judgeForm && h.size() >= 2) {
            // A weak day: give again the target that was set for it.
            Advice before = decide(h.subList(0, h.size() - 1), settings, gap, false);
            before.form = a.form;
            before.sessions = a.sessions;
            if (before.kind == DOWN) return before;   // it was already time to go lighter
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
        int bestReps = 0;
        for (int r : w.reps) bestReps = Math.max(bestReps, r);
        if (w.top > 0 && bestReps < a.low - 1) {
            // Strength was normal, but the weight is too heavy for the rep range: back into it.
            double to = intoRange(w, a.low, a.step);
            if (to < w.top) {
                int[] r = new int[sets];
                for (int i = 0; i < sets; i++) r[i] = Math.min(a.high, predict(w.top, base[i], to));
                set(a, DOWN, to, r);
                a.reason = "Με τα " + Fmt.kg(w.top) + " kg έκανες " + bestReps + " reps, κάτω από το εύρος σου ("
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
        moreReps(a, w, last.effort, good, sets);
        return a;
    }

    /** Every work set reached the top of the range: add weight. */
    private static void up(Advice a, Work w, int effort, boolean good, int sets) {
        if (w.top <= 0) {
            int[] r = new int[sets];
            Arrays.fill(r, a.low);
            set(a, UP, a.step, r);
            a.reason = "Έπιασες τα " + a.high + " reps σε όλα τα sets με το σωματικό σου βάρος. Πρόσθεσε "
                    + Fmt.kg(a.step) + " kg (ζώνη ή αλτήρα) ή πήγαινε σε πιο δύσκολη παραλλαγή.";
            return;
        }
        boolean wayOver = min(w.reps) >= a.high + 3;
        int steps = (wayOver || effort == EASY) && !good ? 2 : 1;
        while (steps > 1 && (steps * a.step > w.top * MAX_JUMP + 1e-9 || min(predictAll(w, steps * a.step)) < a.low)) {
            steps--;
        }
        double to = w.top + steps * a.step;
        int[] r = predictAll(w, steps * a.step);
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
        sb.append("Έπιασες τα ").append(a.high).append(" reps σε όλα τα sets με ").append(Fmt.kg(w.top))
                .append(" kg");
        if (steps == 2) sb.append(effort == EASY ? " και σου φάνηκε εύκολο" : ", πολύ πάνω από το εύρος σου");
        sb.append(": ανέβα ").append(Fmt.kg(steps * a.step)).append(" kg. Τα reps θα πέσουν λίγο· τα ξαναχτίζεις ως τα ")
                .append(a.high).append('.');
        a.reason = sb.toString();
    }

    /** Below the top of the range: same weight, more reps. */
    private static void moreReps(Advice a, Work w, int effort, boolean good, int sets) {
        int[] r = extend(w.reps, sets);
        int done = w.reps.length;
        String with = w.top > 0 ? "με " + Fmt.kg(w.top) + " kg" : "με το σωματικό σου βάρος";
        // Sets that were lighter last time move up to the top weight as they were: no extra reps for them.
        String moveUp = done < sets ? "Κάνε και τα " + sets + " sets " + with + ". " : "";
        String how;
        if (effort == HARD) {
            // At the limit: hold, and add one rep only to the weakest set.
            int weakest = -1;
            for (int i = 0; i < done; i++) {
                if (r[i] < a.high && (weakest < 0 || r[i] < r[weakest])) weakest = i;
            }
            if (weakest >= 0) r[weakest]++;
            how = moveUp + "Ήταν στο όριο, οπότε κράτα τα ίδια και βάλε +1 rep μόνο στο πιο αδύναμο set.";
        } else if (good) {
            // Numbers above the usual level: make them the new normal before asking for more.
            how = moveUp + "Κράτα τα ίδια και ξαναπιάσε αυτά τα νούμερα· μετά συνεχίζεις με +1 rep.";
        } else {
            int add = effort == EASY ? 2 : 1;
            for (int i = 0; i < done; i++) r[i] = Math.min(a.high, r[i] + add);
            String plus = "+" + add + (add == 1 ? " rep" : " reps");
            String easy = effort == EASY ? " (σου φάνηκε εύκολο)" : "";
            how = done < sets
                    ? "Κάνε και τα " + sets + " sets " + with + ", με " + plus + " σε όσα έκανες ήδη " + with + easy + "."
                    : "Μείνε " + (w.top > 0 ? "στα " + Fmt.kg(w.top) + " kg" : "με το σωματικό σου βάρος") + " και βάλε "
                    + plus + " σε κάθε set" + easy + ".";
        }
        set(a, MORE_REPS, w.top, r);
        StringBuilder sb = new StringBuilder();
        if (good) {
            sb.append("Πολύ καλή μέρα (+").append(Math.round((a.form - 1) * 100)).append("% από τα συνηθισμένα σου). ");
        }
        sb.append(how).append(" Όταν πιάσεις τα ").append(a.high).append(" σε όλα, ");
        sb.append(w.top > 0 ? "ανεβαίνεις " + Fmt.kg(a.step) + " kg." : "προσθέτεις βάρος.");
        a.reason = sb.toString();
    }

    /**
     * Last session against the usual level: its score over the median of up to 3 sessions before
     * it (over their best for a good day, so steady progress is not mistaken for luck). 0 when
     * unknown, or when the last session was lighter on purpose (a deload is not a weak day).
     */
    static double form(List<Session> h) {
        int n = h.size();
        if (n < 3) return 0;
        Session last = h.get(n - 1);
        Work lw = work(last);
        if (Strength.centi(lw.top) < Strength.centi(work(h.get(n - 2)).top)) return 0;
        List<Double> prev = new ArrayList<>();
        for (int k = n - 2; k >= 0 && prev.size() < 3; k--) {
            Session s = h.get(k);
            if ((work(s).top > 0) == (lw.top > 0)) prev.add(score(s));
        }
        if (prev.size() < 2) return 0;
        double now = score(last);
        List<Double> sorted = new ArrayList<>(prev);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        double best = sorted.get(sorted.size() - 1);
        if (median <= 0) return 0;
        if (now >= best * GOOD_DAY) return now / best;
        double r = now / median;
        return r <= BAD_DAY ? r : Math.min(r, GOOD_DAY - 0.001);
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

    private static int[] predictAll(Work w, double add) {
        int[] r = new int[w.reps.length];
        for (int i = 0; i < r.length; i++) r[i] = predict(w.top, w.reps[i], w.top + add);
        return r;
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
