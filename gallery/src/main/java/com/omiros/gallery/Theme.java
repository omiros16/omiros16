package com.omiros.gallery;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.ImageView;

/**
 * Material 3 colour roles. On Android 14+ they come from the system (wallpaper colours, light or dark);
 * older versions get a fixed palette in the same roles.
 */
final class Theme {
    static boolean dark = true;
    static int BG;           // surface
    static int SURFACE;      // surface container: bars
    static int HIGH;         // surface container high: cards, placeholders
    static int TEXT;         // on surface
    static int DIM;          // on surface variant
    static int FAINT;        // outline variant
    static int ACCENT;       // primary
    static int ON_ACCENT;    // on primary
    static int ACCENT_BG;    // secondary container: selected tab, tonal buttons
    static int ON_ACCENT_BG; // on secondary container
    static int RED;          // error
    static int PLAYER;       // accent on the always-black viewer

    private Theme() {
    }

    static void init(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (Build.VERSION.SDK_INT >= 34) {
            BG = c.getColor(dark ? android.R.color.system_surface_dark : android.R.color.system_surface_light);
            SURFACE = c.getColor(dark ? android.R.color.system_surface_container_dark : android.R.color.system_surface_container_light);
            HIGH = c.getColor(dark ? android.R.color.system_surface_container_high_dark : android.R.color.system_surface_container_high_light);
            TEXT = c.getColor(dark ? android.R.color.system_on_surface_dark : android.R.color.system_on_surface_light);
            DIM = c.getColor(dark ? android.R.color.system_on_surface_variant_dark : android.R.color.system_on_surface_variant_light);
            FAINT = c.getColor(dark ? android.R.color.system_outline_variant_dark : android.R.color.system_outline_variant_light);
            ACCENT = c.getColor(dark ? android.R.color.system_primary_dark : android.R.color.system_primary_light);
            ON_ACCENT = c.getColor(dark ? android.R.color.system_on_primary_dark : android.R.color.system_on_primary_light);
            ACCENT_BG = c.getColor(dark ? android.R.color.system_secondary_container_dark : android.R.color.system_secondary_container_light);
            ON_ACCENT_BG = c.getColor(dark ? android.R.color.system_on_secondary_container_dark : android.R.color.system_on_secondary_container_light);
            RED = c.getColor(dark ? android.R.color.system_error_dark : android.R.color.system_error_light);
            PLAYER = c.getColor(android.R.color.system_primary_dark);
        } else if (dark) {
            PLAYER = 0xFFAAC7FF;
            BG = 0xFF111318;
            SURFACE = 0xFF1D2024;
            HIGH = 0xFF282A2F;
            TEXT = 0xFFE2E2E9;
            DIM = 0xFFC4C6D0;
            FAINT = 0xFF44474F;
            ACCENT = 0xFFAAC7FF;
            ON_ACCENT = 0xFF0A305F;
            ACCENT_BG = 0xFF3E4759;
            ON_ACCENT_BG = 0xFFDAE2F9;
            RED = 0xFFFFB4AB;
        } else {
            PLAYER = 0xFFAAC7FF;
            BG = 0xFFF9F9FF;
            SURFACE = 0xFFEDEDF4;
            HIGH = 0xFFE7E8EE;
            TEXT = 0xFF191C20;
            DIM = 0xFF44474E;
            FAINT = 0xFFC4C6D0;
            ACCENT = 0xFF415F91;
            ON_ACCENT = 0xFFFFFFFF;
            ACCENT_BG = 0xFFDAE2F9;
            ON_ACCENT_BG = 0xFF3E4759;
            RED = 0xFFBA1A1A;
        }
    }

    /** Draw behind the status and navigation bars, with icons readable on the background. */
    static void edgeToEdge(Activity a, boolean lightIcons) {
        Window w = a.getWindow();
        w.getDecorView(); // creates the decor; getInsetsController() is null-unsafe before it exists
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.setNavigationBarContrastEnforced(false);
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(lightIcons ? 0 : light, light);
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static Drawable ripple(int color, float radius) {
        return ripple(color, radius, TEXT);
    }

    static Drawable ripple(int color, float radius, int on) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(on, 0.12f)), color != 0 ? rounded(color, radius) : null,
                rounded(0xFFFFFFFF, radius));
    }

    /** 48dp round icon button with a description for TalkBack. */
    static ImageView iconButton(Context c, int icon, String description) {
        return iconButton(c, icon, description, TEXT);
    }

    static ImageView iconButton(Context c, int icon, String description, int tint) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setBackground(ripple(0, dp(c, 24), tint));
        b.setContentDescription(description);
        b.setTooltipText(description);
        return b;
    }

    /** The soft scalloped "cookie" shape of Material 3 Expressive, for empty-state icons. */
    static Drawable cookie(int color, float size) {
        Path p = new Path();
        int n = 180;
        for (int i = 0; i <= n; i++) {
            double t = 2 * Math.PI * i / n;
            double r = size / 2 * (0.92 + 0.08 * Math.cos(9 * t));
            float x = (float) (size / 2 + r * Math.cos(t));
            float y = (float) (size / 2 + r * Math.sin(t));
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        ShapeDrawable d = new ShapeDrawable(new PathShape(p, size, size));
        d.getPaint().setColor(color);
        d.getPaint().setAntiAlias(true);
        return d;
    }

    static void fadeIn(View v, boolean show) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1).setDuration(180).withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            final View view = v;
            v.animate().alpha(0).setDuration(180).withEndAction(new Runnable() {
                @Override
                public void run() {
                    view.setVisibility(View.INVISIBLE);
                }
            }).start();
        }
    }
}
