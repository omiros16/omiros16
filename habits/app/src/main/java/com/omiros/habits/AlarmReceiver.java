package com.omiros.habits;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.YearMonth;

/** The armed alarm, and the buttons on notifications and on the widget. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        int id = intent.getIntExtra(Alarms.EXTRA_ID, -1);
        if (Alarms.ACTION_DONE.equals(action)) {
            say(c, Engine.tick(c, id));
        } else if (Alarms.ACTION_TIMER.equals(action)) {
            Alarms.startTimer(c, id);
        } else if (Alarms.ACTION_STOP.equals(action)) {
            Alarms.stopTimer(c);
        } else if (Alarms.ACTION_TOGGLE.equals(action)) {
            LocalDate today = LocalDate.now();
            MonthStore.Habit h = MonthStore.get(c).load(YearMonth.from(today)).find(id);
            if (h == null) return;
            if (h.isDone(today.getDayOfMonth())) Engine.setDone(c, id, today, false);
            else say(c, Engine.tick(c, id));
        } else {
            Alarms.onAlarm(c);
        }
    }

    private static void say(Context c, String message) {
        if (message != null) Toast.makeText(c, message, Toast.LENGTH_LONG).show();
    }
}
