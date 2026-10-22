package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

/** One day's program: its exercises with what was done last time, wherever it was logged. */
final class PresetScreen extends Screen {
    private final long presetId;
    private String name = "";
    private TextView empty;
    private LinearLayout list;
    private LinearLayout add;

    PresetScreen(MainActivity act, long presetId) {
        super(act);
        this.presetId = presetId;
    }

    @Override
    String key() {
        return "preset:" + presetId;
    }

    @Override
    View build() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(BG);
        final ImageView more = ui.iconButton(R.drawable.ic_more, "Μενού");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                presetMenu(more);
            }
        });
        root.addView(header(true, more));

        ScrollView sv = new ScrollView(act);
        LinearLayout col = ui.column();
        col.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(32));
        sv.addView(col);
        empty = ui.text("Δεν έχει ασκήσεις ακόμα. Διάλεξε από τη λίστα σου ή φτιάξε καινούργια.", 15, SOFT, false);
        empty.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(16));
        col.addView(empty);
        list = ui.group();
        col.addView(list, Ui.matchWrap());
        add = ui.actionRow(R.drawable.ic_add, "Προσθήκη άσκησης", null);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
            }
        });
        TextView hint = ui.text("Πάτα μια άσκηση για να γράψεις τα sets της. Ό,τι γράφεις φαίνεται σε κάθε "
                + "πρόγραμμα που έχει την ίδια άσκηση.", 13, DIM, false);
        hint.setPadding(ui.dp(4), ui.dp(16), ui.dp(4), 0);
        col.addView(hint);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    @Override
    void refresh() {
        String n = db.presetName(presetId);
        if (n == null) {
            list.post(new Runnable() {
                @Override
                public void run() {
                    if (act.top() == PresetScreen.this) act.pop();
                }
            });
            return;
        }
        name = n;
        long today = Fmt.today();
        List<Db.Exercise> items = db.presetExercises(presetId);
        int done = 0;
        for (Db.Exercise e : items) {
            if (e.lastDay == today) done++;
        }
        setTitle(name, items.isEmpty() ? null : done == items.size() ? "Όλες έγιναν σήμερα"
                : "Σήμερα " + done + " από " + items.size());

        list.removeAllViews();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (Db.Exercise e : items) ui.addRow(list, card(e, today));
        ui.addRow(list, add);
    }

    private View card(final Db.Exercise e, long today) {
        final LinearLayout card = ui.column();
        card.setBackground(ui.ripple(0, 0));
        card.setPadding(ui.dp(18), ui.dp(8), ui.dp(4), ui.dp(16));

        LinearLayout top = ui.row();
        TextView title = ui.text(e.name, 17, TEXT, ui.semibold);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, Ui.fill());
        if (e.lastDay == today) {
            ImageView done = ui.icon(R.drawable.ic_check, BG);
            done.setBackground(ui.round(TEXT, 11));
            done.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            done.setScaleType(ImageView.ScaleType.FIT_CENTER);
            done.setContentDescription("Έγινε σήμερα");
            top.addView(done, ui.size(22, 22));
        }
        final ImageView more = ui.iconButton(R.drawable.ic_more, "Επιλογές");
        more.setImageTintList(ColorStateList.valueOf(DIM));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                itemMenu(e, more);
            }
        });
        top.addView(more);
        card.addView(top);

        // Done today: what to do next time; otherwise: today's target.
        Coach.Advice advice = db.advice(e.id, e.lastDay == today ? today + 1 : today);
        if (advice != null) {
            LinearLayout next = ui.row();
            next.setGravity(Gravity.BOTTOM);
            next.setPadding(0, 0, ui.dp(14), ui.dp(6));
            next.addView(ui.text(e.lastDay == today ? "Επόμενη φορά" : "Στόχος", 14, MARK, true));
            TextView target = ui.figures(20, TEXT, true);
            target.setText(Ui.quiet(Fmt.target(advice)));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = ui.dp(10);
            next.addView(target, lp);
            card.addView(next);
        }

        TextView last;
        if (e.lastDay == Db.NO_DAY) {
            last = ui.text("Δεν έχει γίνει ακόμα, πάτα για να ξεκινήσεις", 14, DIM, false);
        } else {
            Session s = db.session(e.id, e.lastDay);
            last = ui.text(Fmt.day(e.lastDay, today) + ": " + Fmt.sets(s), 14, SOFT, false);
        }
        last.setPadding(0, 0, ui.dp(14), 0);
        card.addView(last);

        if (e.lastPr != 0) card.addView(prLine(Fmt.badges(e.lastPr)
                + (e.lastDay == today ? " σήμερα" : " την τελευταία φορά")));

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(new ExerciseScreen(act, e.id, Fmt.today()));
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                itemMenu(e, more);
                return true;
            }
        });
        return card;
    }

    private View prLine(String text) {
        LinearLayout line = ui.row();
        line.setPadding(0, ui.dp(6), 0, 0);
        line.addView(ui.icon(R.drawable.ic_trophy, MARK), ui.size(16, 16));
        TextView t = ui.text(text, 13, MARK, true);
        t.setPadding(ui.dp(6), 0, 0, 0);
        line.addView(t);
        return line;
    }

    private void itemMenu(final Db.Exercise e, View anchor) {
        ui.menu(anchor, new String[]{"Ιστορικό & γράφημα", "Μετακίνηση πάνω", "Μετακίνηση κάτω",
                "Αφαίρεση από το πρόγραμμα"}, new Ui.OnPick() {
            @Override
            public void run(int which) {
                switch (which) {
                    case 0:
                        act.push(new HistoryScreen(act, e.id));
                        break;
                    case 1:
                    case 2:
                        db.moveInPreset(presetId, e.id, which == 1 ? -1 : 1);
                        refresh();
                        break;
                    case 3:
                        db.removeFromPreset(presetId, e.id);
                        refresh();
                        ui.toast("Αφαιρέθηκε από το πρόγραμμα (το ιστορικό της μένει)");
                        break;
                }
            }
        });
    }

    private void presetMenu(View anchor) {
        ui.menu(anchor, new String[]{"Μετονομασία", "Διαγραφή προγράμματος"}, new Ui.OnPick() {
            @Override
            public void run(int which) {
                if (which == 0) {
                    ui.prompt("Μετονομασία", "Όνομα προγράμματος", name, "Αποθήκευση", new Ui.OnText() {
                        @Override
                        public void run(String n) {
                            db.renamePreset(presetId, n);
                            refresh();
                        }
                    });
                } else {
                    HomeScreen.confirmDeletePreset(ui, db, presetId, name, new Runnable() {
                        @Override
                        public void run() {
                            act.pop();
                        }
                    });
                }
            }
        });
    }

    /** Search the exercise list, tap to add; a name that does not exist yet can be created right here. */
    private void showPicker() {
        final Set<Long> inPreset = db.presetExerciseIds(presetId);
        final List<Db.Exercise> all = db.exercises();

        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        final EditText query = ui.field("Αναζήτηση ή όνομα νέας άσκησης");
        content.addView(query, Ui.matchWrap());
        ScrollView sv = new ScrollView(act);
        final LinearLayout box = ui.column();
        box.setPadding(0, ui.dp(6), 0, ui.dp(6));
        sv.addView(box);
        int h = Math.min(ui.dp(340), (int) (act.getResources().getDisplayMetrics().heightPixels * 0.42f));
        content.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        final AlertDialog dialog = ui.dialog().setTitle("Προσθήκη άσκησης").setView(content)
                .setPositiveButton("Τέλος", null).create();

        final Runnable[] render = new Runnable[1];
        final Ui.OnText add = new Ui.OnText() {
            @Override
            public void run(String exerciseName) {
                long id = db.addExercise(exerciseName);
                db.addToPreset(presetId, id);
                inPreset.add(id);
                boolean known = false;
                for (Db.Exercise e : all) {
                    if (e.id == id) known = true;
                }
                if (!known) {
                    Db.Exercise e = db.exercise(id);
                    if (e != null) all.add(e);
                }
                ui.toast("Προστέθηκε: " + exerciseName);
                query.setText("");
                render[0].run();
                refresh();
            }
        };
        render[0] = new Runnable() {
            @Override
            public void run() {
                box.removeAllViews();
                String typed = Db.clean(query.getText().toString());
                String k = Db.key(typed);
                boolean exact = false;
                for (Db.Exercise e : all) {
                    if (Db.key(e.name).equals(k)) exact = true;
                }
                if (!typed.isEmpty() && !exact) {
                    box.addView(pickRow("Νέα άσκηση «" + typed + "»", MARK, true, typed, add));
                }
                for (Db.Exercise e : all) {
                    if (!k.isEmpty() && !Db.key(e.name).contains(k)) continue;
                    boolean already = inPreset.contains(e.id);
                    box.addView(pickRow(e.name, already ? DIM : TEXT, !already, e.name, add));
                }
                if (box.getChildCount() == 0) {
                    TextView t = ui.text("Γράψε το όνομα μιας άσκησης για να τη φτιάξεις.", 14, DIM, false);
                    t.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));
                    box.addView(t);
                }
            }
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render[0].run();
            }
        });
        render[0].run();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        ui.styleButtons(dialog, false);
    }

    /** A name to tap; one already in the program is greyed out with a tick. */
    private View pickRow(String label, int color, boolean enabled, final String exerciseName, final Ui.OnText onPick) {
        TextView t = ui.text(label, 16, color, enabled ? ui.medium : ui.regular);
        t.setPadding(ui.dp(10), ui.dp(13), ui.dp(10), ui.dp(13));
        if (enabled) {
            t.setBackground(ui.ripple(0, 10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onPick.run(exerciseName);
                }
            });
        } else {
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_check, 0);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(DIM));
            t.setContentDescription(label + ", ήδη στο πρόγραμμα");
        }
        return t;
    }
}
