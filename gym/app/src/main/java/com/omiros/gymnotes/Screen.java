package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One page of the app. MainActivity keeps a back stack of them. */
abstract class Screen {
    final MainActivity act;
    final Db db;
    final Ui ui;
    private View view;
    TextView titleView;
    TextView subtitleView;

    Screen(MainActivity act) {
        this.act = act;
        db = act.db;
        ui = act.ui;
    }

    final View view() {
        if (view == null) {
            view = build();
            refresh();
        }
        return view;
    }

    /** Creates the views; {@link #refresh} then fills them. */
    abstract View build();

    /** Reloads the data shown (called when shown, and again when coming back to it). */
    void refresh() {
    }

    /** The app came back to the foreground with this screen on top. */
    void onResume() {
        refresh();
    }

    /** This screen is being left (another screen opened, back, or the app paused). */
    void onHide() {
    }

    /** Return true to consume the back press. */
    boolean onBack() {
        return false;
    }

    /** Identity for restoring the back stack, see {@link #restore}. */
    abstract String key();

    static Screen restore(MainActivity act, String key) {
        String[] p = key.split(":");
        try {
            switch (p[0]) {
                case "home":
                    return new HomeScreen(act, Integer.parseInt(p[1]));
                case "preset":
                    long presetId = Long.parseLong(p[1]);
                    return act.db.presetName(presetId) == null ? null : new PresetScreen(act, presetId);
                case "log":
                    long exId = Long.parseLong(p[1]);
                    return act.db.exercise(exId) == null ? null : new ExerciseScreen(act, exId, Long.parseLong(p[2]));
                case "history":
                    long hId = Long.parseLong(p[1]);
                    return act.db.exercise(hId) == null ? null : new HistoryScreen(act, hId);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Title bar: optional back arrow, title with a subtitle line, then action icons. */
    LinearLayout header(boolean back, View... actions) {
        LinearLayout bar = ui.row();
        bar.setPadding(back ? ui.dp(4) : ui.dp(18), ui.dp(10), ui.dp(6), ui.dp(8));
        if (back) {
            ImageView b = ui.iconButton(R.drawable.ic_back, "Πίσω");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.onBackPressed();
                }
            });
            bar.addView(b);
        }
        LinearLayout col = ui.column();
        col.setPadding(back ? ui.dp(6) : 0, 0, ui.dp(6), 0);
        titleView = ui.text(back ? 20 : 24, TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView = ui.text(13, ACCENT_SOFT, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setVisibility(View.GONE);
        col.addView(titleView);
        col.addView(subtitleView);
        bar.addView(col, Ui.fill());
        for (View a : actions) bar.addView(a);
        return bar;
    }

    // ---------------------------------------------------------------- shared exercise actions

    void renameExercise(final Db.Exercise e, final Runnable after) {
        ui.prompt("Μετονομασία άσκησης", "Όνομα", e.name, "Αποθήκευση", new Ui.OnText() {
            @Override
            public void run(String name) {
                if (!db.renameExercise(e.id, name)) {
                    ui.toast("Υπάρχει ήδη άσκηση με αυτό το όνομα");
                    return;
                }
                after.run();
            }
        });
    }

    void deleteExercise(final Db.Exercise e, final Runnable after) {
        String presets = db.presetNames(e.id);
        String msg = "Θα σβηστεί όλο το ιστορικό της"
                + (e.sessions > 0 ? " (" + e.sessions + (e.sessions == 1 ? " καταγραφή)" : " καταγραφές)") : "")
                + (presets.isEmpty() ? "." : " και θα βγει από: " + presets + ".")
                + "\n\nΑν θες μόνο να τη βγάλεις από ένα πρόγραμμα, κάν' το μέσα από το πρόγραμμα.";
        ui.confirm("Διαγραφή «" + e.name + "»;", msg, "Διαγραφή", true, new Runnable() {
            @Override
            public void run() {
                db.deleteExercise(e.id);
                after.run();
            }
        });
    }

    void setTitle(String title, String subtitle) {
        titleView.setText(title);
        subtitleView.setText(subtitle == null ? "" : subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
