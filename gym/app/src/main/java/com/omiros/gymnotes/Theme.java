package com.omiros.gymnotes;

/**
 * App colours: chalk on a graphite floor, with one highlighter colour for what to aim at
 * (the target) and what was beaten (records).
 */
final class Theme {
    static final int BG = 0xFF141414;
    /** Grouped lists and panels. */
    static final int CARD = 0xFF1E1E1E;
    /** Inputs, steppers and quiet buttons. */
    static final int FIELD = 0xFF2A2A2A;
    /** Hairlines between rows. */
    static final int LINE = 0xFF2C2C2C;
    static final int TEXT = 0xFFF2F1ED;
    static final int SOFT = 0xFFB3B1AC;
    static final int DIM = 0xFF7F7D78;
    static final int MARK = 0xFFEBC55E;
    static final int MARK_BG = 0x24EBC55E;
    static final int RED = 0xFFE5735E;
    static final int RIPPLE = 0x22FFFFFF;
    /** How a set felt: easy (reps to spare), normal, at the limit. */
    static final int FEEL_EASY = 0xFF6CC495;
    static final int FEEL_NORMAL = 0xFF93A9CC;
    static final int FEEL_HARD = 0xFFE5735E;

    static int feel(int f) {
        return f == Coach.EASY ? FEEL_EASY : f == Coach.NORMAL ? FEEL_NORMAL : f == Coach.HARD ? FEEL_HARD : DIM;
    }

    /** The same colour at low opacity, for the background behind it. */
    static int tint(int color) {
        return (color & 0x00FFFFFF) | 0x26000000;
    }

    private Theme() {
    }
}
