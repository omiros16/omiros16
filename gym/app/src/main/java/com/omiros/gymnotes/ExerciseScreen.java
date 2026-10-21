package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Logging one exercise on one day. Rows start with the day's target ({@link Coach}: last time's
 * warm-ups, then the suggested work sets); tap ✓ on each set when it is done and it is saved at
 * once (later edits to a ticked set save too). Every ticked set is judged against the whole history
 * for real PRs, see {@link Strength}. Once every set is ticked, the target for next time is shown.
 */
final class ExerciseScreen extends Screen {
    private static final int SAVE_DELAY_MS = 500;
    private static final String[] EFFORTS = {"Εύκολο", "Κανονικό", "Στο όριο"};

    private final long exerciseId;
    private long day;
    private Strength.Bests before = new Strength.Bests();
    private Session prev;
    private final List<Row> rows = new ArrayList<>();
    private boolean pendingSave;
    private boolean propagating;
    private double weightStep = 2.5;

    private ScrollView scroll;
    private TextView dateChip;
    private TextView prevTitle;
    private TextView prevSets;
    private TextView records;
    private TextView banner;
    private LinearLayout rowsBox;
    private TextView summary;
    private AdviceView adviceView;
    private AdviceView nextView;
    private LinearLayout effortRow;
    private final TextView[] effortChips = new TextView[3];

    private final Runnable saveTask = new Runnable() {
        @Override
        public void run() {
            save();
            judge(null);
        }
    };

    private final class Row {
        LinearLayout view;
        TextView num;
        EditText weight;
        EditText reps;
        ImageView check;
        boolean done;
        int flags;
    }

    /** A card with a {@link Coach.Advice}: what, why, and the range / step it used. */
    private final class AdviceView {
        final LinearLayout card;
        final TextView title;
        final TextView target;
        final TextView reason;
        final TextView meta;

