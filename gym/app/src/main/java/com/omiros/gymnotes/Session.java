package com.omiros.gymnotes;

import java.util.Arrays;

/** The sets of one exercise on one day, in the order they were done. */
final class Session {
    final long day;
    int n;
    double[] weight = new double[6];
    int[] reps = new int[6];
    /** How each set felt: {@link Coach#EASY}, {@link Coach#NORMAL}, {@link Coach#HARD}, or 0 if not rated. */
    int[] feel = new int[6];
    /** {@link Strength} PR flags of the whole session, filled by {@link Strength#markPrs}. */
    int flags;

    Session(long day) {
        this.day = day;
    }

    void add(double w, int r) {
        add(w, r, 0);
    }

    void add(double w, int r, int f) {
        if (n == weight.length) {
            weight = Arrays.copyOf(weight, n * 2);
            reps = Arrays.copyOf(reps, n * 2);
            feel = Arrays.copyOf(feel, n * 2);
        }
        weight[n] = w;
        reps[n] = r;
        feel[n] = f >= Coach.EASY && f <= Coach.HARD ? f : 0;
        n++;
    }

    boolean rated() {
        for (int i = 0; i < n; i++) {
            if (feel[i] != 0) return true;
        }
        return false;
    }

    boolean weighted() {
        for (int i = 0; i < n; i++) {
            if (weight[i] > 0) return true;
        }
        return false;
    }

    double bestE1rm() {
        double best = 0;
        for (int i = 0; i < n; i++) best = Math.max(best, Strength.e1rm(weight[i], reps[i]));
        return best;
    }

    int maxReps() {
        int best = 0;
        for (int i = 0; i < n; i++) best = Math.max(best, reps[i]);
        return best;
    }
}
