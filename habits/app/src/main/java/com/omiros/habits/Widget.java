package com.omiros.habits;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** Home-screen widget: today's habits, the next deadline, a tap ticks or unticks. */
public class Widget extends AppWidgetProvider {
    private static final int[] ROWS = {R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5};

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        mgr.updateAppWidget(ids, views(c));
    }

    static void update(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, Widget.class));
        if (ids != null && ids.length > 0) mgr.updateAppWidget(ids, views(c));
    }

    static RemoteViews views(Context c) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int day = today.getDayOfMonth();
        MonthStore.Month m = MonthStore.get(c).load(YearMonth.from(today));
        Rules.Doc doc = Rules.get(c).on(today);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);

        int done = 0;
        MonthStore.Habit next = null;
        int nextMin = Integer.MAX_VALUE;
        for (MonthStore.Habit h : m.habits) {
            if (h.isDone(day)) {
                done++;
                continue;
            }
            Rules.Rule r = doc.rule(h.id);
            if (!r.avoid && r.minute >= 0 && r.minute < nextMin) {
                next = h;
                nextMin = r.minute;
            }
        }
        int n = m.habits.size();
        v.setTextViewText(R.id.widget_title, n == 0 ? "Habits" : done == n ? "Έγιναν όλες" : "Σήμερα " + done + " από " + n);
        String sub;
        if (n == 0) sub = "Πρόσθεσε τις συνήθειές σου στην εφαρμογή";
        else if (m.isRest(day)) sub = "Ρεπό σήμερα";
        else if (Engine.lockAt(c, now) != null) sub = "Κλειδωμένο: κάνε ό,τι έμεινε";
        else if (next != null) sub = "Επόμενο: " + next.name + " ως τις " + Ui.time(nextMin);
        else sub = Ui.weekday(today) + " " + day + " " + Ui.MONTHS_GEN[today.getMonthValue() - 1];
        v.setTextViewText(R.id.widget_next, sub);

        for (int i = 0; i < ROWS.length; i++) {
            if (i >= n) {
                v.setViewVisibility(ROWS[i], View.GONE);
                continue;
            }
            MonthStore.Habit h = m.habits.get(i);
            boolean d = h.isDone(day);
            Rules.Rule r = doc.rule(h.id);
            String right = !d && !r.avoid && r.minute >= 0 ? "   " + Ui.time(r.minute) : "";
            v.setViewVisibility(ROWS[i], View.VISIBLE);
            v.setTextViewText(ROWS[i], (d ? "✓  " : "○  ") + h.name + right);
            v.setTextColor(ROWS[i], d ? Theme.ACCENT : Theme.TEXT);
            v.setOnClickPendingIntent(ROWS[i], Alarms.broadcast(c, Alarms.ACTION_TOGGLE, h.id));
        }
        PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_title, open);
        v.setOnClickPendingIntent(R.id.widget_next, open);
        return v;
    }
}
