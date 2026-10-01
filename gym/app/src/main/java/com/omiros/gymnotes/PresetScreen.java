package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
    private LinearLayout list;

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
        col.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(28));
        sv.addView(col);
        list = ui.column();
        col.addView(list);
        TextView add = ui.button("+ Προσθήκη άσκησης", false);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
            }
        });
        col.addView(add, ui.spaced(12));
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
        setTitle(name, items.isEmpty() ? null : "Σήμερα " + done + "/" + items.size()
                + (done == items.size() ? " ✓ όλες έγιναν" : " ασκήσεις"));

        list.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = ui.text("Δεν έχει ασκήσεις ακόμα.\nΠάτα «+ Προσθήκη άσκησης» για να διαλέξεις "
                    + "από τη λίστα σου ή να φτιάξεις καινούργια.", 15, SOFT, false);
            empty.setLineSpacing(0, 1.15f);
            empty.setBackground(ui.round(CARD, 16));
            empty.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
            list.addView(empty, Ui.matchWrap());
        }
        for (int i = 0; i < items.size(); i++) {
            list.addView(card(items.get(i), today), ui.spaced(i == 0 ? 0 : 10));
        }
    }

    private View card(final Db.Exercise e, long today) {
        final LinearLayout card = ui.column();
        card.setBackground(ui.ripple(CARD, 16));
        card.setPadding(ui.dp(18), ui.dp(10), ui.dp(6), ui.dp(14));

        LinearLayout top = ui.row();
        TextView title = ui.text(e.name, 17, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, Ui.fill());
        if (e.lastDay == today) {
            TextView done = ui.text("✓ σήμερα", 12, GREEN, true);
            done.setBackground(ui.round(GREEN_BG, 10));
            done.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
            top.addView(done);
        }
        final ImageView more = ui.iconButton(R.drawable.ic_more, "Επιλογές");
        more.setImageTintList(ColorStateList.valueOf(DIM));
        more.setLayoutParams(ui.size(40, 40));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                itemMenu(e, more);
            }
        });
        top.addView(more);
        card.addView(top);

        TextView last;
        if (e.lastDay == Db.NO_DAY) {
            last = ui.text("Δεν έχει γίνει ακόμα, πάτα για να ξεκινήσεις", 14, DIM, false);
        } else {
            Session s = db.session(e.id, e.lastDay);
            last = ui.text(Fmt.day(e.lastDay, today) + "  ·  " + Fmt.sets(s), 15, SOFT, false);
        }
        last.setPadding(0, 0, ui.dp(10), 0);
        card.addView(last);

        if (e.bestE1rm > 0) {
            TextView record = ui.text("Ρεκόρ ≈1RM " + Fmt.num1(e.bestE1rm) + " kg  ·  βαρύτερο "
                    + Fmt.kg(e.maxWeight) + " kg", 12, DIM, false);
            record.setPadding(0, ui.dp(4), 0, 0);
            card.addView(record);
        }
        if (e.lastPr != 0) {
            TextView pr = ui.text("🏆 " + Fmt.badges(e.lastPr)
                    + (e.lastDay == today ? " σήμερα" : " την τελευταία φορά"), 12, GOLD, true);
            pr.setPadding(0, ui.dp(4), 0, 0);
            card.addView(pr);
        }

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
        content.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
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
                    box.addView(pickRow("+ Νέα άσκηση «" + typed + "»", ACCENT_SOFT, true, typed, add));
                }
                for (Db.Exercise e : all) {
                    if (!k.isEmpty() && !Db.key(e.name).contains(k)) continue;
                    boolean already = inPreset.contains(e.id);
                    box.addView(pickRow(already ? "✓  " + e.name : e.name, already ? DIM : TEXT, !already, e.name, add));
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

    private View pickRow(String label, int color, boolean enabled, final String exerciseName, final Ui.OnText onPick) {
        TextView t = ui.text(label, 16, color, enabled);
        t.setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12));
        if (enabled) {
            t.setBackground(ui.ripple(0, 10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onPick.run(exerciseName);
                }
            });
        }
        return t;
    }
}
