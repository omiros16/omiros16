package com.omiros.gallery;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.MediaController;

/**
 * Plays a video fitted to the screen, with pinch / double-tap zoom while it plays.
 * The picture is drawn by a TextureView; a transparent ZoomView on top handles the gestures and moves the picture.
 */
final class VideoPlayer extends FrameLayout implements MediaController.MediaPlayerControl,
        TextureView.SurfaceTextureListener, ZoomView.Listener, ZoomView.FrameListener {
    private final Runnable onError;
    private final TextureView texture;
    private final ZoomView zoom;
    private final MediaController controller;
    private final Matrix matrix = new Matrix();
    private final RectF full = new RectF();
    private MediaPlayer player;
    private Surface surface;
    private Uri uri;
    private boolean open;      // a video is loaded or waiting for the surface
    private boolean prepared;
    private int resumeAt = -1; // position to come back to after the app was in the background

    VideoPlayer(Context c, Runnable onError) {
        super(c);
        this.onError = onError;
        setBackgroundColor(0xFF000000);
        setClickable(true); // the pages behind must not react
        setKeepScreenOn(true);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        addView(texture);
        zoom = new ZoomView(c, this);
        zoom.setFrameListener(this);
        zoom.setContentDescription("Βίντεο. Πάτα για τα κουμπιά αναπαραγωγής");
        addView(zoom);
        controller = new MediaController(c);
        controller.setMediaPlayer(this);
        controller.setAnchorView(this);
    }

    void play(Uri u) {
        stop();
        uri = u;
        open = true;
        zoom.reset(0, false);
        texture.setAlpha(0); // hidden until the video size is known, so it never shows stretched
        if (surface != null) load();
    }

    void stop() {
        controller.hide();
        release();
        open = false;
        resumeAt = -1;
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
                if (resumeAt >= 0) {
                    mp.seekTo(resumeAt); // came back from the background: stay paused where it was
                    resumeAt = -1;
                    controller.show(0);
                } else {
                    mp.start();
                    controller.show();
                }
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                controller.show(0);
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
        controller.hide();
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
        if (controller.isShowing()) controller.hide();
        else controller.show();
    }

    // ---- controls ----

    @Override
    public void start() {
        if (player != null && prepared) player.start();
    }

    @Override
    public void pause() {
        if (isPlaying()) player.pause();
    }

    @Override
    public int getDuration() {
        return player != null && prepared ? player.getDuration() : 0;
    }

    @Override
    public int getCurrentPosition() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    @Override
    public void seekTo(int pos) {
        if (player != null && prepared) player.seekTo(pos);
    }

    @Override
    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        return 100; // local files
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getAudioSessionId() {
        return player != null ? player.getAudioSessionId() : 0;
    }
}