        AdviceView() {
            card = ui.column();
            card.setBackground(ui.outline(ACCENT_BG, 0x665B9DFF, 16));
            card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
            title = ui.text(13, ACCENT_SOFT, true);
            target = ui.text(21, TEXT, true);
            target.setFontFeatureSettings("tnum");
            target.setPadding(0, ui.dp(4), 0, 0);
            reason = ui.text(14, SOFT, false);
            reason.setLineSpacing(0, 1.12f);
            reason.setPadding(0, ui.dp(6), 0, 0);
            meta = ui.text(12, ACCENT_SOFT, false);
            meta.setPadding(0, ui.dp(10), 0, ui.dp(2));
            meta.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    changeSettings();
                }
            });
            card.addView(title);
            card.addView(target);
            card.addView(reason);
            card.addView(meta);
        }

        void show(Coach.Advice a, String when) {
            if (a == null) {
                card.setVisibility(View.GONE);
                return;
            }
            card.setVisibility(View.VISIBLE);
            title.setText(when + "  ·  " + Fmt.arrow(a.kind) + " " + Fmt.kind(a.kind));
            target.setText(Fmt.target(a));
            reason.setText(a.reason);
            meta.setText("Εύρος " + Coach.rangeLabel(a.low, a.high) + " reps" + (a.autoRange ? " (αυτόματο)" : "")
                    + "  ·  βήμα " + Fmt.kg(a.step) + " kg" + (a.autoStep ? " (αυτόματο)" : "") + "  ·  αλλαγή ›");
        }
    }

    ExerciseScreen(MainActivity act, long exerciseId, long day) {
        super(act);
        this.exerciseId = exerciseId;
        this.day = day;
    }

    @Override
    String key() {
        return "log:" + exerciseId + ":" + day;
    }

    @Override
    View build() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(BG);
        ImageView history = ui.iconButton(R.drawable.ic_chart, "Ιστορικό");
        history.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(new HistoryScreen(act, exerciseId));
            }
        });
        root.addView(header(true, history));

        scroll = new ScrollView(act);
        LinearLayout col = ui.column();
        col.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(28));
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout dateRow = ui.row();
        dateChip = ui.pill("");
        dateChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });
        dateRow.addView(dateChip, Ui.wrap());
        col.addView(dateRow);

        adviceView = new AdviceView();
        col.addView(adviceView.card, ui.spaced(12));

        LinearLayout info = ui.column();
        info.setBackground(ui.round(CARD, 16));
        info.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        prevTitle = ui.text(13, DIM, true);
        prevSets = ui.text(17, TEXT, true);
        prevSets.setPadding(0, ui.dp(3), 0, 0);
        records = ui.text(13, SOFT, false);
        records.setPadding(0, ui.dp(8), 0, 0);
        info.addView(prevTitle);
        info.addView(prevSets);
        info.addView(records);
        col.addView(info, ui.spaced(12));

        banner = ui.text(15, GOLD, true);
        banner.setBackground(ui.outline(GOLD_BG, 0x66FFC94D, 16));
        banner.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        banner.setLineSpacing(0, 1.15f);
        banner.setVisibility(View.GONE);
        col.addView(banner, ui.spaced(12));

        LinearLayout heads = ui.row();
        heads.setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(4));
        heads.addView(ui.text("Set", 12, DIM, true),
                new LinearLayout.LayoutParams(ui.dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView kgHead = ui.text("Κιλά", 12, DIM, true);
        kgHead.setGravity(Gravity.CENTER);
        heads.addView(kgHead, Ui.fill());
        TextView repsHead = ui.text("Reps", 12, DIM, true);
        repsHead.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = Ui.fill();
        rlp.leftMargin = ui.dp(6);
        heads.addView(repsHead, rlp);
        TextView doneHead = ui.text("Έγινε", 12, DIM, true);
        doneHead.setGravity(Gravity.CENTER);
        heads.addView(doneHead, new LinearLayout.LayoutParams(ui.dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(heads);

        rowsBox = ui.column();
        col.addView(rowsBox);

        TextView addSet = ui.button("+ Set", false);
        addSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSetRow();
            }
        });
        col.addView(addSet, ui.spaced(10));

        effortRow = ui.column();
        effortRow.addView(ui.text("Πώς ήταν; (προαιρετικό, βοηθάει τον στόχο)", 13, DIM, true));
        LinearLayout chips = ui.row();
        for (int i = 0; i < 3; i++) {
            final int effort = i + 1;
            effortChips[i] = ui.pill(EFFORTS[i]);
            effortChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rate(effort);
                }
            });
            LinearLayout.LayoutParams lp = Ui.fill();
            if (i < 2) lp.rightMargin = ui.dp(8);
            chips.addView(effortChips[i], lp);
        }
        effortRow.addView(chips, ui.spaced(8));
        col.addView(effortRow, ui.spaced(18));

        nextView = new AdviceView();
        col.addView(nextView.card, ui.spaced(14));

        summary = ui.text(14, SOFT, false);
        summary.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), 0);
        summary.setLineSpacing(0, 1.15f);
        col.addView(summary);

        TextView how = ui.text("ⓘ  Πώς μετράνε τα PR", 14, ACCENT_SOFT, true);
        how.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), ui.dp(8));
        how.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainPrs(ui);
            }
        });
        col.addView(how, Ui.wrap());
        TextView coach = ui.text("ⓘ  Πώς βγαίνει ο στόχος", 14, ACCENT_SOFT, true);
        coach.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(8));
        coach.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainCoach(ui);
            }
        });
        col.addView(coach, Ui.wrap());
        return root;
    }

    @Override
    void refresh() {
        flush();
        load();
    }

    /** Coming back to the app keeps half-typed rows as they are. */
    @Override
    void onResume() {
    }

    @Override
    void onHide() {
        flush();
        ui.hideKeyboard(scroll);
    }

    private void flush() {
        if (pendingSave) save();
    }

    private void load() {
        Db.Exercise ex = db.exercise(exerciseId);
        if (ex == null) {
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    if (act.top() == ExerciseScreen.this) act.pop();
                }
            });
            return;
        }
        long today = Fmt.today();
        setTitle(ex.name, null);
        dateChip.setText(Fmt.dayWithDate(day, today) + "  ▾");
        ui.stylePill(dateChip, day != today);

        before = db.bestsBefore(exerciseId, day);
        long prevDay = db.lastDayBefore(exerciseId, day);
        prev = prevDay == Db.NO_DAY ? null : db.session(exerciseId, prevDay);
        Session cur = db.session(exerciseId, day);

        if (prev != null) {
            prevTitle.setText("Προηγούμενη φορά  ·  " + Fmt.day(prev.day, today));
            prevSets.setText(Fmt.sets(prev));
            prevSets.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        } else {
            prevTitle.setText("Πρώτη φορά");
            prevSets.setText("Ό,τι γράψεις σήμερα γίνεται η βάση σου· από την επόμενη φορά θα βλέπεις PR.");
            prevSets.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
        if (before.sets == 0) {
            records.setVisibility(View.GONE);
        } else {
            records.setVisibility(View.VISIBLE);
            if (before.bestE1rm > 0) {
                records.setText("Ρεκόρ ≈1RM " + Fmt.num1(before.bestE1rm) + " kg  (" + Fmt.kg(before.bestE1rmWeight)
                        + " × " + before.bestE1rmReps + ")\nΒαρύτερο " + Fmt.kg(before.maxWeight) + " kg × "
                        + before.maxWeightReps);
            } else {
                records.setText("Ρεκόρ " + before.repsAtOrAbove(0) + " reps με σωματικό βάρος");
            }
        }

        Coach.Advice advice = showAdvice();

        // The plan for the day: last time's warm-ups, then the suggested work sets.
        List<String[]> plan = new ArrayList<>();
        if (prev != null) {
            double top = Coach.work(prev).top;
            for (int i = 0; i < prev.n && Coach.isWarmUp(prev.weight[i], top); i++) {
                plan.add(new String[]{Fmt.kg(prev.weight[i]), String.valueOf(prev.reps[i])});
            }
        }
        if (advice != null) {
            for (int r : advice.reps) plan.add(new String[]{Fmt.kg(advice.weight), String.valueOf(r)});
        }
        propagating = true;
        rows.clear();
        rowsBox.removeAllViews();
        for (int i = 0; i < cur.n; i++) addRow(Fmt.kg(cur.weight[i]), String.valueOf(cur.reps[i]), true);
        for (int i = cur.n; i < plan.size(); i++) addRow(plan.get(i)[0], plan.get(i)[1], false);
        if (rows.isEmpty()) addRow("", "", false);
        propagating = false;
        judge(null);
    }

    /** The target for this day, from the sessions before it; also sets the −/+ weight step. */
    private Coach.Advice showAdvice() {
        Coach.Advice advice = db.advice(exerciseId, day);
        Coach.Settings settings = db.settings(exerciseId);
        weightStep = advice != null ? advice.step : settings.step > 0 ? settings.step : 2.5;
        adviceView.show(advice, day == Fmt.today() ? "Στόχος σήμερα" : "Στόχος γι' αυτή τη μέρα");
        return advice;
    }

    private void changeSettings() {
        progressSettings(exerciseId, new Runnable() {
            @Override
            public void run() {
                boolean started = false;
                for (Row r : rows) started |= r.done;
                if (started) {
                    showAdvice();
                    judge(null);
                } else {
                    load();
                }
            }
        });
    }

    private void rate(int effort) {
        int now = db.effort(exerciseId, day) == effort ? 0 : effort;
        db.setEffort(exerciseId, day, now);
        judge(null);
    }

    /**
     * Effort chips once something is ticked; next time's target once every set is ticked, scrolled
     * into view when a tick just finished the exercise.
     */
    private void showOutcome(int done, boolean finished, boolean ticked) {
        boolean wasShown = nextView.card.getVisibility() == View.VISIBLE;
        effortRow.setVisibility(done > 0 ? View.VISIBLE : View.GONE);
        int effort = done > 0 ? db.effort(exerciseId, day) : 0;
        for (int i = 0; i < 3; i++) ui.stylePill(effortChips[i], effort == i + 1);
        Coach.Advice next = null;
        if (done > 0 && finished) {
            next = Coach.next(db.recent(exerciseId, day + 1, Coach.RECENT), db.coachSettings(exerciseId), day);
        }
        nextView.show(next, "Επόμενη φορά");
        if (next != null && !wasShown && ticked) {
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    int bottom = nextView.card.getBottom() + ui.dp(12) - scroll.getHeight();
                    if (bottom > scroll.getScrollY()) scroll.smoothScrollTo(0, bottom);
                }
            });
        }
    }

    // ---------------------------------------------------------------- rows

    private void addSetRow() {
        String w = "";
        String r = "";
        if (!rows.isEmpty()) {
            Row last = rows.get(rows.size() - 1);
            w = last.weight.getText().toString();
            r = last.reps.getText().toString();
        }
        propagating = true;
        addRow(w, r, false);
        propagating = false;
        judge(null);
    }

    private void addRow(String w, String r, boolean done) {
        final Row row = new Row();
        row.done = done;
        row.view = ui.row();
        row.view.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));

        row.num = ui.text(15, DIM, true);
        row.num.setGravity(Gravity.CENTER);
        row.view.addView(row.num, ui.size(30, 30));

        row.weight = numberField(true);
        row.weight.setText(w);
        row.view.addView(stepperGroup(row.weight, true), Ui.fill());

        row.reps = numberField(false);
        row.reps.setText(r);
        LinearLayout.LayoutParams rlp = Ui.fill();
        rlp.leftMargin = ui.dp(6);
        row.view.addView(stepperGroup(row.reps, false), rlp);

        row.check = new ImageView(act);
        row.check.setImageResource(R.drawable.ic_check);
        row.check.setScaleType(ImageView.ScaleType.CENTER);
        row.check.setContentDescription("Έγινε");
        row.check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle(row);
            }
        });
        LinearLayout.LayoutParams clp = ui.size(42, 42);
        clp.leftMargin = ui.dp(6);
        row.view.addView(row.check, clp);

        row.weight.addTextChangedListener(new Watcher() {
            @Override
            void changed(String old, String now) {
                propagateWeight(row, old, now);
                edited(row);
            }
        });
        row.reps.addTextChangedListener(new Watcher() {
            @Override
            void changed(String old, String now) {
                edited(row);
            }
        });
        View.OnLongClickListener remove = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                rowMenu(row);
                return true;
            }
        };
        row.num.setOnLongClickListener(remove);
        row.view.setOnLongClickListener(remove);

        rows.add(row);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = ui.dp(6);
        rowsBox.addView(row.view, lp);
    }

    private EditText numberField(boolean decimal) {
        EditText e = new EditText(act);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setHint(decimal ? "kg" : "reps");
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        e.setFontFeatureSettings("tnum");
        e.setGravity(Gravity.CENTER);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setInputType(decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(decimal ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        e.setBackground(ui.round(FIELD, 10));
        e.setPadding(0, ui.dp(8), 0, ui.dp(8));
        e.setMinWidth(0);
        e.setMinimumWidth(0);
        return e;
    }

    /** [−] field [+] */
    private View stepperGroup(final EditText field, final boolean decimal) {
        LinearLayout g = ui.row();
        TextView minus = ui.stepper("−");
        TextView plus = ui.stepper("+");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(field, decimal, -1);
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(field, decimal, 1);
            }
        });
        g.addView(minus, ui.size(32, 44));
        g.addView(field, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        g.addView(plus, ui.size(32, 44));
        return g;
    }

    private void step(EditText field, boolean decimal, int dir) {
        String t = field.getText().toString();
        if (decimal) {
            field.setText(Fmt.kg(Math.max(0, Fmt.parseKg(t) + dir * weightStep)));
        } else {
            field.setText(String.valueOf(Math.max(0, Fmt.parseReps(t) + dir)));
        }
    }

    /** Changing the weight of a set carries over to the later, not yet done sets that had the same weight. */
    private void propagateWeight(Row row, String old, String now) {
        if (propagating || old.equals(now)) return;
        propagating = true;
        for (int k = rows.indexOf(row) + 1; k < rows.size(); k++) {
            Row o = rows.get(k);
            if (!o.done && o.weight.getText().toString().equals(old)) o.weight.setText(now);
        }
        propagating = false;
    }

    private void edited(Row row) {
        if (propagating || !row.done) return;
        pendingSave = true;
        scroll.removeCallbacks(saveTask);
        scroll.postDelayed(saveTask, SAVE_DELAY_MS);
    }

    private void toggle(Row row) {
        if (!row.done && Fmt.parseReps(row.reps.getText().toString()) <= 0) {
            ui.toast("Γράψε πόσα reps έκανες");
            row.reps.requestFocus();
            return;
        }
        row.done = !row.done;
        if (row.done) {
            // Show the value as it is stored ("52,5" -> "52.5", "" -> "0").
            propagating = true;
            row.weight.setText(Fmt.kg(Fmt.parseKg(row.weight.getText().toString())));
            row.reps.setText(String.valueOf(Fmt.parseReps(row.reps.getText().toString())));
            propagating = false;
        }
        row.weight.clearFocus();
        row.reps.clearFocus();
        ui.hideKeyboard(row.view);
        save();
        judge(row);
    }

    private void rowMenu(final Row row) {
        ui.menu(row.num, new String[]{"Διαγραφή set"}, new Ui.OnPick() {
            @Override
            public void run(int which) {
                rows.remove(row);
                rowsBox.removeView(row.view);
                if (row.done) save();
                judge(null);
            }
        });
    }

    private void save() {
        scroll.removeCallbacks(saveTask);
        pendingSave = false;
        Session s = new Session(day);
        for (Row r : rows) {
            if (!r.done) continue;
            int reps = Fmt.parseReps(r.reps.getText().toString());
            if (reps > 0) s.add(Fmt.parseKg(r.weight.getText().toString()), reps);
        }
        db.saveSession(exerciseId, s);
    }

    /**
     * Re-scores the ticked sets in order against the history and the earlier sets of the day,
     * then updates numbers, colours, the PR banner and the comparison with last time.
     */
    private void judge(Row trigger) {
        Strength.Bests running = before.copy();
        boolean hasHistory = before.sets > 0;
        String prMessage = null;
        Session today = new Session(day);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int f = 0;
            if (r.done) {
                double w = Fmt.parseKg(r.weight.getText().toString());
                int reps = Fmt.parseReps(r.reps.getText().toString());
                if (reps > 0) {
                    if (hasHistory) f = running.judge(w, reps);
                    if (f != 0) prMessage = Fmt.describe(f, w, reps, running);
                    running.add(w, reps);
                    today.add(w, reps);
                }
            }
            boolean newPr = f != 0 && r.flags == 0;
            r.flags = f;
            style(r, i + 1);
            if (r == trigger && newPr) {
                r.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                ui.toast("🏆 Νέο PR!");
            }
        }
        if (prMessage != null) {
            banner.setText("🏆  Νέο PR!\n" + prMessage);
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }
        boolean finished = true;
        for (Row r : rows) finished &= r.done;
        showOutcome(today.n, finished, trigger != null && trigger.done);
        summary.setText(today.n == 0
                ? "Πάτα ✓ σε κάθε set μόλις το τελειώσεις· αποθηκεύεται αμέσως και φαίνεται σε όλα τα "
                + "προγράμματα με αυτή την άσκηση."
                : (day == Fmt.today() ? "Σήμερα: " : "Αυτή τη μέρα: ") + Fmt.setCount(today.n) + compare(today));
    }

    /** Today against last time by strength (best estimated 1RM, or max reps for bodyweight), not volume. */
    private String compare(Session today) {
        if (today.weighted() || (prev != null && prev.weighted())) {
            double now = today.bestE1rm();
            if (now <= 0) return "";
            String s = "  ·  ≈1RM " + Fmt.num1(now) + " kg";
            if (prev != null && prev.bestE1rm() > 0) {
                double d = now - prev.bestE1rm();
                s += Math.abs(d) < 0.05 ? "\nΊδιο με την προηγούμενη φορά"
                        : "\n" + Fmt.signed1(d) + " kg από την προηγούμενη φορά";
            }
            return s;
        }
        int now = today.maxReps();
        String s = "  ·  max " + now + " reps";
        if (prev != null) {
            int d = now - prev.maxReps();
            s += d == 0 ? "\nΊδιο με την προηγούμενη φορά" : "\n" + (d > 0 ? "+" : "−") + Math.abs(d)
                    + " reps από την προηγούμενη φορά";
        }
        return s;
    }

    private void style(Row r, int number) {
        r.view.setBackground(ui.round(r.done ? GREEN_BG : CARD, 14));
        if (r.flags != 0) {
            r.num.setText("🏆");
            r.num.setBackground(ui.round(GOLD_BG, 15));
        } else {
            r.num.setText(String.valueOf(number));
            r.num.setBackground(null);
            r.num.setTextColor(r.done ? GREEN : DIM);
        }
        if (r.done) {
            r.check.setBackground(ui.ripple(GREEN, 21));
            r.check.setImageTintList(ColorStateList.valueOf(BG));
        } else {
            r.check.setBackground(ui.ripple(ui.outline(0, DIM, 21), 21));
            r.check.setImageTintList(ColorStateList.valueOf(DIM));
        }
    }

    private void pickDate() {
        LocalDate d = LocalDate.ofEpochDay(day);
        DatePickerDialog dlg = new DatePickerDialog(act, R.style.DialogTheme, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int y, int m, int dd) {
                long picked = LocalDate.of(y, m + 1, dd).toEpochDay();
                if (picked == day) return;
                flush();
                day = Math.min(picked, Fmt.today());
                load();
            }
        }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        dlg.getDatePicker().setMaxDate(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli() - 1);
        dlg.show();
        ui.styleButtons(dlg, false);
    }

    static void explainPrs(Ui ui) {
        ui.info("Πώς μετράνε τα PR",
                "Δεν μετράει το κιλά × reps: αλλιώς 1 kg × 100 θα «νικούσε» τα 100 kg × 5.\n\n"
                        + "Ένα set είναι PR μόνο αν, σε σχέση με όλες τις προηγούμενες φορές της άσκησης "
                        + "(από όποιο πρόγραμμα κι αν τις έγραψες):\n\n"
                        + "• PR βάρους: σήκωσες περισσότερα κιλά από ποτέ.\n\n"
                        + "• PR 1RM: το εκτιμώμενο μέγιστο για 1 επανάληψη (τύπος Epley: κιλά × (1 + reps/30)) "
                        + "ξεπέρασε το ρεκόρ σου. Μετράνε έως " + Strength.REP_CAP + " reps: ένα set των 20 "
                        + "λογίζεται σαν " + Strength.REP_CAP + ", ώστε τα ελαφριά set με πολλές επαναλήψεις να "
                        + "μη βγάζουν ψεύτικη δύναμη.\n\n"
                        + "• PR reps: περισσότερες επαναλήψεις από κάθε άλλη φορά με αυτά τα κιλά ή και "
                        + "βαρύτερα. Μετράει μόνο σε κιλά που έχεις ξαναδουλέψει και είναι τουλάχιστον "
                        + Math.round(Strength.REP_PR_MIN_SHARE * 100) + "% του βαρύτερού σου (ή σωματικό "
                        + "βάρος).\n\n"
                        + "Η πρώτη φορά μιας άσκησης είναι η βάση, γι' αυτό δεν βγάζει PR. "
                        + "Τα 0 kg σημαίνουν σωματικό βάρος (BW).");
    }

    /** TextWatcher that reports old and new text once the edit is applied. */
    private abstract static class Watcher implements TextWatcher {
        private String old = "";

        abstract void changed(String old, String now);

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            old = s.toString();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            changed(old, s.toString());
        }
    }
}
