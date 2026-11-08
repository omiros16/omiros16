package com.omiros.gallery;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen photo / video viewer. Also opens files other apps send with "Open with". */
public class ViewerActivity extends Activity implements Pager.Host, ZoomView.Listener {
    static final String EXTRA_ID = "id";
    /** The exact list the grid was showing, so swiping follows it; null after a process restart. */
    static List<Media> handoff;

    private static final int REQ_FAV = 1;
    private static final int REQ_TRASH = 2;
    private static final int REQ_RESTORE = 3;
    private static final int REQ_DELETE = 4;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, HH:mm", Sections.GREEK);

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final HashMap<Long, Bitmap> full = new HashMap<>(); // sharp bitmaps for the current page and its neighbours
    private final HashSet<Long> decoding = new HashSet<>();
    private List<Media> items = new ArrayList<>();
    private int mode;
    private boolean external;
    private long pendingId;
    private int maxSide;
    private boolean chrome = true;

    private Pager pager;
    private View top;
    private View bottom;
    private TextView title;
    private TextView subtitle;
    private ImageView fav;
    private VideoPlayer videoBox;
    private View videoClose;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Theme.init(this);
        Theme.edgeToEdge(this, true); // always on black
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // shortcut: one decode at up to 1.5x the screen, no tiles; deep zoom on huge photos is soft. Upgrade with BitmapRegionDecoder.
        maxSide = Math.min(4096, Math.max(dm.widthPixels, dm.heightPixels) * 3 / 2);

        Intent in = getIntent();
        external = Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null;
        mode = in.getIntExtra(MainActivity.EXTRA_MODE, Media.ALL);
        setContentView(buildUi());

