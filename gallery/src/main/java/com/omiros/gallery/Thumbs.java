package com.omiros.gallery;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

/** Thumbnail cache shared by every screen. Newest requests load first so fast scrolling stays responsive. */
final class Thumbs {
    interface Callback {
        void onThumb(long id);
    }

    private static Thumbs instance;

    static synchronized Thumbs get(Context c) {
        if (instance == null) instance = new Thumbs(c.getApplicationContext().getContentResolver());
        return instance;
    }

    private static final int MAX_QUEUE = 120;

    private final ContentResolver cr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> cache;
    private final LinkedBlockingDeque<Object[]> queue = new LinkedBlockingDeque<>(); // {Media, Integer size}
    private final Map<Long, List<Callback>> waiting = new HashMap<>(); // main thread only
    private final HashSet<Long> failed = new HashSet<>();               // main thread only

    private Thumbs(ContentResolver cr) {
        this.cr = cr;
        cache = new LruCache<Long, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 4)) {
            @Override
            protected int sizeOf(Long key, Bitmap b) {
                return b.getAllocationByteCount();
            }
        };
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    work();
                }
            }, "thumbs-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    Bitmap cached(long id) {
        return cache.get(id);
    }

    boolean failed(long id) {
        return failed.contains(id);
    }

    /** The cached thumbnail (maybe smaller than asked); loads a sharp one in the background when needed. */
    Bitmap request(Media m, int size, Callback cb) {
        Bitmap b = cache.get(m.id);
        if (b != null && Math.min(b.getWidth(), b.getHeight()) * 10 >= size * 7) return b;
        if (failed.contains(m.id)) return b; // keep showing a smaller one rather than nothing
        List<Callback> list = waiting.get(m.id);
        if (list == null) {
            waiting.put(m.id, list = new ArrayList<>());
            queue.addLast(new Object[]{m, size});
            while (queue.size() > MAX_QUEUE) {
                Object[] dropped = queue.pollFirst(); // scrolled past long ago
                if (dropped != null) waiting.remove(((Media) dropped[0]).id);
            }
        }
        if (!list.contains(cb)) list.add(cb);
        return b;
    }

    private void work() {
        while (true) {
            Object[] job;
            try {
                job = queue.takeLast();
            } catch (InterruptedException e) {
                return;
            }
            final Media m = (Media) job[0];
            int size = (Integer) job[1];
            Bitmap b = null;
            try {
                // loadThumbnail fits inside the box; ask a bit bigger so the square crop stays sharp
                int s = size * 4 / 3;
                b = cr.loadThumbnail(m.uri, new Size(s, s), null);
            } catch (Exception ignored) {
                // missing or unreadable file: shown as a placeholder
            }
            final Bitmap result = b;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (result != null) cache.put(m.id, result);
                    else failed.add(m.id);
                    List<Callback> list = waiting.remove(m.id);
                    if (list != null) for (Callback cb : list) cb.onThumb(m.id);
                }
            });
        }
    }
}
