package com.omiros.gymnotes;

/** App colours. */
final class Theme {
    static final int BG = 0xFF0F1115;
    static final int CARD = 0xFF181B22;
    static final int FIELD = 0xFF232833;
    static final int TEXT = 0xFFEEF1F6;
    static final int SOFT = 0xFFB7BECB;
    static final int DIM = 0xFF7D8594;
    static final int FAINT = 0xFF2A2F3A;
    static final int ACCENT = 0xFF5B9DFF;
    static final int ACCENT_SOFT = 0xFF9CC3FF;
    static final int ACCENT_BG = 0xFF1C2E4A;
    static final int GREEN = 0xFF3DD68C;
    static final int GREEN_BG = 0xFF15302A;
    static final int GOLD = 0xFFFFC94D;
    static final int GOLD_BG = 0xFF3A3018;
    static final int RED = 0xFFF06A6A;
    static final int DIALOG = 0xFF1A1D24;
    /** How a set felt: easy (reps to spare), normal, at the limit. */
    static final int FEEL_EASY = 0xFF3DD68C;
    static final int FEEL_NORMAL = 0xFF7FB2FF;
    static final int FEEL_HARD = 0xFFF07A6A;

    static int feel(int f) {
        return f == Coach.EASY ? FEEL_EASY : f == Coach.NORMAL ? FEEL_NORMAL : f == Coach.HARD ? FEEL_HARD : DIM;
    }

    private Theme() {
    }
}
