package com.omiros.habits;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * The lock. Android lets an app see which app comes to the front only through an accessibility
 * service, which the person turns on themselves. When the app in front is covered by a lock, the
 * lock screen goes over it.
 */
public class LockService extends AccessibilityService {
    private static LockService running;

    /** The app in front, from the last window change. */
    private String current;
    private Set<String> always;
    private long alwaysAt;

    /** Whether the person has turned the service on in the accessibility settings. */
    static boolean isOn(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(c, LockService.class);
        for (String s : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    /** A lock may have started, or the app in front may need checking again. */
    static void recheck() {
        if (running != null) running.check();
    }

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        current = e.getPackageName().toString();
        check();
    }

    private void check() {
        if (current == null || alwaysAllowed().contains(current)) return;
        Engine.Lock lock = Engine.lockAt(this, LocalDateTime.now());
        if (lock == null || !lock.blocks(Rules.get(this).on(LocalDate.now()), current)) return;
        startActivity(new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /**
     * Never locked, whatever the rules say: this app, calls and emergency, the home screen, the
     * keyboard, the system screens and Settings (so the lock can always be turned off there).
     */
    private Set<String> alwaysAllowed() {
        if (always != null && System.currentTimeMillis() - alwaysAt < 10 * 60_000) return always;
        Set<String> s = new HashSet<>();
        s.add(getPackageName());
        String[] fixed = {"android", "com.android.systemui", "com.android.settings", "com.android.phone",
                "com.android.server.telecom", "com.android.emergency", "com.google.android.dialer",
                "com.android.dialer", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                "com.android.packageinstaller", "com.google.android.packageinstaller"};
        for (String p : fixed) s.add(p);
        addHandlers(s, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        addHandlers(s, new Intent(Intent.ACTION_DIAL));
        addHandlers(s, new Intent(Settings.ACTION_SETTINGS));
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) s.add(i.getPackageName());
        }
        always = s;
        alwaysAt = System.currentTimeMillis();
        return s;
    }

    private void addHandlers(Set<String> s, Intent intent) {
        for (ResolveInfo r : getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            s.add(r.activityInfo.packageName);
        }
    }

    /** Apps worth keeping open during a full lock by default: the camera and maps. */
    static Set<String> defaultExceptions(Context c) {
        Set<String> s = new HashSet<>();
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)) {
            s.add(r.activityInfo.packageName);
        }
        try {
            c.getPackageManager().getPackageInfo("com.google.android.apps.maps", 0);
            s.add("com.google.android.apps.maps");
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return s;
    }
}
