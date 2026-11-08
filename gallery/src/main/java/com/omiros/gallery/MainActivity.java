package com.omiros.gallery;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Timeline and albums at the root; started again with extras it shows one album, the favourites or the trash.
 */
public class MainActivity extends Activity implements MediaGrid.Listener {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_BUCKET = "bucket";
    static final String EXTRA_TITLE = "title";
    static final ExecutorService BG = Executors.newSingleThreadExecutor();

    private static final int REQ_PERMS = 1;
    private static final int REQ_CHANGE = 2;
    private static final int NONE = 0;
    private static final int PARTIAL = 1;
    private static final int FULL = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reload = new Runnable() {
        @Override
        public void run() {
            if (access != NONE) load();
        }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 400); // a burst of changes (e.g. a delete of many) reloads once
        }
    };

    private SharedPreferences prefs;
    private int mode;
    private long bucket;
    private String title;
    private boolean root;
    private int tab;
    private int access = FULL;
    private boolean loaded;
    private int loadGen;
    private Insets insets = Insets.NONE;
    private Object backCallback; // OnBackInvokedCallback while something can be closed (Android 13+)

    private View topBar;
    private TextView titleView;
    private TextView emptyTrash;
    private View normalBar;
    private View selectBar;
    private TextView selectCount;
    private LinearLayout toolbar;
    private ImageView selectFav;
    private TextView banner;
    private TextView trashNote;
    private MediaGrid grid;
    private GridView albumGrid;
    private final AlbumAdapter albums = new AlbumAdapter();
    private View empty;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyText;
    private TextView emptyButton;
    private ProgressBar progress;
    private LinearLayout nav;
    private final View[] tabIndicators = new View[2];
    private final ImageView[] tabIcons = new ImageView[2];
    private final TextView[] tabLabels = new TextView[2];

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Theme.init(this);
        Theme.edgeToEdge(this, Theme.dark);
        getWindow().setBackgroundDrawable(new ColorDrawable(Theme.BG));
        Intent in = getIntent();
        mode = in.getIntExtra(EXTRA_MODE, Media.ALL);
        bucket = in.getLongExtra(EXTRA_BUCKET, 0);
        title = in.getStringExtra(EXTRA_TITLE);
        root = mode == Media.ALL;
        prefs = getSharedPreferences("gallery", MODE_PRIVATE);
        if (saved != null) tab = saved.getInt("tab");
        setContentView(buildUi());
        if (root && saved == null && access() == NONE && !prefs.getBoolean("asked", false)) askPermission();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContentResolver cr = getContentResolver();
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
        refresh(); // also picks up permission changes made in Settings
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacks(reload);
    }

    @Override
    public void onBackPressed() { // Android 11-12; newer versions use the callback below
        if (grid.isSelecting()) grid.clearSelection();
        else super.onBackPressed();
    }

    /** Back closes the selection first; otherwise the system plays its predictive back animation. */
    private void interceptBack(boolean on) {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (on && backCallback == null) {
            OnBackInvokedCallback cb = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    grid.clearSelection();
                }
            };
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        } else if (!on && backCallback != null) {
            d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    // ---- permission ----

    private static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private int access() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) return FULL;
            // Android 14 "select photos": only the chosen items are visible
            if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return PARTIAL;
            return NONE;
        }
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE) ? FULL : NONE;
    }

    private void askPermission() {
        boolean rationale = false;
        for (String p : permissions()) rationale |= shouldShowRequestPermissionRationale(p);
        if (prefs.getBoolean("asked", false) && !rationale && access() == NONE) {
            // denied twice: Android no longer shows the prompt, only Settings can grant it
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        prefs.edit().putBoolean("asked", true).apply();
        requestPermissions(permissions(), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        refresh();
    }

    // ---- data ----

    private void refresh() {
        access = access();
        banner.setVisibility(access == PARTIAL ? View.VISIBLE : View.GONE);
        if (access == NONE) {
            grid.setItems(Collections.<Media>emptyList(), true);
            albums.set(Collections.<Media>emptyList());
            loaded = true;
            progress.setVisibility(View.GONE);
            updateViews();
            return;
        }
        load();
    }

    private void load() {
        final int gen = ++loadGen;
        final boolean trash = mode == Media.TRASH;
        final ContentResolver cr = getContentResolver();
        if (!loaded) progress.setVisibility(View.VISIBLE);
        BG.execute(new Runnable() {
            @Override
            public void run() {
                List<Media> list = null;
                String error = null;
                try {
                    list = Media.load(cr, trash);
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
                final List<Media> result = list;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen == loadGen && !isDestroyed()) show(result, err);
                    }
                });
            }
        });
    }

    private void show(List<Media> all, String error) {
        loaded = true;
        progress.setVisibility(View.GONE);
        if (all == null) {
            grid.setItems(Collections.<Media>emptyList(), true);
            setEmpty(R.drawable.ic_photos, "Δεν φορτώθηκαν οι φωτογραφίες", error, "Δοκίμασε ξανά");
            grid.setVisibility(View.GONE);
            albumGrid.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        grid.setItems(Media.filter(all, mode, bucket), mode != Media.TRASH);
        albums.set(all);
        updateViews();
    }

    // ---- what is on screen ----

    private void updateViews() {
        boolean albumTab = root && tab == 1;
        boolean noItems = grid.items().isEmpty();
        boolean showEmpty = loaded && (access == NONE || (!albumTab && noItems));
        grid.setVisibility(!albumTab && !showEmpty ? View.VISIBLE : View.GONE);
        albumGrid.setVisibility(albumTab && access != NONE ? View.VISIBLE : View.GONE);
        empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (access == NONE) {
            setEmpty(R.drawable.ic_photos, "Δώσε πρόσβαση στις φωτογραφίες σου",
                    "Το Gallery δείχνει τις φωτογραφίες και τα βίντεο του κινητού σου. Δεν στέλνει τίποτα πουθενά.",
                    "Επίτρεψε πρόσβαση");
        } else if (mode == Media.TRASH) {
            setEmpty(R.drawable.ic_delete, "Ο κάδος είναι άδειος",
                    "Ό,τι διαγράφεις μένει εδώ 30 ημέρες και μετά σβήνεται οριστικά.", null);
        } else if (mode == Media.FAVORITES) {
            setEmpty(R.drawable.ic_heart, "Δεν έχεις αγαπημένα ακόμα",
                    "Πάτα την καρδιά σε μια φωτογραφία για να μπει εδώ.", null);
        } else if (mode == Media.ALBUM) {
            setEmpty(R.drawable.ic_albums, "Το άλμπουμ είναι άδειο", null, null);
        } else {
            setEmpty(R.drawable.ic_photos, "Δεν υπάρχουν φωτογραφίες ακόμα",
                    "Ό,τι τραβάς με την κάμερα ή κατεβάζεις θα εμφανίζεται εδώ.", null);
        }
        titleView.setText(root ? (tab == 0 ? "Φωτογραφίες" : "Άλμπουμ") : title);
        boolean trashItems = mode == Media.TRASH && !noItems && access != NONE;
        emptyTrash.setVisibility(trashItems ? View.VISIBLE : View.GONE);
        trashNote.setVisibility(trashItems ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2 && root; i++) {
            boolean on = i == tab;
            tabIndicators[i].setBackground(on ? Theme.rounded(Theme.ACCENT_BG, dp(16)) : null);
            tabIcons[i].setImageTintList(ColorStateList.valueOf(on ? Theme.ON_ACCENT_BG : Theme.DIM));
            tabLabels[i].setTextColor(on ? Theme.TEXT : Theme.DIM);
            tabLabels[i].setTypeface(Typeface.create("sans-serif-medium", on ? Typeface.BOLD : Typeface.NORMAL));
            tabIndicators[i].setSelected(on);
        }
        onSelectionChanged();
        updateBarColor();
    }

    private void setEmpty(int icon, String head, String text, String button) {
        emptyIcon.setImageResource(icon);
        emptyTitle.setText(head);
        emptyText.setText(text);
        emptyText.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        emptyButton.setText(button);
        emptyButton.setVisibility(button == null ? View.GONE : View.VISIBLE);
    }

    private void selectTab(int t) {
        if (t == tab) {
            if (t == 0) grid.smoothScrollToPosition(0);
            return;
        }
        grid.clearSelection();
        tab = t;
        updateViews();
    }

    /** The top bar takes the container colour once content scrolls under it (Material 3). */
    private void updateBarColor() {
        AbsListView list = root && tab == 1 ? albumGrid : grid;
        boolean scrolled = list.getVisibility() == View.VISIBLE && list.getChildCount() > 0
                && (list.getFirstVisiblePosition() > 0 || list.getChildAt(0).getTop() < list.getPaddingTop());
        topBar.setBackgroundColor(scrolled || grid.isSelecting() ? Theme.SURFACE : Theme.BG);
    }

    private void applyInsets() {
        boolean selecting = grid.isSelecting();
        topBar.setPadding(insets.left, insets.top, insets.right, 0);
        if (nav != null) nav.setPadding(insets.left, 0, insets.right, insets.bottom);
        // without a navigation bar the lists run behind the system bar; leave room for the floating toolbar
        int bottom = (nav == null || selecting ? insets.bottom : 0) + (selecting ? dp(96) : dp(16));
        grid.setPadding(insets.left, 0, insets.right, bottom);
        albumGrid.setPadding(insets.left + dp(16), dp(4), insets.right + dp(16), bottom);
        ((FrameLayout.LayoutParams) toolbar.getLayoutParams()).bottomMargin = insets.bottom + dp(16);
        toolbar.requestLayout();
    }

    // ---- grid callbacks ----

    @Override
    public void onOpen(Media m) {
        ViewerActivity.handoff = new ArrayList<>(grid.items());
        startActivity(new Intent(this, ViewerActivity.class)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_BUCKET, bucket)
                .putExtra(ViewerActivity.EXTRA_ID, m.id));
    }

    @Override
    public void onSelectionChanged() {
        boolean on = grid.isSelecting();
        normalBar.setVisibility(on ? View.GONE : View.VISIBLE);
        selectBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (nav != null) nav.setVisibility(on ? View.GONE : View.VISIBLE);
        if ((toolbar.getVisibility() == View.VISIBLE) != on) {
            toolbar.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                toolbar.setTranslationY(dp(24));
                toolbar.setAlpha(0);
                toolbar.animate().translationY(0).alpha(1).setDuration(220).start();
            }
            applyInsets();
        }
        interceptBack(on);
        updateBarColor();
        if (!on) return;
        List<Media> sel = grid.getSelected();
        selectCount.setText(sel.size() == 1 ? "1 επιλεγμένο" : sel.size() + " επιλεγμένα");
        if (selectFav != null) {
            boolean allFav = allFavorite(sel);
            selectFav.setImageResource(allFav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            String d = allFav ? "Αφαίρεση από τα αγαπημένα" : "Προσθήκη στα αγαπημένα";
            selectFav.setContentDescription(d);
            selectFav.setTooltipText(d);
        }
    }

    @Override
    public void onColumnsChanged(int cols) {
        prefs.edit().putInt("cols", cols).apply();
    }

    private static boolean allFavorite(List<Media> list) {
        for (Media m : list) if (!m.favorite) return false;
        return !list.isEmpty();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CHANGE && resultCode == RESULT_OK) {
            grid.clearSelection();
            load();
        }
    }

    private void openAlbum(int mode, long bucket, String title) {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_BUCKET, bucket)
                .putExtra(EXTRA_TITLE, title));
    }

    // ---- layout ----

    private int dp(float v) {
        return Theme.dp(this, v);
    }

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Theme.BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column);

        FrameLayout bars = new FrameLayout(this);
        bars.addView(buildNormalBar(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, root ? dp(88) : dp(64)));
        bars.addView(selectBar = buildSelectBar(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        selectBar.setVisibility(View.GONE);
        topBar = bars;
        column.addView(bars);

        banner = new TextView(this);
        banner.setText("Βλέπεις μόνο όσες φωτογραφίες επέλεξες. Πάτα για να δώσεις πρόσβαση σε όλες.");
        banner.setTextColor(Theme.ON_ACCENT_BG);
        banner.setTextSize(14);
        banner.setPadding(dp(20), dp(16), dp(20), dp(16));
        banner.setBackground(Theme.ripple(Theme.ACCENT_BG, dp(20), Theme.ON_ACCENT_BG));
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(permissions(), REQ_PERMS);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(16), 0, dp(16), dp(12));
        column.addView(banner, blp);
        banner.setVisibility(View.GONE);

        trashNote = new TextView(this);
        trashNote.setText("Τα στοιχεία σβήνονται οριστικά 30 ημέρες μετά τη διαγραφή. Κράτα πατημένο ένα στοιχείο για επαναφορά.");
        trashNote.setTextColor(Theme.DIM);
        trashNote.setTextSize(14);
        trashNote.setPadding(dp(20), 0, dp(20), dp(12));
        column.addView(trashNote);
        trashNote.setVisibility(View.GONE);

        FrameLayout content = new FrameLayout(this);
        grid = new MediaGrid(this, prefs.getInt("cols", 4), this);
        content.addView(grid);
        albumGrid = buildAlbumGrid();
        content.addView(albumGrid);
        content.addView(empty = buildEmpty());
        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Theme.ACCENT));
        content.addView(progress, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        progress.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        albumGrid.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        column.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        AbsListView.OnScrollListener scroll = new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                updateBarColor();
            }
        };
        grid.setOnScrollListener(scroll);
        albumGrid.setOnScrollListener(scroll);

        if (root) column.addView(nav = buildNav());

        toolbar = buildToolbar();
        frame.addView(toolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        toolbar.setVisibility(View.GONE);

        frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insets = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                applyInsets();
                return WindowInsets.CONSUMED;
            }
        });
        return frame;
    }

    private View buildNormalBar() {
        LinearLayout bar = new LinearLayout(this);
        normalBar = bar;
        bar.setGravity(root ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(root ? 20 : 4), 0, dp(12), root ? dp(12) : 0);
        if (!root) {
            ImageView back = Theme.iconButton(this, R.drawable.ic_back, "Πίσω");
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        titleView = new TextView(this);
        titleView.setTextColor(Theme.TEXT);
        titleView.setTextSize(root ? 32 : 22);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        titleView.setLetterSpacing(root ? -0.01f : 0);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(root ? 0 : dp(8), 0, 0, 0);
        titleView.setAccessibilityHeading(true);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        emptyTrash = new TextView(this);
        emptyTrash.setText("Άδειασμα");
        emptyTrash.setTextColor(Theme.RED);
        emptyTrash.setTextSize(14);
        emptyTrash.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        emptyTrash.setGravity(Gravity.CENTER);
        emptyTrash.setPadding(dp(16), 0, dp(16), 0);
        emptyTrash.setBackground(Theme.ripple(0, dp(20), Theme.RED));
        emptyTrash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Actions.deleteForever(MainActivity.this, REQ_CHANGE, grid.items());
            }
        });
        bar.addView(emptyTrash, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        emptyTrash.setVisibility(View.GONE);
        return bar;
    }

    private View buildSelectBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(4), 0);
        bar.addView(action(R.drawable.ic_close, "Ακύρωση επιλογής", new Runnable() {
            @Override
            public void run() {
                grid.clearSelection();
            }
        }));
        selectCount = new TextView(this);
        selectCount.setTextColor(Theme.TEXT);
        selectCount.setTextSize(22);
        selectCount.setSingleLine(true);
        selectCount.setEllipsize(TextUtils.TruncateAt.END);
        selectCount.setPadding(dp(8), 0, 0, 0);
        selectCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(selectCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(action(R.drawable.ic_select_all, "Επιλογή όλων", new Runnable() {
            @Override
            public void run() {
                grid.selectAll();
            }
        }));
        return bar;
    }

    /** Floating toolbar with the actions for the selection (Material 3 Expressive). */
    private LinearLayout buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackground(Theme.rounded(Theme.HIGH, dp(32)));
        bar.setElevation(dp(6));
        if (mode == Media.TRASH) {
            bar.addView(action(R.drawable.ic_restore, "Επαναφορά", new Runnable() {
                @Override
                public void run() {
                    Actions.trash(MainActivity.this, REQ_CHANGE, grid.getSelected(), false);
                }
            }));
            bar.addView(action(R.drawable.ic_delete_forever, "Οριστική διαγραφή", new Runnable() {
                @Override
                public void run() {
                    Actions.deleteForever(MainActivity.this, REQ_CHANGE, grid.getSelected());
                }
            }));
            return bar;
        }
        bar.addView(action(R.drawable.ic_share, "Κοινοποίηση", new Runnable() {
            @Override
            public void run() {
                Actions.share(MainActivity.this, grid.getSelected());
            }
        }));
        selectFav = action(R.drawable.ic_heart, "Προσθήκη στα αγαπημένα", new Runnable() {
            @Override
            public void run() {
                List<Media> sel = grid.getSelected();
                Actions.favorite(MainActivity.this, REQ_CHANGE, sel, !allFavorite(sel));
            }
        });
        bar.addView(selectFav);
        bar.addView(action(R.drawable.ic_delete, "Μεταφορά στον κάδο", new Runnable() {
            @Override
            public void run() {
                Actions.trash(MainActivity.this, REQ_CHANGE, grid.getSelected(), true);
            }
        }));
        return bar;
    }

    private ImageView action(int icon, String description, final Runnable run) {
        ImageView b = Theme.iconButton(this, icon, description);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    private View buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), 0, dp(32), dp(48));
        emptyIcon = new ImageView(this);
        emptyIcon.setImageTintList(ColorStateList.valueOf(Theme.ON_ACCENT_BG));
        emptyIcon.setBackground(Theme.cookie(Theme.ACCENT_BG, dp(112)));
        int p = dp(34);
        emptyIcon.setPadding(p, p, p, p);
        box.addView(emptyIcon, new LinearLayout.LayoutParams(dp(112), dp(112)));
        emptyTitle = new TextView(this);
        emptyTitle.setTextColor(Theme.TEXT);
        emptyTitle.setTextSize(22);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(24), 0, 0);
        box.addView(emptyTitle);
        emptyText = new TextView(this);
        emptyText.setTextColor(Theme.DIM);
        emptyText.setTextSize(15);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(0, 1.15f);
        emptyText.setPadding(0, dp(8), 0, 0);
        box.addView(emptyText);
        emptyButton = new TextView(this);
        emptyButton.setTextColor(Theme.ON_ACCENT);
        emptyButton.setTextSize(16);
        emptyButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        emptyButton.setGravity(Gravity.CENTER);
        emptyButton.setPadding(dp(28), 0, dp(28), 0);
        emptyButton.setBackground(Theme.ripple(Theme.ACCENT, dp(28), Theme.ON_ACCENT));
        emptyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (access == NONE) askPermission();
                else load();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        lp.topMargin = dp(28);
        box.addView(emptyButton, lp);
        return box;
    }

    /** Material 3 navigation bar: a pill behind the selected icon, label underneath. */
    private LinearLayout buildNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Theme.SURFACE);
        String[] names = {"Φωτογραφίες", "Άλμπουμ"};
        int[] icons = {R.drawable.ic_photos, R.drawable.ic_albums};
        for (int i = 0; i < 2; i++) {
            final int t = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, dp(12), 0, dp(16));
            item.setContentDescription(names[i]);
            FrameLayout pill = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            pill.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            pill.setBackground(null);
            pill.setDuplicateParentStateEnabled(true);
            item.addView(pill, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView label = new TextView(this);
            label.setText(names[i]);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(label);
            // the ripple hugs the pill, as in Material 3
            pill.setForeground(Theme.ripple(0, dp(16)));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(t);
                }
            });
            tabIndicators[i] = pill;
            tabIcons[i] = icon;
            tabLabels[i] = label;
            bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return bar;
    }

    private GridView buildAlbumGrid() {
        GridView g = new GridView(this);
        g.setNumColumns(GridView.AUTO_FIT);
        g.setColumnWidth(dp(150));
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setHorizontalSpacing(dp(12));
        g.setVerticalSpacing(dp(20));
        g.setClipToPadding(false);
        g.setSelector(android.R.color.transparent);
        g.setAdapter(albums);
        g.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AlbumAdapter.Entry e = albums.entries.get(position);
                openAlbum(e.mode, e.bucket, e.name);
            }
        });
        return g;
    }

    /** Favourites and trash first, then one card per folder. */
    private final class AlbumAdapter extends BaseAdapter {
        final class Entry {
            final int mode;
            final long bucket;
            final String name;
            final String count;
            final Media cover;
            final int icon;

            Entry(int mode, long bucket, String name, String count, Media cover, int icon) {
                this.mode = mode;
                this.bucket = bucket;
                this.name = name;
                this.count = count;
                this.cover = cover;
                this.icon = icon;
            }
        }

        final List<Entry> entries = new ArrayList<>();

        void set(List<Media> all) {
            entries.clear();
            Media favCover = null;
            int favs = 0;
            for (Media m : all) {
                if (!m.favorite) continue;
                if (favCover == null) favCover = m;
                favs++;
            }
            entries.add(new Entry(Media.FAVORITES, 0, "Αγαπημένα", count(favs), favCover, R.drawable.ic_heart));
            entries.add(new Entry(Media.TRASH, 0, "Κάδος", "Διαγραμμένα", null, R.drawable.ic_delete));
            for (Media.Album a : Media.albums(all)) {
                entries.add(new Entry(Media.ALBUM, a.id, a.name, count(a.count), a.cover, 0));
            }
            notifyDataSetChanged();
        }

        private String count(int n) {
            return n == 1 ? "1 στοιχείο" : n + " στοιχεία";
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Context c = parent.getContext();
            LinearLayout card = (LinearLayout) convert;
            if (card == null) {
                card = new LinearLayout(c);
                card.setOrientation(LinearLayout.VERTICAL);
                CellView cover = new CellView(c);
                cover.setRadius(dp(24));
                cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                card.addView(cover);
                TextView name = new TextView(c);
                name.setTextColor(Theme.TEXT);
                name.setTextSize(16);
                name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(4), dp(10), dp(4), 0);
                card.addView(name);
                TextView count = new TextView(c);
                count.setTextColor(Theme.DIM);
                count.setTextSize(14);
                count.setPadding(dp(4), dp(2), dp(4), 0);
                card.addView(count);
            }
            Entry e = entries.get(position);
            CellView cover = (CellView) card.getChildAt(0);
            cover.bind(e.cover, false, false);
            if (e.icon != 0) {
                Drawable d = c.getDrawable(e.icon).mutate();
                d.setTint(Theme.ON_ACCENT_BG);
                cover.setIcon(d, Theme.ACCENT_BG);
            } else {
                cover.setIcon(null, Theme.HIGH);
            }
            ((TextView) card.getChildAt(1)).setText(e.name);
            ((TextView) card.getChildAt(2)).setText(e.count);
            return card;
        }
    }
}
