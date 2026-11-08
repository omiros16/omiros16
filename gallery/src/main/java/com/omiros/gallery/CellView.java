package com.omiros.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** A square thumbnail with its badges: video length, favourite, days left in the trash, selection. */
final class CellView extends View implements Thumbs.Callback {
    private final Thumbs thumbs;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Path clip = new Path();
    private final Path check = new Path();
    private final Drawable heart;
    private final Drawable play;
    private Drawable icon;

    private Media media;
    private boolean selecting;
    private boolean selected;
    private float radius;

    CellView(Context c) {
        super(c);
        thumbs = Thumbs.get(c);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(Theme.dp(c, 12));
        text.setFakeBoldText(true);
        text.setShadowLayer(Theme.dp(c, 2), 0, 0, 0x99000000);
        heart = c.getDrawable(R.drawable.ic_heart_filled).mutate();
        heart.setTint(0xFFFFFFFF);
        play = c.getDrawable(R.drawable.ic_play).mutate();
        play.setTint(0xFFFFFFFF);
    }

    void setRadius(float r) {
        radius = r;
    }

    /** Shown when there is no media to draw (empty favourites, the trash). */
    void setIcon(Drawable d) {
        icon = d;
        invalidate();
    }

    void bind(Media m, boolean selecting, boolean selected) {
        if (m != media || this.selecting != selecting || this.selected != selected) {
            media = m;
            this.selecting = selecting;
            this.selected = selected;
            invalidate();
        }
        if (m == null) return;
        String what = m.video ? "Βίντεο " + Actions.duration(m.duration) : "Φωτογραφία";
        String when = m.date > 0 ? ", " + Actions.date(m.date) : "";
        setContentDescription(what + when + (m.favorite ? ", αγαπημένο" : "") + (selected ? ", επιλεγμένο" : ""));
    }

    @Override
    public void onThumb(long id) {
        if (media != null && media.id == id) invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shade.setShader(new LinearGradient(0, h * 0.6f, 0, h, 0x00000000, 0x88000000, Shader.TileMode.CLAMP));
        clip.reset();
        if (radius > 0) clip.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0) return;
        if (radius > 0) c.clipPath(clip);
        float pad = selected ? w * 0.08f : 0;
        dst.set(pad, pad, w - pad, h - pad);

        Bitmap b = media == null ? null : thumbs.request(media, w, this);
        if (b != null) {
            // centre crop
            int bw = b.getWidth();
            int bh = b.getHeight();
            int side = Math.min(bw, bh);
            src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            c.drawBitmap(b, src, dst, bitmapPaint);
        } else {
            fill.setColor(Theme.SURFACE);
            c.drawRect(dst, fill);
            Drawable d = media == null ? icon : null;
            if (d != null) {
                int s = w / 3;
                d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                d.draw(c);
            }
        }
        if (media == null) return;

        int m = Theme.dp(getContext(), 6);
        int badge = Theme.dp(getContext(), 14);
        boolean badges = radius == 0 && (media.video || media.favorite || media.expires > 0); // album covers stay clean
        if (badges && w > Theme.dp(getContext(), 56)) {
            c.drawRect(dst.left, h * 0.6f, dst.right, dst.bottom, shade);
            float baseline = dst.bottom - m;
            if (media.video) {
                String t = Actions.duration(media.duration);
                float tw = text.measureText(t);
                c.drawText(t, dst.right - m - tw, baseline, text);
                int right = (int) (dst.right - m - tw - Theme.dp(getContext(), 2));
                play.setBounds(right - badge, (int) baseline - badge + Theme.dp(getContext(), 2), right, (int) baseline + Theme.dp(getContext(), 2));
                play.draw(c);
            }
            if (media.favorite) {
                heart.setBounds((int) dst.left + m, (int) baseline - badge + Theme.dp(getContext(), 2), (int) dst.left + m + badge, (int) baseline + Theme.dp(getContext(), 2));
                heart.draw(c);
            } else if (media.expires > 0) {
                c.drawText(Actions.daysLeft(media.expires) + " ημ.", dst.left + m, baseline, text);
            }
        }

        if (selecting) {
            float r = Theme.dp(getContext(), 11);
            float cx = m + r;
            float cy = m + r;
            if (selected) {
                fill.setColor(0x33000000 | (Theme.ACCENT & 0x00FFFFFF));
                c.drawRect(dst, fill);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Theme.ACCENT);
                c.drawCircle(cx, cy, r, fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(Theme.dp(getContext(), 2));
                fill.setColor(0xFFFFFFFF);
                check.reset();
                check.moveTo(cx - r * 0.45f, cy);
                check.lineTo(cx - r * 0.1f, cy + r * 0.35f);
                check.lineTo(cx + r * 0.45f, cy - r * 0.3f);
                c.drawPath(check, fill);
            } else {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(0x33000000);
                c.drawCircle(cx, cy, r, fill);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(Theme.dp(getContext(), 2));
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, r, fill);
            }
            fill.setStyle(Paint.Style.FILL);
        }
    }
}
