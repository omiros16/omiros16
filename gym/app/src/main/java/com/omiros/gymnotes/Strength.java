package com.omiros.gymnotes;

import java.util.List;
import java.util.TreeMap;

/**
 * Strength benchmarking: how strong a set is and whether it is a real personal record.
 *
 * <p>Volume (weight × reps) is deliberately not used, because 1 kg × 100 would "beat" 100 kg × 5.
 * A set is a PR only when, compared with everything done before in that exercise:
 * <ul>
 * <li>{@link #WEIGHT}: it is heavier than any set before;</li>
 * <li>{@link #E1RM}: its estimated one-rep max beats the best so far. Epley, w × (1 + reps / 30),
 *     with reps capped at {@link #REP_CAP} because the estimate inflates for long sets (a set of
 *     20 counts as a set of 12, so light high-rep sets can never fake strength);</li>
 * <li>{@link #REPS}: more reps than ever with that weight or heavier, for a weight that was used
 *     before (so there is a record at it to beat) and is at least {@link #REP_PR_MIN_SHARE} of the
 *     heaviest one (or bodyweight, 0 kg), so warm-ups and light pump sets never count.</li>
 * </ul>
 * The first session of an exercise is the baseline and has no PRs.
 */
final class Strength {
    static final int WEIGHT = 1;
    static final int E1RM = 2;
    static final int REPS = 4;

    static final int REP_CAP = 12;
    static final double REP_PR_MIN_SHARE = 0.75;
    /** An estimate has to improve by more than this (kg) to count, so rounding noise is no PR. */
    private static final double E1RM_MARGIN = 0.05;

    private Strength() {
    }

    /** Weight in hundredths of a kg, so equal weights compare exactly. */
    static long centi(double kg) {
        return Math.round(kg * 100);
    }

    /** Estimated one-rep max (kg); 0 for bodyweight sets. */
    static double e1rm(double weight, int reps) {
        if (weight <= 0 || reps <= 0) return 0;
        if (reps == 1) return weight;
        return weight * (1 + Math.min(reps, REP_CAP) / 30.0);
    }

    /** The best results among the sets added so far (add them in the order they were done). */
    static final class Bests {
        int sets;
        double maxWeight;
        int maxWeightReps;
        double bestE1rm;
        double bestE1rmWeight;
        int bestE1rmReps;
        /** Weight (centi-kg) -> most reps done with exactly that weight. */
        private final TreeMap<Long, Integer> repsAt;

        Bests() {
            repsAt = new TreeMap<>();
        }

        private Bests(Bests o) {
            sets = o.sets;
            maxWeight = o.maxWeight;
            maxWeightReps = o.maxWeightReps;
            bestE1rm = o.bestE1rm;
            bestE1rmWeight = o.bestE1rmWeight;
            bestE1rmReps = o.bestE1rmReps;
            repsAt = new TreeMap<>(o.repsAt);
        }

        Bests copy() {
            return new Bests(this);
        }

        void add(double w, int r) {
            if (r <= 0 || w < 0) return;
            long c = centi(w);
            if (sets == 0 || c > centi(maxWeight)) {
                maxWeight = w;
                maxWeightReps = r;
            } else if (c == centi(maxWeight) && r > maxWeightReps) {
                maxWeightReps = r;
            }
            double e = e1rm(w, r);
            if (e > bestE1rm) {
                bestE1rm = e;
                bestE1rmWeight = w;
                bestE1rmReps = r;
            }
            Integer old = repsAt.get(c);
            if (old == null || r > old) repsAt.put(c, r);
            sets++;
        }

        /** Most reps ever done with {@code w} kg or more, or -1 if nothing that heavy was lifted. */
        int repsAtOrAbove(double w) {
            int best = -1;
            for (int r : repsAt.tailMap(centi(w), true).values()) {
                if (r > best) best = r;
            }
            return best;
        }

        /** PR flags of the set {@code w} × {@code r} against these bests; 0 when there is no history. */
        int judge(double w, int r) {
            if (sets == 0 || r <= 0 || w < 0) return 0;
            long c = centi(w);
            long max = centi(maxWeight);
            int f = 0;
            if (c > max) f |= WEIGHT;
            if (bestE1rm > 0 && e1rm(w, r) > bestE1rm + E1RM_MARGIN) f |= E1RM;
            if ((f & WEIGHT) == 0 && repsAt.containsKey(c) && (c == 0 || c >= REP_PR_MIN_SHARE * max)
                    && r > repsAtOrAbove(w)) {
                f |= REPS;
            }
            return f;
        }
    }

    /**
     * Sets {@link Session#flags} of each session (oldest first) against everything before it,
     * including the earlier sets of the same day. Returns the bests over the whole history.
     */
    static Bests markPrs(List<Session> sessions) {
        Bests b = new Bests();
        for (Session s : sessions) {
            boolean baseline = b.sets == 0;
            int f = 0;
            for (int i = 0; i < s.n; i++) {
                if (!baseline) f |= b.judge(s.weight[i], s.reps[i]);
                b.add(s.weight[i], s.reps[i]);
            }
            s.flags = f;
        }
        return b;
    }
}