        if (external) {
            Uri uri = in.getData();
            String mime = in.getType() != null ? in.getType() : getContentResolver().getType(uri);
            Media m = Media.external(uri, mime);
            items.add(m);
            pager.show(0);
            updateChrome();
            if (m.video) play(m);
            return;
        }
        final long id = saved != null ? saved.getLong(EXTRA_ID) : in.getLongExtra(EXTRA_ID, -1);
        if (handoff != null) {
            items = handoff;
            handoff = null;
            start(id);
            return;
        }
        final long bucket = in.getLongExtra(MainActivity.EXTRA_BUCKET, 0);
        final ContentResolver cr = getContentResolver();
        MainActivity.BG.execute(new Runnable() {
            @Override
            public void run() {
                List<Media> list;
                try {
                    list = Media.filter(Media.load(cr, mode == Media.TRASH), mode, bucket);
                } catch (RuntimeException e) {
                    list = new ArrayList<>(); // e.g. the permission was revoked
                }
                final List<Media> result = list;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        items = new ArrayList<>(result);
                        start(id);
                    }
                });
            }
        });
    }

    private void start(long id) {
        if (items.isEmpty()) {
            finish();
            return;
        }
        int index = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id == id) index = i;
        pager.show(index);
        updateChrome();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Media m = current();
        if (m != null) out.putLong(EXTRA_ID, m.id);
    }

    @Override
    protected void onPause() {
        super.onPause();
        videoBox.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        decoder.shutdownNow();
        videoBox.stop();
    }

    @Override
    public void onBackPressed() { // Android 11-12; newer versions use interceptBack()
        if (videoBox.getVisibility() == View.VISIBLE && !external) closeVideo();
        else super.onBackPressed();
    }

    private Object backCallback;

    /** While a video is open, back closes it; otherwise the system's predictive back animation runs. */
    private void interceptBack(boolean on) {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (on && backCallback == null) {
            OnBackInvokedCallback cb = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    closeVideo();
                }
            };
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        } else if (!on && backCallback != null) {
            d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    private Media current() {
        int p = pager.position();
        return p >= 0 && p < items.size() ? items.get(p) : null;
    }

    // ---- pages ----

    @Override
    public int count() {
        return items.size();
    }

    @Override
    public void bind(ZoomView v, int position) {
        Media m = items.get(position);
        if (v.id != m.id) v.reset(m.id, m.video);
        v.setContentDescription(m.video ? "Βίντεο, πάτα για αναπαραγωγή" : "Φωτογραφία");
        Bitmap b = full.get(m.id);
        if (b != null) {
            v.setBitmap(b);
            return;
        }
        if (!v.hasBitmap()) {
            Bitmap t = Thumbs.get(this).cached(m.id);
            if (t != null) v.setBitmap(t);
        }
        decode(m);
    }

    @Override
    public void onPageChanged(int position) {
        HashSet<Long> keep = new HashSet<>();
        for (int p = position - 1; p <= position + 1; p++) if (p >= 0 && p < items.size()) keep.add(items.get(p).id);
        full.keySet().retainAll(keep);
        updateChrome();
    }

    private void decode(final Media m) {
        if (external && m.video) return; // nothing to show behind the player
        if (!decoding.add(m.id)) return;
        final ContentResolver cr = getContentResolver();
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    if (m.video) {
                        b = cr.loadThumbnail(m.uri, new Size(maxSide, maxSide), null);
                    } else {
                        b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, m.uri), new ImageDecoder.OnHeaderDecodedListener() {
                            @Override
                            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                                int w = info.getSize().getWidth();
                                int h = info.getSize().getHeight();
                                float k = maxSide / (float) Math.max(w, h);
                                if (k < 1) d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                                d.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE); // keeps big bitmaps off the Java heap
                            }
                        });
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                    // unreadable or unsupported: the page says so
                }
                final Bitmap result = b;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        decoding.remove(m.id);
                        if (isDestroyed()) return;
                        int p = pager.position();
                        for (int i = p - 1; i <= p + 1; i++) {
                            if (i >= 0 && i < items.size() && items.get(i).id == m.id && result != null) full.put(m.id, result);
                        }
                        for (int i = 0; i < pager.getChildCount(); i++) {
                            ZoomView v = (ZoomView) pager.getChildAt(i);
                            if (v.id != m.id) continue;
                            if (result != null) v.setBitmap(result);
                            else v.setBroken();
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onTap(ZoomView v) {
        Media m = current();
        if (m != null && m.video) play(m);
        else setChrome(!chrome);
    }

    // ---- top and bottom bars ----

    private void setChrome(boolean show) {
        chrome = show;
        Theme.fadeIn(top, show);
        Theme.fadeIn(bottom, show);
        systemBars(show);
    }

    private void systemBars(boolean show) {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (show) c.show(WindowInsets.Type.systemBars());
        else c.hide(WindowInsets.Type.systemBars());
    }

    private void updateChrome() {
        Media m = current();
        if (m == null) return;
        if (m.date > 0) {
            title.setText(Actions.date(m.date));
            subtitle.setText(m.expires > 0 ? "Σβήνεται οριστικά σε " + Actions.daysLeft(m.expires) + " ημέρες"
                    : WHEN.format(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault())));
        } else {
            title.setText(m.name);
            subtitle.setText(null);
        }
        if (fav != null) {
            fav.setImageResource(m.favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            fav.setImageTintList(ColorStateList.valueOf(m.favorite ? 0xFFFFB3C1 : 0xFFFFFFFF));
            String d = m.favorite ? "Αφαίρεση από τα αγαπημένα" : "Προσθήκη στα αγαπημένα";
            fav.setContentDescription(d);
            fav.setTooltipText(d);
        }
    }

    // ---- actions ----

    private List<Media> one() {
        Media m = current();
        pendingId = m.id;
        return Collections.singletonList(m);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_FAV) {
            for (Media m : items) if (m.id == pendingId) m.favorite = !m.favorite;
            updateChrome();
            return;
        }
        String done = requestCode == REQ_TRASH ? "Μεταφέρθηκε στον κάδο"
                : requestCode == REQ_RESTORE ? "Έγινε επαναφορά" : "Διαγράφηκε οριστικά";
        Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id != pendingId) continue;
            items.remove(i);
            full.remove(pendingId);
            if (items.isEmpty()) {
                finish();
                return;
            }
            pager.show(Math.min(pager.position(), items.size() - 1));
            onPageChanged(pager.position());
            return;
        }
    }

    // ---- video ----

    private void play(Media m) {
        videoBox.setVisibility(View.VISIBLE);
        systemBars(false);
        videoBox.play(m.uri);
        if (!external) interceptBack(true);
    }

    private void closeVideo() {
        videoBox.stop();
        videoBox.setVisibility(View.GONE);
        interceptBack(false);
        if (external) {
            finish();
            return;
        }
        systemBars(chrome);
    }

    // ---- layout ----

    private int dp(float v) {
        return Theme.dp(this, v);
    }

    private View buildUi() {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        pager = new Pager(this, this, this);
        root.addView(pager);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        ImageView back = Theme.iconButton(this, R.drawable.ic_back, "Πίσω", 0xFFFFFFFF);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(48), dp(48));
        blp.setMargins(dp(4), dp(8), dp(4), dp(24));
        topBar.addView(back, blp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        subtitle = new TextView(this);
        subtitle.setTextColor(0xCCEEF1F6);
        subtitle.setTextSize(13);
        titles.addView(title);
        titles.addView(subtitle);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.bottomMargin = dp(16);
        topBar.addView(titles, tlp);
        top = topBar;
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // floating toolbar (Material 3 Expressive)
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottomBar.setBackground(Theme.rounded(0xE6202226, dp(32)));
        bottomBar.setElevation(dp(6));
        addAction(bottomBar, R.drawable.ic_share, "Κοινοποίηση", new Runnable() {
            @Override
            public void run() {
                Actions.share(ViewerActivity.this, Collections.singletonList(current()));
            }
        });
        if (!external && mode == Media.TRASH) {
            addAction(bottomBar, R.drawable.ic_restore, "Επαναφορά", new Runnable() {
                @Override
                public void run() {
                    Actions.trash(ViewerActivity.this, REQ_RESTORE, one(), false);
                }
            });
            addAction(bottomBar, R.drawable.ic_info, "Πληροφορίες", new Runnable() {
                @Override
                public void run() {
                    Actions.info(ViewerActivity.this, current());
                }
            });
            addAction(bottomBar, R.drawable.ic_delete_forever, "Οριστική διαγραφή", new Runnable() {
                @Override
                public void run() {
                    Actions.deleteForever(ViewerActivity.this, REQ_DELETE, one());
                }
            });
        } else if (!external) {
            fav = addAction(bottomBar, R.drawable.ic_heart, "Προσθήκη στα αγαπημένα", new Runnable() {
                @Override
                public void run() {
                    Media m = current();
                    Actions.favorite(ViewerActivity.this, REQ_FAV, one(), !m.favorite);
                }
            });
            addAction(bottomBar, R.drawable.ic_info, "Πληροφορίες", new Runnable() {
                @Override
                public void run() {
                    Actions.info(ViewerActivity.this, current());
                }
            });
            addAction(bottomBar, R.drawable.ic_delete, "Μεταφορά στον κάδο", new Runnable() {
                @Override
                public void run() {
                    Actions.trash(ViewerActivity.this, REQ_TRASH, one(), true);
                }
            });
        }
        bottom = bottomBar;
        root.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        videoBox = new VideoPlayer(this, new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ViewerActivity.this, "Δεν παίζει αυτό το βίντεο", Toast.LENGTH_LONG).show();
                closeVideo();
            }
        });
        ImageView close = Theme.iconButton(this, R.drawable.ic_close, "Κλείσιμο βίντεο", 0xFFFFFFFF);
        close.setBackground(Theme.rounded(0x66000000, dp(24)));
        close.setForeground(Theme.ripple(0, dp(24), 0xFFFFFFFF));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeVideo();
            }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START);
        clp.setMargins(dp(8), dp(8), 0, 0);
        videoBox.controls().addView(close, clp);
        videoClose = close;
        videoBox.setVisibility(View.GONE);
        videoBox.setElevation(dp(16)); // above the floating toolbar, which has its own elevation
        root.addView(videoBox);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                // ignoring visibility keeps the bars still while the system bars hide and show
                Insets i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top.setPadding(i.left, i.top, i.right, 0);
                ((FrameLayout.LayoutParams) bottom.getLayoutParams()).bottomMargin = i.bottom + dp(20);
                bottom.requestLayout();
                videoBox.setInsets(i);
                return insets;
            }
        });
        return root;
    }

    private ImageView addAction(LinearLayout bar, int icon, String description, final Runnable run) {
        ImageView b = Theme.iconButton(this, icon, description, 0xFFFFFFFF);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current() != null) run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.leftMargin = lp.rightMargin = dp(6);
        bar.addView(b, lp);
        return b;
    }
}
