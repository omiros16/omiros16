package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Records, a progress chart and every session of one exercise (newest first). */
final class HistoryScreen extends Screen {
    private static final int CHART_POINTS = 100;

    private final long exerciseId;
    private Db.Exercise exercise;
    private final List<Session> sessions = new ArrayList<>();
    private ListView list;
    private SessionAdapter adapter;
    private LinearLayout stats;
    private LinearLayout chartCard;
    private TextView chartTitle;
    private ChartView chart;
    private TextView listTitle;

    HistoryScreen(MainActivity act, long exerciseId) {
        super(act);
        this.exerciseId = exerciseId;
    }

    @Override
    String key() {
        return "history:" + exerciseId;
    }

    @Override
    View build() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(BG);
        ImageView add = ui.iconButton(R.drawable.ic_add, "Καταγραφή");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(new ExerciseScreen(act, exerciseId, Fmt.today()));
            }
        });
        final ImageView more = ui.iconButton(R.drawable.ic_more, "Μενού");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                menu(more);
            }
        });
        root.addView(header(true, add, more));

        LinearLayout head = ui.column();
        head.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0);
        stats = ui.group();
        head.addView(stats, Ui.matchWrap());

        chartCard = ui.column();
        chartCard.setPadding(ui.dp(4), ui.dp(26), 0, 0);
        LinearLayout chartHead = ui.row();
        chartTitle = ui.text(14, SOFT, true);
        chartHead.addView(chartTitle, Ui.fill());
        View dot = new View(act);
        dot.setBackground(ui.round(MARK, 4));
        chartHead.addView(dot, ui.size(8, 8));
        TextView legend = ui.text("PR", 13, DIM, false);
        legend.setPadding(ui.dp(6), 0, ui.dp(4), 0);
        chartHead.addView(legend);
        chartCard.addView(chartHead);
        chart = new ChartView(act, ui.regular);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(180));
        clp.topMargin = ui.dp(12);
        chartCard.addView(chart, clp);
        head.addView(chartCard, Ui.matchWrap());

        listTitle = ui.label("Καταγραφές");
        listTitle.setPadding(ui.dp(4), ui.dp(26), 0, ui.dp(4));
        head.addView(listTitle);

        list = new ListView(act);
        list.setDivider(new InsetDrawable(new ColorDrawable(LINE), ui.dp(20), 0, 0, 0));
        list.setDividerHeight(1);
        list.setHeaderDividersEnabled(false);
        list.setSelector(new ColorDrawable(0));
        list.setClipToPadding(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setPadding(0, 0, 0, ui.dp(32));
        list.addHeaderView(head, null, false);
        adapter = new SessionAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Session s = sessionAt(position);
                if (s != null) act.push(new ExerciseScreen(act, exerciseId, s.day));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final Session s = sessionAt(position);
                if (s == null) return false;
                ui.confirm("Διαγραφή καταγραφής;", Fmt.dayLong(s.day) + "\n" + Fmt.sets(s), "Διαγραφή", true,
                        new Runnable() {
                            @Override
                            public void run() {
                                db.deleteSession(exerciseId, s.day);
                                refresh();
                            }
                        });
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private Session sessionAt(int position) {
        int i = position - list.getHeaderViewsCount();
        return i >= 0 && i < sessions.size() ? sessions.get(i) : null;
    }

    @Override
    void refresh() {
        exercise = db.exercise(exerciseId);
        if (exercise == null) {
            list.post(new Runnable() {
                @Override
                public void run() {
                    if (act.top() == HistoryScreen.this) act.pop();
                }
            });
            return;
        }
        String presets = db.presetNames(exerciseId);
        setTitle(exercise.name, presets.isEmpty() ? "Σε κανένα πρόγραμμα" : presets);

        List<Session> h = db.history(exerciseId);
        Strength.Bests b = Strength.markPrs(h);
        fillStats(h, b);
        fillChart(h);
        sessions.clear();
        for (int i = h.size() - 1; i >= 0; i--) sessions.add(h.get(i));
        listTitle.setVisibility(h.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void fillStats(List<Session> h, Strength.Bests b) {
        stats.removeAllViews();
        long today = Fmt.today();
        if (h.isEmpty()) {
            TextView t = ui.text("Δεν υπάρχουν καταγραφές ακόμα. Πάτα + πάνω δεξιά για να γράψεις την πρώτη.",
                    15, SOFT, false);
            t.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
            stats.addView(t);
            return;
        }
        // Where each record was set.
        long bestDay = 0;
        long heaviestDay = 0;
        long repsDay = 0;
        double best = -1;
        double heaviest = -1;
        int heaviestReps = 0;
        int mostReps = -1;
        int prSessions = 0;
        for (Session s : h) {
            if (s.flags != 0) prSessions++;
            for (int i = 0; i < s.n; i++) {
                double e = Strength.e1rm(s.weight[i], s.reps[i]);
                if (e > best + 1e-9) {
                    best = e;
                    bestDay = s.day;
                }
                long c = Strength.centi(s.weight[i]);
                if (c > Strength.centi(heaviest) || (c == Strength.centi(heaviest) && s.reps[i] > heaviestReps)) {
                    heaviest = s.weight[i];
                    heaviestReps = s.reps[i];
                    heaviestDay = s.day;
                }
                if (c == 0 && s.reps[i] > mostReps) {
                    mostReps = s.reps[i];
                    repsDay = s.day;
                }
            }
        }
        Coach.Advice next = db.advice(exerciseId, today + 1);
        if (next != null) {
            statRow(h.get(h.size() - 1).day == today ? "Επόμενη φορά" : "Στόχος", Fmt.target(next),
                    Fmt.arrow(next.kind) + " " + Fmt.kind(next.kind)).setTextColor(MARK);
            // What the coach has learned about this person's day-to-day spread in this exercise.
            statRow("Διακύμανση μέρας", "±" + Fmt.num1(next.spread * 100) + "%",
                    next.learned >= 3 ? "από τις " + next.learned + " τελευταίες προπονήσεις σου" : "αρχική εκτίμηση");
        }
        if (b.bestE1rm > 0) {
            statRow("Ρεκόρ ≈1RM", Fmt.num1(b.bestE1rm) + " kg", Fmt.kg(b.bestE1rmWeight) + " × " + b.bestE1rmReps
                    + ", " + Fmt.day(bestDay, today));
            statRow("Βαρύτερο", Fmt.kg(heaviest) + " kg × " + heaviestReps, Fmt.day(heaviestDay, today));
        }
        if (mostReps > 0) statRow("Ρεκόρ με BW", mostReps + " reps", Fmt.day(repsDay, today));
        statRow("Καταγραφές", String.valueOf(h.size()), "από " + Fmt.day(h.get(0).day, today));
        statRow("Μέρες με PR", String.valueOf(prSessions), null);
    }

    /** A label with its value (and a note under it); returns the label. */
    private TextView statRow(String label, String value, String note) {
        LinearLayout r = ui.row();
        r.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        TextView name = ui.text(label, 15, SOFT, false);
        r.addView(name, Ui.fill());
        LinearLayout right = ui.column();
        right.setGravity(Gravity.END);
        right.setPadding(ui.dp(12), 0, 0, 0);
        TextView v = ui.figures(20, TEXT, true);
        v.setText(Ui.quiet(value));
        v.setGravity(Gravity.END);
        right.addView(v);
        if (note != null) {
            TextView n = ui.text(note, 13, DIM, false);
            n.setGravity(Gravity.END);
            n.setPadding(0, ui.dp(3), 0, 0);
            right.addView(n);
        }
        r.addView(right);
        ui.addRow(stats, r);
        return name;
    }

    private void fillChart(List<Session> h) {
        boolean weighted = false;
        for (Session s : h) {
            if (s.weighted()) weighted = true;
        }
        List<Session> points = new ArrayList<>();
        for (Session s : h) {
            if (!weighted || s.bestE1rm() > 0) points.add(s);
        }
        if (points.size() > CHART_POINTS) points = points.subList(points.size() - CHART_POINTS, points.size());
        if (points.size() < 2) {
            chartCard.setVisibility(View.GONE);
            return;
        }
        chartCard.setVisibility(View.VISIBLE);
        chartTitle.setText(weighted ? "≈1RM ανά προπόνηση, kg" : "Max reps ανά προπόνηση");
        float[] v = new float[points.size()];
        boolean[] pr = new boolean[points.size()];
        for (int i = 0; i < v.length; i++) {
            Session s = points.get(i);
            v[i] = (float) (weighted ? s.bestE1rm() : s.maxReps());
            pr[i] = s.flags != 0;
        }
        long today = Fmt.today();
        chart.setData(v, pr, Fmt.day(points.get(0).day, today), Fmt.day(points.get(points.size() - 1).day, today));
    }

    private void menu(View anchor) {
        ui.menu(anchor, new String[]{"Εύρος reps & βήμα κιλών", "Μετονομασία", "Διαγραφή άσκησης", "Πώς βγαίνει ο στόχος",
                "Πώς μετράνε τα PR"}, new Ui.OnPick() {
            @Override
            public void run(int which) {
                if (exercise == null) return;
                if (which == 0) {
                    progressSettings(exerciseId, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } else if (which == 1) {
                    renameExercise(exercise, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } else if (which == 2) {
                    deleteExercise(exercise, new Runnable() {
                        @Override
                        public void run() {
                            act.pop();
                        }
                    });
                } else if (which == 3) {
                    explainCoach(ui);
                } else {
                    ExerciseScreen.explainPrs(ui);
                }
            }
        });
    }

    private final class SessionAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return sessions.size();
        }

        @Override
        public Session getItem(int position) {
            return sessions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return sessions.get(position).day;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                h = new Holder();
                LinearLayout item = ui.column();
                item.setBackground(ui.ripple(0, 0));
                item.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
                LinearLayout top = ui.row();
                h.date = ui.text(15, SOFT, ui.semibold);
                top.addView(h.date, Ui.fill());
                h.score = ui.text(13, DIM, false);
                top.addView(h.score);
                item.addView(top);
                h.sets = ui.figures(19, TEXT, true);
                h.sets.setPadding(0, ui.dp(6), 0, 0);
                item.addView(h.sets);
                h.feel = ui.text(13, DIM, false);
                h.feel.setPadding(0, ui.dp(5), 0, 0);
                item.addView(h.feel);
                h.pr = ui.row();
                h.pr.setPadding(0, ui.dp(6), 0, 0);
                h.pr.addView(ui.icon(R.drawable.ic_trophy, MARK), ui.size(16, 16));
                h.prText = ui.text(13, MARK, true);
                h.prText.setPadding(ui.dp(6), 0, 0, 0);
                h.pr.addView(h.prText);
                item.addView(h.pr);
                item.setTag(h);
                convertView = item;
            } else {
                h = (Holder) convertView.getTag();
            }
            Session s = sessions.get(position);
            h.date.setText(Fmt.day(s.day, Fmt.today()));
            double e = s.bestE1rm();
            h.score.setText(e > 0 ? "≈1RM " + Fmt.num1(e) + " kg" : "max " + s.maxReps() + " reps");
            h.sets.setText(Ui.quiet(Fmt.sets(s)));
            h.feel.setText(feelLine(s));
            h.feel.setVisibility(s.rated() ? View.VISIBLE : View.GONE);
            h.prText.setText(Fmt.badges(s.flags));
            h.pr.setVisibility(s.flags != 0 ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    /** "χαλαρό, μέτριο, στο όριο", each word in its colour; a dash for a set that was not rated. */
    private static CharSequence feelLine(Session s) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < s.n; i++) {
            if (i > 0) sb.append(", ");
            String word = s.feel[i] != 0 ? Fmt.feel(s.feel[i]) : "–";
            int start = sb.length();
            sb.append(word);
            sb.setSpan(new ForegroundColorSpan(Theme.feel(s.feel[i])), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static final class Holder {
        TextView date;
        TextView score;
        TextView sets;
        TextView feel;
        LinearLayout pr;
        TextView prText;
    }
}
