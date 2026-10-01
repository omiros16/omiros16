package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
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
        add.setImageTintList(ColorStateList.valueOf(ACCENT));
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
        stats = ui.column();
        stats.setBackground(ui.round(CARD, 16));
        stats.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12));
        head.addView(stats, Ui.matchWrap());

        chartCard = ui.column();
        chartCard.setBackground(ui.round(CARD, 16));
        chartCard.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8));
        chartTitle = ui.text(13, DIM, true);
        chartTitle.setPadding(ui.dp(4), 0, 0, ui.dp(6));
        chartCard.addView(chartTitle);
        chart = new ChartView(act);
        chartCard.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(170)));
        head.addView(chartCard, ui.spaced(12));

        listTitle = ui.label("Καταγραφές");
        head.addView(listTitle);

        list = new ListView(act);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setClipToPadding(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(28));
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
            TextView t = ui.text("Δεν υπάρχουν καταγραφές ακόμα.\nΠάτα + πάνω δεξιά για να γράψεις την πρώτη.",
                    15, SOFT, false);
            t.setPadding(0, ui.dp(6), 0, ui.dp(4));
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
        if (b.bestE1rm > 0) {
            statRow("Ρεκόρ ≈1RM", Fmt.num1(b.bestE1rm) + " kg", Fmt.kg(b.bestE1rmWeight) + " × " + b.bestE1rmReps
                    + "  ·  " + Fmt.day(bestDay, today));
            statRow("Βαρύτερο", Fmt.kg(heaviest) + " kg × " + heaviestReps, Fmt.day(heaviestDay, today));
        }
        if (mostReps > 0) statRow("Ρεκόρ με BW", mostReps + " reps", Fmt.day(repsDay, today));
        statRow("Καταγραφές", String.valueOf(h.size()), "από " + Fmt.day(h.get(0).day, today));
        statRow("Μέρες με PR", String.valueOf(prSessions), null);
    }

    private void statRow(String label, String value, String note) {
        LinearLayout r = ui.row();
        r.setPadding(0, ui.dp(5), 0, ui.dp(5));
        r.addView(ui.text(label, 14, DIM, false), Ui.fill());
        LinearLayout right = ui.column();
        right.setGravity(Gravity.END);
        TextView v = ui.text(value, 16, TEXT, true);
        v.setGravity(Gravity.END);
        right.addView(v);
        if (note != null) {
            TextView n = ui.text(note, 12, DIM, false);
            n.setGravity(Gravity.END);
            right.addView(n);
        }
        r.addView(right);
        stats.addView(r, Ui.matchWrap());
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
        chartTitle.setText(weighted ? "≈1RM ανά προπόνηση (kg)  ·  🏆 = PR" : "Max reps ανά προπόνηση  ·  🏆 = PR");
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
        ui.menu(anchor, new String[]{"Μετονομασία", "Διαγραφή άσκησης", "Πώς μετράνε τα PR"}, new Ui.OnPick() {
            @Override
            public void run(int which) {
                if (exercise == null) return;
                if (which == 0) {
                    renameExercise(exercise, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } else if (which == 1) {
                    deleteExercise(exercise, new Runnable() {
                        @Override
                        public void run() {
                            act.pop();
                        }
                    });
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
                LinearLayout wrap = ui.column();
                wrap.setPadding(0, ui.dp(4), 0, ui.dp(4));
                LinearLayout card = ui.column();
                card.setBackground(ui.ripple(CARD, 14));
                card.setDuplicateParentStateEnabled(true);
                card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
                LinearLayout top = ui.row();
                h.date = ui.text(15, TEXT, true);
                top.addView(h.date, Ui.fill());
                h.score = ui.text(13, DIM, false);
                top.addView(h.score);
                card.addView(top);
                h.sets = ui.text(15, SOFT, false);
                h.sets.setPadding(0, ui.dp(3), 0, 0);
                card.addView(h.sets);
                h.pr = ui.text(12, GOLD, true);
                h.pr.setPadding(0, ui.dp(4), 0, 0);
                card.addView(h.pr);
                wrap.addView(card, Ui.matchWrap());
                wrap.setTag(h);
                convertView = wrap;
            } else {
                h = (Holder) convertView.getTag();
            }
            Session s = sessions.get(position);
            h.date.setText(Fmt.day(s.day, Fmt.today()));
            double e = s.bestE1rm();
            h.score.setText(e > 0 ? "≈1RM " + Fmt.num1(e) + " kg" : "max " + s.maxReps() + " reps");
            h.sets.setText(Fmt.sets(s));
            h.pr.setText("🏆 " + Fmt.badges(s.flags));
            h.pr.setVisibility(s.flags != 0 ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    private static final class Holder {
        TextView date;
        TextView score;
        TextView sets;
        TextView pr;
    }
}
