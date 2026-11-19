package com.omiros.nfcreader;

import java.util.Locale;

/** Tiny translation helper: Greek on Greek phones, English everywhere else. */
final class L {
    static final boolean EL = "el".equals(Locale.getDefault().getLanguage());

    static String t(String en, String el) {
        return EL ? el : en;
    }

    private L() {
    }
}
