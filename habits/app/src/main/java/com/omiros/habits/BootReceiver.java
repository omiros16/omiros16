package com.omiros.habits;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms do not survive reboots, updates or clock changes: catch up and re-arm the next one. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Engine.sync(c);
        Engine.changed(c);
    }
}
