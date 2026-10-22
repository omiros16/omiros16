package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

/** Small view factory and dialog helpers shared by the screens. */
final class Ui {
    interface OnText {
        void run(String text);
    }

    interface OnPick {
        void run(int which);
    }

    final Activity act;
    private final float density;
    /** Sofia Sans for text; its condensed cut, with tabular figures, for weights and reps. */
    final Typeface regular;
    final Typeface medium;
    final Typeface semibold;
    final Typeface figures;
    final Typeface figuresBold;

    Ui(Activity act) {
        this.act = act;
        density = act.getResources().getDisplayMetrics().density;
        regular = font(R.font.sofia_sans_regular, Typeface.DEFAULT);
        medium = font(R.font.sofia_sans_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        semibold = font(R.font.sofia_sans_semibold, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        figures = font(R.font.sofia_sans_condensed_semibold, Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        figuresBold = font(R.font.sofia_sans_condensed_bold, Typeface.create("sans-serif-condensed", Typeface.BOLD));
    }

    private Typeface font(int res, Typeface fallback) {
        try {
            Typeface t = act.getResources().getFont(res);
            return t != null ? t : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- text

    TextView text(float sp, int color, Typeface face) {
        TextView tv = new TextView(act);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(face);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(dp(2), 1f);
        return tv;
    }

    TextView text(float sp, int color, boolean medium) {
        return text(sp, color, medium ? this.medium : regular);
    }

    TextView text(String s, float sp, int color, boolean medium) {
        TextView tv = text(sp, color, medium);
        tv.setText(s);
        return tv;
    }

    TextView text(String s, float sp, int color, Typeface face) {
        TextView tv = text(sp, color, face);
        tv.setText(s);
        return tv;
    }

    /** Weights and reps: condensed, tabular figures. */
    TextView figures(float sp, int color, boolean bold) {
        TextView tv = text(sp, color, bold ? figuresBold : figures);
        tv.setFontFeatureSettings("tnum");
        tv.setLineSpacing(0, 1f);
        return tv;
    }

    /** "52.5 kg × 10, 8" with the unit and the × set smaller and quieter than the numbers. */
    static CharSequence quiet(String s) {
        SpannableString sp = new SpannableString(s);
        int i = 0;
        while ((i = s.indexOf("kg", i)) >= 0) {
            sp.setSpan(new RelativeSizeSpan(0.62f), i, i + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(DIM), i, i + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i += 2;
        }
        i = 0;
        while ((i = s.indexOf('×', i)) >= 0) {
            sp.setSpan(new RelativeSizeSpan(0.8f), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(DIM), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i++;
        }
        return sp;
    }

    /** Quiet underlined text that opens an explanation. */
    TextView link(String s) {
        TextView tv = text(s, 14, SOFT, false);
        tv.setPaintFlags(tv.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tv.setBackground(ripple(0, 8));
        return tv;
    }

    /** A small heading above a group. */
    TextView label(String s) {
        TextView tv = text(s, 14, SOFT, true);
        tv.setPadding(dp(4), dp(26), 0, dp(10));
        return tv;
    }

    // ---------------------------------------------------------------- layout

    LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    LinearLayout.LayoutParams size(int wDp, int hDp) {
        return new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
    }

    /** A 1 px line; {@code insetDp} keeps it off the left edge, under the text. */
    View hairline(int insetDp) {
        View v = new View(act);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(insetDp);
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------- surfaces

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable outline(int fill, int stroke, int radiusDp) {
        GradientDrawable g = round(fill, radiusDp);
        g.setStroke(dp(1.5f), stroke);
        return g;
    }

    /** Touch feedback over a rounded fill (color 0 = transparent). */
    Drawable ripple(int color, int radiusDp) {
        GradientDrawable mask = round(0xFFFFFFFF, radiusDp);
        Drawable content = color == 0 ? new ColorDrawable(0) : round(color, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask);
    }

    Drawable ripple(Drawable content, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, round(0xFFFFFFFF, radiusDp));
    }

    /** Rows sharing one rounded panel, split by hairlines; add them with {@link #addRow}. */
    LinearLayout group() {
        LinearLayout g = column();
        g.setBackground(round(CARD, 16));
        g.setClipToOutline(true);
        return g;
    }

    void addRow(LinearLayout group, View row) {
        if (group.getChildCount() > 0) group.addView(hairline(18));
        group.addView(row, matchWrap());
    }

    /** A tappable row for a {@link #group}. */
    LinearLayout groupRow() {
        LinearLayout r = row();
        r.setBackground(ripple(0, 0));
        r.setPadding(dp(18), dp(15), dp(12), dp(15));
        r.setMinimumHeight(dp(56));
        return r;
    }

    /** A row with a leading icon: "Νέο πρόγραμμα", export, import. */
    LinearLayout actionRow(int iconRes, String title, String subtitle) {
        LinearLayout r = groupRow();
        r.addView(icon(iconRes, SOFT), size(24, 24));
        LinearLayout col = column();
        col.setPadding(dp(16), 0, 0, 0);
        col.addView(text(title, 16, TEXT, true));
        if (subtitle != null) {
            TextView sub = text(subtitle, 14, DIM, false);
            sub.setPadding(0, dp(3), 0, 0);
            col.addView(sub);
        }
        r.addView(col, fill());
        return r;
    }

    ImageView icon(int res, int color) {
        ImageView iv = new ImageView(act);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        return iv;
    }

    ImageView iconButton(int res, String description) {
        ImageView iv = icon(res, TEXT);
        iv.setContentDescription(description);
        iv.setBackground(ripple(0, 24));
        iv.setLayoutParams(size(48, 48));
        return iv;
    }

    /** −/+ inside a number field. */
    ImageView stepper(int res, String description) {
        ImageView iv = icon(res, SOFT);
        iv.setContentDescription(description);
        iv.setBackground(ripple(0, 12));
        return iv;
    }

    EditText field(String hint) {
        EditText e = new EditText(act);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTypeface(regular);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setBackground(round(FIELD, 12));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    /** A tab with a line under the selected one. */
    void styleTab(TextView t, boolean selected) {
        t.setTextColor(selected ? TEXT : DIM);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(selected ? TEXT : 0);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{bar});
        ld.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(0, dp(2));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), ld, null));
    }

    TextView tab(String s) {
        TextView t = text(s, 16, DIM, semibold);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, dp(14));
        styleTab(t, false);
        return t;
    }

    /** A choice chip: chalk when selected. */
    void stylePill(TextView c, boolean selected) {
        c.setTextColor(selected ? BG : SOFT);
        c.setBackground(ripple(selected ? TEXT : FIELD, 10));
    }

    TextView pill(String s) {
        TextView c = text(s, 14, SOFT, true);
        c.setGravity(Gravity.CENTER);
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        stylePill(c, false);
        return c;
    }

    // ---------------------------------------------------------------- feedback & dialogs

    void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(act, R.style.DialogTheme);
    }

    /** The app's fonts and mixed-case buttons (Greek all-caps would otherwise keep the accents). */
    void styleButtons(AlertDialog d, boolean destructive) {
        int[] ids = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int id : ids) {
            Button b = d.getButton(id);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTypeface(semibold);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            if (id == DialogInterface.BUTTON_POSITIVE) b.setTextColor(destructive ? RED : TEXT);
            else if (id == DialogInterface.BUTTON_NEUTRAL) b.setTextColor(RED);
            else b.setTextColor(DIM);
        }
        int titleId = act.getResources().getIdentifier("alertTitle", "id", "android");
        View title = titleId == 0 ? null : d.findViewById(titleId);
        if (title instanceof TextView) ((TextView) title).setTypeface(semibold);
        View message = d.findViewById(android.R.id.message);
        if (message instanceof TextView) {
            ((TextView) message).setTypeface(regular);
            ((TextView) message).setLineSpacing(dp(3), 1f);
        }
    }

    AlertDialog show(AlertDialog.Builder b, boolean destructive) {
        AlertDialog d = b.show();
        styleButtons(d, destructive);
        return d;
    }

    void confirm(String title, String msg, String yes, boolean destructive, final Runnable onYes) {
        show(dialog().setTitle(title).setMessage(msg).setNegativeButton("Άκυρο", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onYes.run();
                    }
                }), destructive);
    }

    void info(String title, String msg) {
        show(dialog().setTitle(title).setMessage(msg).setPositiveButton("OK", null), false);
    }

    /** Asks for a name; empty input is ignored. */
    void prompt(String title, String hint, String initial, String ok, final OnText onOk) {
        LinearLayout box = column();
        box.setPadding(dp(22), dp(12), dp(22), 0);
        final EditText input = field(hint);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(input, matchWrap());
        final AlertDialog d = dialog().setTitle(title).setView(box).setNegativeButton("Άκυρο", null)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        String t = Db.clean(input.getText().toString());
                        if (!t.isEmpty()) onOk.run(t);
                    }
                }).create();
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            }
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        input.requestFocus();
        d.show();
        styleButtons(d, false);
    }

    void menu(View anchor, String[] items, final OnPick onPick) {
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu m = pm.getMenu();
        for (int i = 0; i < items.length; i++) m.add(0, i, i, items[i]);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                onPick.run(item.getItemId());
                return true;
            }
        });
        pm.show();
    }
}
