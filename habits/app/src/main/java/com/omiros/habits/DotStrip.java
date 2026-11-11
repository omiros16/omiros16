package com.omiros.habits;

import static com.omiros.habits.Theme.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * One dot per day of the month: filled when done, a ring for today, a dash for a day off, small
 * for days still ahead and for days before the habit was added.
 */
final class DotStrip extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int days = 30;
    private int doneBits;
    private int today;
    private int since = 1;
    private int rest;

    DotStrip(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(ACCENT);
        ring.setStrokeWidth(1.5f * density());
    }

    /**
     * {@code today} is the 1-based day of the month; days after it are drawn as still ahead.
     * {@code since} is the day the habit was added; {@code rest} has a bit set for each day off.
     */
    void set(int days, int doneBits, int today, int since, int rest) {
        if (days == this.days && doneBits == this.doneBits && today == this.today && since == this.since
                && rest == this.rest) return;
        this.days = days;
        this.doneBits = doneBits;
        this.today = today;
        this.since = since;
        this.rest = rest;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float width = getWidth() - left - getPaddingRight();
        float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float step = width / days;
        float r = Math.min(step * 0.34f, 4.5f * density());
        for (int d = 1; d <= days; d++) {
            float cx = left + step * (d - 0.5f);
            if ((doneBits & (1 << (d - 1))) != 0) {
                fill.setColor(ACCENT);
                canvas.drawCircle(cx, cy, r, fill);
            } else if ((rest & (1 << (d - 1))) != 0) {
                fill.setColor(FAINT);
                canvas.drawRoundRect(cx - r, cy - r * 0.3f, cx + r, cy + r * 0.3f, r * 0.3f, r * 0.3f, fill);
            } else if (d == today) {
                canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2, ring);
            } else {
                fill.setColor(FAINT);
                canvas.drawCircle(cx, cy, d < today && d >= since ? r : r * 0.45f, fill);
            }
        }
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }
}
