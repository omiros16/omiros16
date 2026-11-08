package com.omiros.gallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Plays a video fitted to the screen. Pinch / double-tap zooms while it plays; a horizontal swipe seeks
 * (only after a deliberate slide, so taps and small wobbles never move the video).
 * The picture is drawn by a TextureView; a transparent ZoomView on top handles the gestures and moves the picture.
 */
final class VideoPlayer extends FrameLayout implements TextureView.SurfaceTextureListener,
        ZoomView.Listener, ZoomView.FrameListener {
    private static final long HIDE_AFTER = 3000;
    private static final int MAX_SWIPE_SPAN = 90_000; // a full-width swipe moves at most 90 s

    private final Runnable onError;
    private final TextureView texture;
    private final ZoomView zoom;
    private final FrameLayout controls;
    private final ImageView playPause;
    private final SeekBar bar;
    private final TextView time;
    private final TextView total;
    private final TextView seekLabel;
    private final Matrix matrix = new Matrix();
    private final RectF full = new RectF();
    private final int seekSlop;

    private MediaPlayer player;
    private Surface surface;
    private Uri uri;
    private boolean open;      // a video is loaded or waiting for the surface
    private boolean prepared;
    private boolean ended;
    private int resumeAt = -1; // position to come back to after the app was in the background

    // swipe to seek
    private float downX;
    private float downY;
    private boolean swiping;
    private boolean wasPlaying;
    private int swipeFrom;
    private int swipeTo;
    private float swipeStartX;
    private long lastSeek;

    private final Runnable hide = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) Theme.fadeIn(controls, false);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (open) postDelayed(this, 250);
        }
    };

    VideoPlayer(Context c, Runnable onError) {
        super(c);
        this.onError = onError;
        seekSlop = Theme.dp(c, 28);
        setBackgroundColor(0xFF000000);
        setClickable(true); // the pages behind must not react
        setKeepScreenOn(true);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        addView(texture);
        zoom = new ZoomView(c, this);
        zoom.setFrameListener(this);
        zoom.setContentDescription("Βίντεο. Πάτα για τα κουμπιά, σύρε δεξιά ή αριστερά για να προχωρήσεις ή να γυρίσεις πίσω");
        addView(zoom);

        controls = new FrameLayout(c);
        addView(controls);

        playPause = new ImageView(c);
        playPause.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        int p = Theme.dp(c, 20);
        playPause.setPadding(p, p, p, p);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x66000000);
        playPause.setBackground(circle);
        playPause.setForeground(Theme.ripple(0, Theme.dp(c, 40), 0xFFFFFFFF));
        playPause.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        controls.addView(playPause, new LayoutParams(Theme.dp(c, 80), Theme.dp(c, 80), Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(c);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(Theme.dp(c, 20), Theme.dp(c, 8), Theme.dp(c, 20), Theme.dp(c, 16));
        time = label(c);
        total = label(c);
        bar = new SeekBar(c);
        bar.setProgressTintList(ColorStateList.valueOf(Theme.PLAYER));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(Theme.PLAYER);
        thumb.setCornerRadius(Theme.dp(c, 2));
        thumb.setSize(Theme.dp(c, 4), Theme.dp(c, 28)); // Material 3 Expressive slider handle
        bar.setThumb(thumb);
        bar.setSplitTrack(true);
        bar.setContentDescription("Θέση βίντεο");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser || !prepared) return;
                player.seekTo(progress, MediaPlayer.SEEK_CLOSEST);
                time.setText(Actions.duration(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                removeCallbacks(hide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                scheduleHide();
            }
        });
        bottom.addView(time);
        bottom.addView(bar, new LinearLayout.LayoutParams(0, Theme.dp(c, 48), 1));
        bottom.addView(total);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
        controls.addView(bottom, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        seekLabel = new TextView(c);
        seekLabel.setTextColor(0xFFFFFFFF);
        seekLabel.setTextSize(18);
        seekLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        seekLabel.setGravity(Gravity.CENTER);
        seekLabel.setLineSpacing(0, 1.1f);
        seekLabel.setPadding(Theme.dp(c, 24), Theme.dp(c, 12), Theme.dp(c, 24), Theme.dp(c, 12));
        seekLabel.setBackground(Theme.rounded(0x99000000, Theme.dp(c, 28)));
        seekLabel.setVisibility(INVISIBLE);
        addView(seekLabel, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private static TextView label(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setFontFeatureSettings("tnum"); // digits keep their width while the time counts
        t.setText("0:00");
        return t;
    }

    /** The layer that fades with the buttons; the viewer puts its close button here. */
    FrameLayout controls() {
        return controls;
    }

    void setInsets(Insets i) {
        controls.setPadding(i.left, i.top, i.right, i.bottom);
    }

    void play(Uri u) {
        stop();
        uri = u;
        open = true;
        ended = false;
        zoom.reset(0, false);
        texture.setAlpha(0); // hidden until the video size is known, so it never shows stretched
        bar.setProgress(0);
        time.setText("0:00");
        total.setText("0:00");
        showControls();
        removeCallbacks(tick);
        post(tick);
        if (surface != null) load();
    }

    void stop() {
        removeCallbacks(hide);
        removeCallbacks(tick);
        release();
        open = false;
        resumeAt = -1;
    }

    void pause() {
        if (isPlaying()) player.pause();
        updateProgress();
        showControls();
    }

    private void toggle() {
        if (!prepared) return;
        if (isPlaying()) {
            player.pause();
        } else {
            if (ended) player.seekTo(0);
            ended = false;
            player.start();
        }
        updateProgress();
        showControls();
    }

    private boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    private void release() {
        if (player != null) player.release();
        player = null;
        prepared = false;
    }

    private void load() {
        player = new MediaPlayer();
        try {
            player.setDataSource(getContext(), uri);
        } catch (Exception e) {
            fail();
            return;
        }
        player.setSurface(surface);
        player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, int w, int h) {
                if (w <= 0 || h <= 0) return;
                zoom.setContentSize(w, h);
                texture.setAlpha(1);
            }
        });
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                bar.setMax(mp.getDuration());
                total.setText(Actions.duration(mp.getDuration()));
                if (resumeAt >= 0) {
                    mp.seekTo(resumeAt, MediaPlayer.SEEK_CLOSEST); // back from the background: stay paused there
                    resumeAt = -1;
                } else {
                    mp.start();
                }
                updateProgress();
                showControls();
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                ended = true;
                updateProgress();
                showControls();
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                fail();
                return true;
            }
        });
        player.prepareAsync();
    }

    private void fail() {
        stop();
        onError.run();
    }

    private void updateProgress() {
        boolean playing = isPlaying();
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPause.setContentDescription(playing ? "Παύση" : "Αναπαραγωγή");
        if (!prepared || swiping) return;
        int pos = player.getCurrentPosition();
        bar.setProgress(pos);
        time.setText(Actions.duration(pos));
    }

    private void showControls() {
        Theme.fadeIn(controls, true);
        scheduleHide();
    }

    private void scheduleHide() {
        removeCallbacks(hide);
        postDelayed(hide, HIDE_AFTER);
    }

    // ---- swipe to seek ----

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (swiping) return true;
                if (!prepared || zoom.isZoomed() || ev.getPointerCount() > 1) return false; // zoomed: the slide pans
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                // only a clear sideways slide seeks: well past a tap's wobble and mostly horizontal
                if (Math.abs(dx) > seekSlop && Math.abs(dx) > 2 * Math.abs(dy)) {
                    startSwipe(ev.getX());
                    return true;
                }
                return false;
            default:
                return swiping;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!swiping) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                moveSwipe(ev.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endSwipe();
                return true;
            default:
                return true;
        }
    }

    private void startSwipe(float x) {
        swiping = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        wasPlaying = isPlaying();
        if (wasPlaying) player.pause();
        swipeFrom = swipeTo = player.getCurrentPosition();
        swipeStartX = x; // count from here, so the video does not jump by the slop distance
        lastSeek = 0;
        removeCallbacks(hide);
        Theme.fadeIn(controls, true);
        playPause.setVisibility(INVISIBLE);
        seekLabel.setVisibility(VISIBLE);
        moveSwipe(x);
    }

    private void moveSwipe(float x) {
        int duration = player.getDuration();
        float msPerPx = Math.min(duration, MAX_SWIPE_SPAN) / (float) Math.max(1, getWidth());
        swipeTo = Math.max(0, Math.min(duration, swipeFrom + Math.round((x - swipeStartX) * msPerPx)));
        int delta = swipeTo - swipeFrom;
        seekLabel.setText((delta < 0 ? "−" : "+") + Actions.duration(Math.abs(delta)) + "\n"
                + Actions.duration(swipeTo) + " / " + Actions.duration(duration));
        bar.setProgress(swipeTo);
        time.setText(Actions.duration(swipeTo));
        long now = SystemClock.uptimeMillis();
        if (now - lastSeek > 120) { // a quick preview frame while sliding; the exact one on release
            lastSeek = now;
            player.seekTo(swipeTo, MediaPlayer.SEEK_CLOSEST_SYNC);
        }
    }

    private void endSwipe() {
        swiping = false;
        seekLabel.setVisibility(INVISIBLE);
        playPause.setVisibility(VISIBLE);
        player.seekTo(swipeTo, MediaPlayer.SEEK_CLOSEST);
        ended = false;
        if (wasPlaying) player.start();
        updateProgress();
        showControls();
    }

    // ---- surface ----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (open) load();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (player != null && prepared) resumeAt = player.getCurrentPosition();
        release();
        surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    // ---- zoom ----

    @Override
    public void onFrame(RectF frame) {
        if (texture.getWidth() == 0) return;
        full.set(0, 0, texture.getWidth(), texture.getHeight());
        matrix.setRectToRect(full, frame, Matrix.ScaleToFit.FILL);
        texture.setTransform(matrix);
    }

    @Override
    public void onTap(ZoomView v) {
        if (controls.getVisibility() == VISIBLE && controls.getAlpha() > 0.5f) Theme.fadeIn(controls, false);
        else showControls();
    }
}
