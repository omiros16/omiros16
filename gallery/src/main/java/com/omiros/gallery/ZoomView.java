package com.omiros.gallery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** One page of the viewer: fit-to-screen image with pinch / double-tap zoom and panning. Videos get a play button. */
final class ZoomView extends View {
    interface Listener {
        void onTap(ZoomView v);
    }

    private static final float MAX_SCALE = 6;

    private final Listener listener;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable play;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private final RectF fit = new RectF();
    private final RectF dst = new RectF();
    private ValueAnimator anim;

    long id = Long.MIN_VALUE; // which item this page shows
    boolean video;
    private Bitmap bitmap;
    private boolean broken;
    private float scale = 1;
    private float tx;
    private float ty;

    ZoomView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        circle.setColor(0x99000000);
        text.setColor(Theme.DIM);
        text.setTextSize(Theme.dp(c, 15));
        text.setTextAlign(Paint.Align.CENTER);
        play = c.getDrawable(R.drawable.ic_play).mutate();
        play.setTint(0xFFFFFFFF);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (anim != null) anim.cancel();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                ZoomView.this.listener.onTap(ZoomView.this);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!video && bitmap != null) animateTo(scale > 1.05f ? 1 : 2.5f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale <= 1) return false;
                tx -= dx;
                ty -= dy;
                clamp();
                invalidate();
                return true;
            }
        });
    }

    /** Show another item: forget the zoom and the old image. */
    void reset(long id, boolean video) {
        if (anim != null) anim.cancel();
        this.id = id;
        this.video = video;
        bitmap = null;
        broken = false;
        scale = 1;
        tx = ty = 0;
        invalidate();
    }

    void resetZoom() {
        if (anim != null) anim.cancel();
        scale = 1;
        tx = ty = 0;
        invalidate();
    }

    /** Swap in a (sharper) bitmap of the same item; the zoom is kept because the aspect ratio is the same. */
    void setBitmap(Bitmap b) {
        bitmap = b;
        broken = false;
        computeFit();
        clamp();
        invalidate();
    }

    boolean hasBitmap() {
        return bitmap != null;
    }

    void setBroken() {
        if (bitmap == null) broken = true;
        invalidate();
    }

    boolean isZoomed() {
        return scale > 1.01f;
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        if (!isZoomed()) return false;
        current();
        return direction < 0 ? dst.left < -0.5f : dst.right > getWidth() + 0.5f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!video) {
            scaleDetector.onTouchEvent(ev);
            // keep the pager from stealing a pinch
            if (ev.getPointerCount() > 1) getParent().requestDisallowInterceptTouchEvent(true);
        }
        gestures.onTouchEvent(ev);
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = 1;
        tx = ty = 0;
        computeFit();
    }

    private void computeFit() {
        int w = getWidth();
        int h = getHeight();
        if (bitmap == null || w == 0 || h == 0) return;
        float s = Math.min(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        float bw = bitmap.getWidth() * s;
        float bh = bitmap.getHeight() * s;
        fit.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
    }

    private void current() {
        dst.set(fit.left * scale + tx, fit.top * scale + ty, fit.right * scale + tx, fit.bottom * scale + ty);
    }

    private void zoomBy(float factor, float fx, float fy) {
        float next = Math.max(1, Math.min(MAX_SCALE, scale * factor));
        float f = next / scale;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = next;
        clamp();
        invalidate();
    }

    private void animateTo(final float target, final float fx, final float fy) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(scale, target);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                zoomBy((Float) a.getAnimatedValue() / scale, fx, fy);
            }
        });
        anim.start();
    }

    /** Centre content smaller than the view; otherwise keep its edges outside the view. */
    private void clamp() {
        float w = getWidth();
        float h = getHeight();
        current();
        if (dst.width() <= w) tx += (w - dst.width()) / 2 - dst.left;
        else if (dst.left > 0) tx -= dst.left;
        else if (dst.right < w) tx += w - dst.right;
        if (dst.height() <= h) ty += (h - dst.height()) / 2 - dst.top;
        else if (dst.top > 0) ty -= dst.top;
        else if (dst.bottom < h) ty += h - dst.bottom;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (bitmap != null) {
            current();
            c.drawBitmap(bitmap, null, dst, paint);
        } else if (broken) {
            c.drawText("Δεν ανοίγει αυτό το αρχείο", w / 2f, h / 2f, text);
        }
        if (video && !broken) {
            float r = Theme.dp(getContext(), 34);
            c.drawCircle(w / 2f, h / 2f, r, circle);
            int s = (int) (r * 0.9f);
            int off = (int) (r * 0.08f); // the triangle looks centred when nudged right
            play.setBounds(w / 2 - s / 2 + off, h / 2 - s / 2, w / 2 + s / 2 + off, h / 2 + s / 2);
            play.draw(c);
        }
    }
}
