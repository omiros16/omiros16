package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
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

    Ui(Activity act) {
        this.act = act;
        density = act.getResources().getDisplayMetrics().density;
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- views

    TextView text(float sp, int color, boolean medium) {
        TextView tv = new TextView(act);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    TextView text(String s, float sp, int color, boolean medium) {
        TextView tv = text(sp, color, medium);
        tv.setText(s);
        return tv;
    }

    TextView label(String s) {
        TextView tv = text(s, 13, DIM, true);
        tv.setPadding(dp(4), dp(18), 0, dp(8));
        return tv;
    }

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
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    Drawable ripple(Drawable content, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, round(0xFFFFFFFF, radiusDp));
    }

    /** A rounded, tappable card. */
    LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(ripple(CARD, 16));
        c.setPadding(dp(16), dp(14), dp(12), dp(14));
        return c;
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
        iv.setBackground(ripple(0, 22));
        iv.setLayoutParams(size(44, 44));
        return iv;
    }

    /** Full-width button: primary = filled accent, otherwise an outlined card. */
    TextView button(String label, boolean primary) {
        TextView b = text(label, 16, primary ? 0xFF0B1220 : ACCENT_SOFT, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(50));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackground(primary ? ripple(ACCENT, 14) : ripple(outline(CARD, FAINT, 14), 14));
        return b;
    }

    /** A big two-line button with an icon, for the export / import actions. */
    LinearLayout bigButton(int iconRes, String title, String subtitle) {
        LinearLayout b = row();
        b.setBackground(ripple(outline(CARD, FAINT, 16), 16));
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        ImageView iv = icon(iconRes, ACCENT);
        iv.setBackground(round(ACCENT_BG, 20));
        b.addView(iv, size(40, 40));
        LinearLayout col = column();
        col.setPadding(dp(14), 0, 0, 0);
        col.addView(text(title, 16, TEXT, true));
        col.addView(text(subtitle, 13, DIM, false));
        b.addView(col, fill());
        return b;
    }

    TextView stepper(String label) {
        TextView tv = text(label, 20, TEXT, false);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(ripple(0, 12));
        return tv;
    }

    EditText field(String hint) {
        EditText e = new EditText(act);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        return e;
    }

    /** A pill used for tabs and the date. */
    void stylePill(TextView c, boolean selected) {
        c.setTextColor(selected ? ACCENT_SOFT : SOFT);
        c.setBackground(ripple(selected ? outline(ACCENT_BG, ACCENT, 20) : outline(CARD, FAINT, 20), 20));
    }

    TextView pill(String s) {
        TextView c = text(s, 14, SOFT, true);
        c.setGravity(Gravity.CENTER);
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        c.setPadding(dp(14), dp(9), dp(14), dp(9));
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

    /** Mixed-case buttons (Greek all-caps would otherwise keep the accents). */
    void styleButtons(AlertDialog d, boolean destructive) {
        int[] ids = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int id : ids) {
            Button b = d.getButton(id);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            if (id == DialogInterface.BUTTON_POSITIVE) b.setTextColor(destructive ? RED : ACCENT);
            else if (id == DialogInterface.BUTTON_NEUTRAL) b.setTextColor(RED);
            else b.setTextColor(DIM);
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
        box.setPadding(dp(22), dp(8), dp(22), 0);
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
