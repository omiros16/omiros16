package com.omiros.gallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageView;

/** Colours and small view helpers shared by the screens (same palette as Timetable). */
final class Theme {
    static final int BG = 0xFF0F1115;
    static final int SURFACE = 0xFF161920;
    static final int DIALOG = 0xFF1A1D24;
    static final int TEXT = 0xFFEEF1F6;
    static final int DIM = 0xFF7D8594;
    static final int FAINT = 0xFF2A2F3A;
    static final int ACCENT = 0xFF5B9DFF;
    static final int ACCENT_BG = 0xFF1C2E4A;
    static final int RED = 0xFFF06A6A;

    private Theme() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Drawable ripple(int color, float radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        GradientDrawable content = null;
        if (color != 0) {
            content = new GradientDrawable();
            content.setColor(color);
            content.setCornerRadius(radius);
        }
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    /** 48dp round icon button with a description for TalkBack. */
    static ImageView iconButton(Context c, int icon, String description) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(TEXT));
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setBackground(ripple(0, dp(c, 24)));
        b.setContentDescription(description);
        b.setTooltipText(description);
        return b;
    }
}
