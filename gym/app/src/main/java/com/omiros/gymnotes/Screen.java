package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.View;
import android.widget.HorizontalScrollView;
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

    /** Rep range and weight step of an exercise (each automatic unless chosen). */
    void progressSettings(final long exerciseId, final Runnable after) {
        Coach.Settings current = db.settings(exerciseId);
        Coach.Settings baseline = db.coachSettings(exerciseId);
        baseline.low = 0;
        baseline.high = 0;
        baseline.step = 0;   // show what "automatic" would pick
        Coach.Advice auto = Coach.next(db.recent(exerciseId, Fmt.today() + 1, Coach.LOAD), baseline, Fmt.today());
        String autoRange = auto == null ? "" : " (" + Coach.rangeLabel(auto.low, auto.high) + ")";
        String autoStep = auto == null ? "" : " (" + Fmt.kg(auto.step) + ")";

        final int[] range = {0};
        for (int i = 0; i < Coach.RANGES.length; i++) {
            if (Coach.RANGES[i][0] == current.low && Coach.RANGES[i][1] == current.high) range[0] = i + 1;
        }
        final int[] step = {0};
        for (int i = 0; i < Coach.STEPS.length; i++) {
            if (Strength.centi(Coach.STEPS[i]) == Strength.centi(current.step)) step[0] = i + 1;
        }

        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        box.addView(ui.label("Εύρος reps"));
        String[] ranges = new String[Coach.RANGES.length + 1];
        ranges[0] = "Αυτόματο" + autoRange;
        for (int i = 0; i < Coach.RANGES.length; i++) ranges[i + 1] = Coach.rangeLabel(Coach.RANGES[i][0], Coach.RANGES[i][1]);
        box.addView(pills(ranges, range));
        TextView rangeHint = ui.text("Ανεβαίνεις κιλά όταν πιάσεις το πάνω όριο σε όλα τα sets.", 13, DIM, false);
        rangeHint.setPadding(ui.dp(4), ui.dp(6), 0, 0);
        box.addView(rangeHint);
        box.addView(ui.label("Βήμα κιλών"));
        String[] steps = new String[Coach.STEPS.length + 1];
        steps[0] = "Αυτόματο" + autoStep;
        for (int i = 0; i < Coach.STEPS.length; i++) steps[i + 1] = Fmt.kg(Coach.STEPS[i]) + " kg";
        box.addView(pills(steps, step));
        TextView stepHint = ui.text("Η μικρότερη αύξηση που έχει το μηχάνημα ή οι αλτήρες. Το αυτόματο τη βρίσκει "
                + "από τα κιλά που έχεις γράψει.", 13, DIM, false);
        stepHint.setPadding(ui.dp(4), ui.dp(6), 0, 0);
        box.addView(stepHint);

        ui.show(ui.dialog().setTitle("Πρόοδος άσκησης").setView(box).setNegativeButton("Άκυρο", null)
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int low = range[0] == 0 ? 0 : Coach.RANGES[range[0] - 1][0];
                        int high = range[0] == 0 ? 0 : Coach.RANGES[range[0] - 1][1];
                        double st = step[0] == 0 ? 0 : Coach.STEPS[step[0] - 1];
                        db.setSettings(exerciseId, low, high, st);
                        after.run();
                    }
                }), false);
    }

    /** A scrolling row of pills; {@code selected[0]} follows the tapped one. */
    private View pills(String[] labels, final int[] selected) {
        final LinearLayout row = ui.row();
        final TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            views[i] = ui.pill(labels[i]);
            views[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected[0] = index;
                    for (int k = 0; k < views.length; k++) ui.stylePill(views[k], k == index);
                }
            });
            ui.stylePill(views[i], i == selected[0]);
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = ui.dp(6);
            row.addView(views[i], lp);
        }
        HorizontalScrollView h = new HorizontalScrollView(act);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        return h;
    }

    static void explainCoach(Ui ui) {
        ui.info("Πώς βγαίνει ο στόχος",
                "Ο στόχος βγαίνει από τις τελευταίες σου καταγραφές της άσκησης, από όποιο πρόγραμμα κι αν "
                        + "τις έγραψες.\n\n"
                        + "• Μένεις στα ίδια κιλά και ανεβάζεις reps. Όταν πιάσεις το πάνω όριο του εύρους σου "
                        + "σε όλα τα sets, ανεβαίνεις ένα βήμα κιλών (η μικρότερη αύξηση που χρησιμοποιείς).\n\n"
                        + "• Σε κάθε set που τσεκάρεις σε ρωτάει «Πώς ήταν;». Χαλαρό (έμεναν 3+ reps): +2 reps σε "
                        + "εκείνο το set την επόμενη φορά. Μέτριο (έμεναν 1–2): +1. Στο όριο: ίδια reps. Αν δεν "
                        + "απαντήσεις, μετράει σαν μέτριο. Ένα χαλαρό set που έμεινε 1–2 reps κάτω από το πάνω όριο "
                        + "μετράει σαν να το έπιασε, και τα reps στο νέο βάρος βγαίνουν από όσα μπορούσες να κάνεις.\n\n"
                        + "• Κάθε προπόνηση συγκρίνεται με τις συνηθισμένες σου. Μετά από κακή μέρα δεν σου λέει να "
                        + "κατέβεις: ξαναδοκιμάζεις τον ίδιο στόχο. Μετά από πολύ καλή μέρα ανεβαίνεις μόνο ένα "
                        + "βήμα, όχι άλμα. Το τι μετράει για σένα «κακή» ή «πολύ καλή» μέρα το μαθαίνει από το πόσο "
                        + "κυμαίνονται τα δικά σου νούμερα: στην αρχή −5% / +6%, και όσο γράφεις προπονήσεις "
                        + "προσαρμόζεται σε σένα (φαίνεται στο ιστορικό της άσκησης).\n\n"
                        + "• " + Coach.STALL_SESSIONS + " φορές στα ίδια κιλά χωρίς πρόοδο: κατεβαίνεις ~10% και "
                        + "ξαναχτίζεις. Μετά από " + Coach.LONG_BREAK_DAYS + "+ μέρες διάλειμμα ξεκινάς ~10% πιο "
                        + "ελαφριά, μετά από " + Coach.BREAK_DAYS + "+ κάνεις πρώτα ξανά τα ίδια.\n\n"
                        + "• Αν τα reps πέσουν πολύ κάτω από το εύρος σου (π.χ. ανέβηκες μόνος σου πολλά κιλά), "
                        + "σου λέει το βάρος που σε ξαναφέρνει μέσα στο εύρος.\n\n"

                        + "• Ποτέ πάνω από +" + Math.round(Coach.MAX_JUMP * 100) + "% σε μία φορά. Αν το επόμενο "
                        + "βάρος είναι μεγάλο άλμα (π.χ. ελαφριοί αλτήρες), κάνεις πρώτα λίγα reps παραπάνω.\n\n"
                        + "Το εύρος reps και το βήμα κιλών βγαίνουν αυτόματα από τα δεδομένα σου· μπορείς να τα "
                        + "αλλάξεις από την κάρτα του στόχου.");
    }

    void setTitle(String title, String subtitle) {
        titleView.setText(title);
        subtitleView.setText(subtitle == null ? "" : subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
