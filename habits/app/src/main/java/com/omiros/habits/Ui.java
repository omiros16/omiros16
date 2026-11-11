package com.omiros.habits;

import static com.omiros.habits.Theme.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;

/** View helpers and Greek date words shared by the screens; every screen is built in code. */
final class Ui {
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** Roboto Condensed, already on the phone: the big countdown numerals. */
    static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    static final String[] MONTHS = {"Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος",
            "Ιούνιος", "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος"};
    static final String[] MONTHS_GEN = {"Ιανουαρίου", "Φεβρουαρίου", "Μαρτίου", "Απριλίου", "Μαΐου",
            "Ιουνίου", "Ιουλίου", "Αυγούστου", "Σεπτεμβρίου", "Οκτωβρίου", "Νοεμβρίου", "Δεκεμβρίου"};
    static final String[] WEEKDAYS = {"Δευτέρα", "Τρίτη", "Τετάρτη", "Πέμπτη", "Παρασκευή", "Σάββατο", "Κυριακή"};

    private Ui() {
    }

    static String weekday(LocalDate d) {
        return WEEKDAYS[d.getDayOfWeek().getValue() - 1];
    }

    /** "τον Οκτώβριο": every month name takes the accusative by dropping its final ς. */
    static String monthAcc(int month) {
        String m = MONTHS[month - 1];
        return m.substring(0, m.length() - 1);
    }

    static String time(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** "1 ώρα 5 λεπτά", "45 λεπτά". */
    static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        String hs = h == 1 ? "1 ώρα" : h + " ώρες";
        if (h == 0) return m == 1 ? "1 λεπτό" : m + " λεπτά";
        return m == 0 ? hs : hs + " " + m + "'";
    }

    static boolean isSunday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, float sp, int color, boolean medium) {
        TextView tv = new TextView(c);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(MEDIUM);
        return tv;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Touch feedback over a shape; the shape stays live, so its colours can change later. */
    static Drawable withRipple(Context c, Drawable shape, float radius) {
        GradientDrawable mask = rounded(0xFFFFFFFF, radius);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape == null ? new ColorDrawable(0) : shape, mask);
    }

    /**
     * A pill button. {@code primary}: filled with the accent; otherwise an outline. Pass
     * {@code color} to make the outline (and text) another colour, e.g. RED for a pass.
     */
    static TextView button(Context c, String label, boolean primary, int color) {
        TextView b = text(c, 15, primary ? ON_ACCENT : color, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setMinHeight(dp(c, 44));
        GradientDrawable g = rounded(primary ? color : 0, dp(c, 22));
        if (!primary) g.setStroke(dp(c, 1.5f), color);
        b.setBackground(withRipple(c, g, dp(c, 22)));
        return b;
    }

    /** A choice among a few: selected ones are filled with the soft accent. */
    static TextView chip(Context c, String label, boolean selected) {
        TextView tv = text(c, 14, selected ? ACCENT : TEXT, selected);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        GradientDrawable g = rounded(selected ? ACCENT_SOFT : CHIP, dp(c, 18));
        g.setStroke(dp(c, 1), selected ? ACCENT : LINE);
        tv.setBackground(withRipple(c, g, dp(c, 18)));
        return tv;
    }

    /** Small grey heading above a group of settings. */
    static EditText field(Context c, String hint, int type) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        return e;
    }

    /** Shows a dialog that is mostly one text field: keyboard up, and the keyboard's Done presses OK. */
    static void showTyping(Context c, final AlertDialog d, EditText input) {
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                return true;
            }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        input.requestFocus();
        d.show();
        styleDialog(c, d);
    }

    static TextView label(Context c, String s) {
        TextView tv = text(c, 13, DIM, true);
        tv.setText(s);
        tv.setPadding(0, dp(c, 20), 0, dp(c, 8));
        return tv;
    }

    /** Rounded corners and mixed-case buttons (Greek all-caps would otherwise keep the accents). */
    static void styleDialog(Context c, AlertDialog d) {
        d.getWindow().setBackgroundDrawable(new InsetDrawable(rounded(CARD, dp(c, 24)), dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 24)));
        int[] ids = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int id : ids) {
            Button b = d.getButton(id);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextColor(id == DialogInterface.BUTTON_NEUTRAL ? RED : id == DialogInterface.BUTTON_POSITIVE ? ACCENT : DIM);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        }
    }

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, R.style.DialogTheme);
    }

    /** Lays its children out left to right, wrapping to a new row when one is full. */
    static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c) {
            super(c);
            gap = dp(c, 8);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            boolean bounded = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
            int max = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            int widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(max, bounded ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = child.getMeasuredWidth();
                if (bounded && x > 0 && x + w > max) {
                    x = 0;
                    y += rowHeight + gap;
                    rowHeight = 0;
                }
                x += w + gap;
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
                widest = Math.max(widest, x - gap);
            }
            setMeasuredDimension(resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthSpec),
                    y + rowHeight + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0;
            int y = 0;
            int rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += rowHeight + gap;
                    rowHeight = 0;
                }
                child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + gap;
                rowHeight = Math.max(rowHeight, h);
            }
        }
    }
}
