package com.omiros.gallery;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/** Swipe between items with three recycled pages (previous, current, next); the framework has no ViewPager. */
final class Pager extends ViewGroup {
    interface Host {
        int count();

        void bind(ZoomView page, int position);

        void onPageChanged(int position);
    }

    private final Host host;
    private final ZoomView[] pages = new ZoomView[3]; // [previous, current, next]
    private final int gap;
    private final int slop;
    private final int minFling;
    private int position;
    private float offset;
    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private VelocityTracker velocity;
    private ValueAnimator anim;

    Pager(Context c, Host host, ZoomView.Listener tap) {
        super(c);
        this.host = host;
        gap = Theme.dp(c, 16);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
        for (int i = 0; i < 3; i++) {
            pages[i] = new ZoomView(c, tap);
            addView(pages[i]);
        }
    }

    ZoomView current() {
        return pages[1];
    }

    int position() {
        return position;
    }

    /** Jump to a position and rebind every page (also after the list changed). */
    void show(int p) {
        if (anim != null) anim.cancel();
        position = p;
        offset = 0;
        bind(1);
        bind(0);
        bind(2);
        place();
    }

    private void bind(int slot) {
        int p = position + slot - 1;
        ZoomView v = pages[slot];
        if (p < 0 || p >= host.count()) {
            v.reset(Long.MIN_VALUE, false);
            v.setVisibility(INVISIBLE);
            return;
        }
        v.setVisibility(VISIBLE);
        host.bind(v, p);
    }

    private void place() {
        int w = getWidth() + gap;
        for (int i = 0; i < 3; i++) pages[i].setTranslationX((i - 1) * w + offset);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (ZoomView v : pages) v.layout(0, 0, r - l, b - t);
        place();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int ws = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        int hs = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        for (ZoomView v : pages) v.measure(ws, hs);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                dragging = anim != null && anim.isRunning();
                if (dragging) anim.cancel(); // catch a page that is still settling
                track(ev);
                return dragging;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() > 1) return false;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                track(ev);
                // take over a horizontal drag once the zoomed image cannot pan further that way
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) && !current().canScrollHorizontally(dx > 0 ? -1 : 1)) {
                    dragging = true;
                    lastX = ev.getX();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return dragging;
            default:
                return dragging;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - lastX;
                lastX = ev.getX();
                boolean atEdge = (offset + dx > 0 && position == 0) || (offset + dx < 0 && position == host.count() - 1);
                offset += atEdge ? dx * 0.3f : dx; // resist past the first and last item
                place();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                velocity.computeCurrentVelocity(1000);
                float vx = velocity.getXVelocity();
                velocity.recycle();
                velocity = null;
                dragging = false;
                int dir = 0;
                if (offset < -getWidth() / 3f || (vx < -minFling && offset < 0)) dir = 1;
                else if (offset > getWidth() / 3f || (vx > minFling && offset > 0)) dir = -1;
                int target = position + dir;
                if (target < 0 || target >= host.count()) dir = 0;
                settle(dir);
                return true;
            default:
                return true;
        }
    }

    private void track(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
    }

    private void settle(final int dir) {
        float end = -dir * (getWidth() + gap);
        anim = ValueAnimator.ofFloat(offset, end);
        anim.setDuration(Math.max(120, (long) (250 * Math.abs(end - offset) / (getWidth() + gap + 1f))));
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                offset = (Float) a.getAnimatedValue();
                place();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (dir != 0) turn(dir, cancelled);
            }
        });
        anim.start();
    }

    /** Shift the three pages after a swipe and rebind the one that came in. */
    private void turn(int dir, boolean cancelled) {
        if (cancelled) offset += dir * (getWidth() + gap); // keep the picture where the finger caught it
        else offset = 0;
        position += dir;
        if (dir > 0) {
            ZoomView old = pages[0];
            pages[0] = pages[1];
            pages[1] = pages[2];
            pages[2] = old;
            pages[0].resetZoom();
            bind(2);
        } else {
            ZoomView old = pages[2];
            pages[2] = pages[1];
            pages[1] = pages[0];
            pages[0] = old;
            pages[2].resetZoom();
            bind(0);
        }
        place();
        host.onPageChanged(position);
    }
}
