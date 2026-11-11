package com.omiros.habits;

import static com.omiros.habits.Theme.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String[] WEEKDAY_INITIALS = {"Δ", "Τ", "Τ", "Π", "Π", "Σ", "Κ"};
    private static final String[] SUGGESTIONS = {"Γυμναστική", "Διάβασμα", "Διαλογισμός", "Περπάτημα",
            "2 λίτρα νερό", "Ύπνος πριν τις 12", "Χωρίς social media", "Ημερολόγιο", "Διατάσεις"};
    private static final String[] LEVELS = {"Τίποτα", "Υπενθύμιση", "Πρήξιμο", "Κλείδωμα"};
    private static final String[] LEVEL_HELP = {
            "Μόνο το τικ, χωρίς ειδοποιήσεις.",
            "Μία ειδοποίηση μία ώρα πριν από την ώρα.",
            "Ειδοποιήσεις 2 ώρες πριν, 1 ώρα πριν και μετά κάθε 15 λεπτά ως την ώρα.",
            "Όπως το πρήξιμο, και στην ώρα το κινητό κλειδώνει μέχρι να το κάνεις."};
    private static final int[] TIMERS = {0, 10, 15, 20, 30, 45, 60};
    private static final int REQ_NOTIF = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!checkNewDay()) refresh();
            handler.postDelayed(this, 30_000);
        }
    };

    private MonthStore store;
    private Rules rules;
    private LocalDate today;
    private YearMonth shown;
    private MonthStore.Month month;

    private TextView subtitleView;
    private TextView titleView;
    private ImageView historyButton;
    private ImageView settingsButton;
    private ImageView prevButton;
    private ImageView nextButton;
    private TextView statusView;
    private TextView noticeView;
    private LinearLayout hero;
    private LinearLayout list;
    private LinearLayout footer;
    /** The rows on screen, by habit id, so a tick updates one row in place instead of rebuilding. */
    private final SparseArray<RowViews> rows = new SparseArray<>();

    /** The parts of a habit row that change when it is ticked. */
    private static final class RowViews {
        View row;
        ImageView check;
        GradientDrawable checkCircle;
        TextView name;
        TextView meta;
        TextView right;
        DotStrip strip;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = MonthStore.get(this);
        rules = Rules.get(this);
        Engine.sync(this);
        today = LocalDate.now();
        String saved = savedInstanceState == null ? null : savedInstanceState.getString("month");
        shown = saved != null ? YearMonth.parse(saved) : YearMonth.from(today);
        if (shown.isAfter(YearMonth.from(today))) shown = YearMonth.from(today);
        setContentView(buildUi());
        showMonth(shown);
        Engine.changed(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!checkNewDay()) refresh();
        handler.postDelayed(ticker, 30_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // From an earlier month, Back returns to today before leaving the app.
        if (!isCurrent()) showMonth(YearMonth.from(today));
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("month", shown.toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        refresh();
    }

    /**
     * Follows the clock past midnight: a new month starts from zero, and if the shown month was
     * the current one the screen moves on to the new current month. Returns true if the day changed.
     */
    private boolean checkNewDay() {
        LocalDate now = LocalDate.now();
        if (now.equals(today)) return false;
        Engine.sync(this);
        YearMonth was = YearMonth.from(today);
        YearMonth current = YearMonth.from(now);
        today = now;
        if ((shown.equals(was) && !was.equals(current)) || shown.isAfter(current)) showMonth(current);
        else showMonth(shown);
        return true;
    }

    private boolean isCurrent() {
        return shown.equals(YearMonth.from(today));
    }

    private Rules.Doc doc() {
        return rules.on(today);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private TextView text(float sp, int color, boolean medium) {
        return Ui.text(this, sp, color, medium);
    }

    // ---------------------------------------------------------------- UI construction

    private View buildUi() {
        // No background of its own: the window's is the same colour, and drawing it twice costs every frame.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(18), dp(10), dp(6));
        root.addView(header);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(top);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        subtitleView = text(13, ACCENT, true);
        titleView = text(28, TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(subtitleView);
        titleCol.addView(titleView);
        titleCol.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isCurrent()) showMonth(YearMonth.from(today));
            }
        });
        top.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        historyButton = iconButton(R.drawable.ic_history, "Προηγούμενοι μήνες", DIM);
        historyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(shown.minusMonths(1));
            }
        });
        top.addView(historyButton);
        settingsButton = iconButton(R.drawable.ic_settings, "Ρυθμίσεις", DIM);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        top.addView(settingsButton);
        prevButton = iconButton(R.drawable.ic_chevron_left, "Προηγούμενος μήνας", TEXT);
        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(shown.minusMonths(1));
            }
        });
        top.addView(prevButton);
        nextButton = iconButton(R.drawable.ic_chevron_right, "Επόμενος μήνας", TEXT);
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(shown.plusMonths(1));
            }
        });
        top.addView(nextButton);

        statusView = text(13, DIM, false);
        statusView.setPadding(0, dp(6), dp(12), 0);
        header.addView(statusView);
        noticeView = text(13, WARN, true);
        noticeView.setPadding(0, dp(8), dp(12), dp(4));
        noticeView.setBackground(Ui.withRipple(this, null, dp(8)));
        header.addView(noticeView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(10), dp(16), dp(32));
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        content.addView(hero);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        content.addView(footer);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private ImageView iconButton(int res, String description, int tint) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setContentDescription(description);
        iv.setBackground(Ui.withRipple(this, null, dp(22)));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return iv;
    }

    // ---------------------------------------------------------------- rendering

    private void showMonth(YearMonth ym) {
        shown = ym;
        month = store.load(ym);
        render();
    }

    /** Rebuilds the whole screen: after a change of month, day or habit list. */
    private void render() {
        boolean current = isCurrent();
        list.removeAllViews();
        rows.clear();
        if (current) addLastMonthBanner();
        if (month.habits.isEmpty() && current) addEmptyState();
        for (MonthStore.Habit h : month.habits) list.addView(buildRow(h, current));
        refresh();
    }

    /** Updates header, hero, rows and footer in place: after ticks, which change no layout. */
    private void refresh() {
        for (MonthStore.Habit h : month.habits) {
            if (rows.get(h.id) == null) {
                render();
                return;
            }
        }
        renderHeader();
        renderHero();
        for (MonthStore.Habit h : month.habits) bindRow(h, rows.get(h.id));
        renderFooter();
    }

    private void renderHeader() {
        boolean current = isCurrent();
        int n = month.habits.size();
        YearMonth earliest = store.earliest();
        if (current) {
            subtitleView.setText(Ui.MONTHS[today.getMonthValue() - 1]);
            titleView.setText(Ui.weekday(today) + " " + today.getDayOfMonth());
        } else {
            subtitleView.setText("Ιστορικό " + shown.getYear());
            titleView.setText(Ui.MONTHS[shown.getMonthValue() - 1]);
        }
        historyButton.setVisibility(current && earliest != null && earliest.isBefore(shown) ? View.VISIBLE : View.GONE);
        settingsButton.setVisibility(current ? View.VISIBLE : View.GONE);
        prevButton.setVisibility(current ? View.GONE : View.VISIBLE);
        nextButton.setVisibility(current ? View.GONE : View.VISIBLE);
        prevButton.setEnabled(earliest != null && shown.isAfter(earliest));
        prevButton.setAlpha(prevButton.isEnabled() ? 1f : 0.25f);

        int elapsed = current ? today.getDayOfMonth() : shown.lengthOfMonth();
        int pct = month.percent(elapsed);
        if (n == 0) {
            statusView.setText(current ? "" : "Δεν υπάρχουν καταγραφές γι' αυτόν τον μήνα");
        } else if (current) {
            int done = 0;
            for (MonthStore.Habit h : month.habits) if (h.isDone(today.getDayOfMonth())) done++;
            String s = "Έγιναν " + done + " από " + n + ", " + pct + "% τον " + Ui.monthAcc(shown.getMonthValue());
            if (!doc().on) s += "\nΣκέτο tracker: χωρίς ειδοποιήσεις και κλειδώματα.";
            statusView.setText(s);
        } else {
            int total = month.total();
            statusView.setText("Ολοκλήρωση " + pct + "%, " + total + (total == 1 ? " φορά" : " φορές") + " συνολικά");
        }
        statusView.setVisibility(statusView.getText().length() == 0 ? View.GONE : View.VISIBLE);
        renderNotice(current);
    }

    /** What stops the rules from working: notifications or the lock turned off on the phone. */
    private void renderNotice(boolean current) {
        String notice = null;
        View.OnClickListener action = null;
        Rules.Doc doc = doc();
        if (current && doc.on) {
            boolean anyRule = false;
            boolean anyLock = false;
            for (MonthStore.Habit h : month.habits) {
                Rules.Rule r = doc.rule(h.id);
                anyRule |= r.active();
                anyLock |= !r.avoid && r.level == Rules.LOCK || r.avoid && r.block;
            }
            if (anyRule && !hasNotificationPermission()) {
                notice = "Οι ειδοποιήσεις είναι κλειστές. Πάτα για να τις ανοίξεις.";
                action = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestNotificationPermission();
                    }
                };
            } else if (anyLock && !LockService.isOn(this)) {
                notice = "Το κλείδωμα δεν έχει ενεργοποιηθεί στο κινητό. Πάτα για να το ρυθμίσεις.";
                action = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                };
            }
        }
        noticeView.setText(notice);
        noticeView.setOnClickListener(action);
        noticeView.setVisibility(notice == null ? View.GONE : View.VISIBLE);
    }

    /** The top of today's screen: whatever is most urgent right now, in big type. */
    private void renderHero() {
        hero.removeAllViews();
        if (!isCurrent() || month.habits.isEmpty()) {
            hero.setVisibility(View.GONE);
            return;
        }
        hero.setVisibility(View.VISIBLE);
        LocalDateTime now = LocalDateTime.now();
        int day = today.getDayOfMonth();
        int nowMin = Engine.minuteOf(now);
        Rules.Doc doc = doc();

        long timerEnd = Alarms.timerEnd(this);
        final MonthStore.Habit timerHabit = month.find(Alarms.timerHabit(this));
        Engine.Lock lock = Engine.lockAt(this, now);

        if (month.isRest(day)) {
            heroBig("Ρεπό", ACCENT);
            heroCaption("Χωρίς ειδοποιήσεις και κλειδώματα σήμερα. Το σερί σου δεν σπάει.");
            TextView undo = Ui.button(this, "Ακύρωση ρεπό", false, DIM);
            undo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Engine.setRest(MainActivity.this, today, false);
                    refresh();
                }
            });
            heroButtons(undo);
        } else if (timerEnd > System.currentTimeMillis() && timerHabit != null) {
            heroLabel("Χρονόμετρο", ACCENT);
            heroName(timerHabit.name);
            Chronometer ch = new Chronometer(this);
            ch.setCountDown(true);
            ch.setBase(SystemClock.elapsedRealtime() + (timerEnd - System.currentTimeMillis()));
            ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
            ch.setTypeface(Ui.CONDENSED);
            ch.setTextColor(TEXT);
            ch.start();
            hero.addView(ch);
            heroCaption("Τικάρεται μόλις τελειώσει.");
            TextView stop = Ui.button(this, "Σταμάτα", false, DIM);
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Alarms.stopTimer(MainActivity.this);
                    refresh();
                }
            });
            heroButtons(doneButton(timerHabit, "Έγινε τώρα"), stop);
        } else if (lock != null) {
            heroLabel("Κλειδωμένο", RED);
            if (!lock.habits.isEmpty()) {
                MonthStore.Habit h = lock.habits.get(0);
                heroName(h.name);
                heroBig(Ui.time(doc.rule(h.id).minute), RED);
                heroCaption("έληξε και δεν έχει γίνει");
                heroButtons(doneButton(h, "Έγινε"), timerButton(h), passButton());
            } else {
                heroName(lock.avoids.get(0).name);
                heroBig(Ui.time(Math.min(lock.until, 24 * 60 - 1)), RED);
                heroCaption("η λίστα σου μένει μπλοκαρισμένη ως τότε");
                heroButtons(passButton());
            }
        } else {
            MonthStore.Habit next = null;
            int nextMin = Integer.MAX_VALUE;
            int done = 0;
            for (MonthStore.Habit h : month.habits) {
                if (h.isDone(day)) {
                    done++;
                    continue;
                }
                Rules.Rule r = doc.rule(h.id);
                if (!r.avoid && r.minute >= 0 && r.minute < nextMin) {
                    next = h;
                    nextMin = r.minute;
                }
            }
            int n = month.habits.size();
            if (next != null) {
                heroName(next.name);
                if (nextMin > nowMin) {
                    int left = nextMin - nowMin;
                    heroBig(left / 60 + ":" + String.format(Locale.ROOT, "%02d", left % 60), left < 60 ? WARN : TEXT);
                    heroCaption("μένουν, ως τις " + Ui.time(nextMin));
                } else {
                    heroBig(Ui.time(nextMin), RED);
                    heroCaption("έληξε και δεν έχει γίνει");
                }
                heroButtons(doneButton(next, "Έγινε"), timerButton(next));
            } else if (done == n) {
                heroBig("Έγιναν όλες", ACCENT);
                heroCaption("Τίποτα δεν εκκρεμεί σήμερα.");
            } else {
                heroBig(done + " από " + n, TEXT);
                heroCaption("έγιναν σήμερα");
            }
        }
        View gap = new View(this);
        hero.addView(gap, new LinearLayout.LayoutParams(1, dp(22)));
    }

    private void heroLabel(String s, int color) {
        TextView t = text(13, color, true);
        t.setText(s);
        hero.addView(t);
    }

    private void heroName(String s) {
        TextView t = text(20, TEXT, true);
        t.setText(s);
        t.setPadding(0, dp(2), 0, 0);
        hero.addView(t);
    }

    private void heroBig(String s, int color) {
        TextView t = text(60, color, false);
        t.setTypeface(Ui.CONDENSED);
        t.setIncludeFontPadding(false);
        t.setText(s);
        t.setPadding(0, dp(6), 0, 0);
        t.setFontFeatureSettings("tnum");
        hero.addView(t);
    }

    private void heroCaption(String s) {
        TextView t = text(14, DIM, false);
        t.setText(s);
        hero.addView(t);
    }

    private void heroButtons(View... buttons) {
        LinearLayout row = new LinearLayout(this);
        boolean first = true;
        for (View b : buttons) {
            if (b == null) continue;
            LinearLayout.LayoutParams lp = Ui.wrap();
            if (!first) lp.leftMargin = dp(8);
            row.addView(b, lp);
            first = false;
        }
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = dp(16);
        hero.addView(h, lp);
    }

    private TextView doneButton(final MonthStore.Habit h, String label) {
        TextView b = Ui.button(this, label, true, ACCENT);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Alarms.timerHabit(MainActivity.this) == h.id) Alarms.stopTimer(MainActivity.this);
                toggleToday(h);
            }
        });
        return b;
    }

    private TextView timerButton(final MonthStore.Habit h) {
        Rules.Rule r = doc().rule(h.id);
        if (r.timer <= 0) return null;
        TextView b = Ui.button(this, "Χρονόμετρο " + r.timer + "'", false, TEXT);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Alarms.startTimer(MainActivity.this, h.id);
                refresh();
            }
        });
        return b;
    }

    private TextView passButton() {
        int left = Engine.passesLeft(month);
        if (left == 0) return null;
        TextView b = Ui.button(this, "Πάσο (" + left + ")", false, RED);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = "Όλα ξεκλειδώνουν μέχρι τα μεσάνυχτα και η μέρα μετράει ως «δεν έγινε»."
                        + (doc().hasFriends() ? " Οι φίλοι σου θα ενημερωθούν." : "");
                AlertDialog d = Ui.dialog(MainActivity.this)
                        .setTitle("Να χρησιμοποιηθεί πάσο;")
                        .setMessage(msg)
                        .setNegativeButton("Άκυρο", null)
                        .setNeutralButton("Πάσο", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Engine.usePass(MainActivity.this);
                                refresh();
                            }
                        })
                        .show();
                Ui.styleDialog(MainActivity.this, d);
            }
        });
        return b;
    }

    private View buildRow(final MonthStore.Habit h, final boolean current) {
        final RowViews rv = new RowViews();
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(10), 0, dp(6));
        row.setBackground(Ui.withRipple(this, null, dp(14)));
        rv.row = row;

        if (current) {
            rv.check = new ImageView(this);
            rv.check.setScaleType(ImageView.ScaleType.CENTER);
            rv.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            rv.check.setImageTintList(ColorStateList.valueOf(ON_ACCENT));
            rv.checkCircle = new GradientDrawable();
            rv.checkCircle.setShape(GradientDrawable.OVAL);
            rv.check.setBackground(rv.checkCircle);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(34), dp(34));
            clp.rightMargin = dp(14);
            clp.topMargin = dp(2);
            row.addView(rv.check, clp);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        rv.name = text(17, TEXT, true);
        rv.name.setText(h.name);
        rv.name.setSingleLine(true);
        rv.name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(rv.name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rv.right = text(14, DIM, true);
        rv.right.setCompoundDrawablePadding(dp(4));
        rv.right.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams rlp = Ui.wrap();
        rlp.leftMargin = dp(10);
        line.addView(rv.right, rlp);
        col.addView(line);
        rv.meta = text(13, DIM, false);
        col.addView(rv.meta);

        rv.strip = new DotStrip(this);
        rv.strip.setPadding(0, dp(8), dp(2), dp(8));
        rv.strip.setBackground(Ui.withRipple(this, null, dp(8)));
        rv.strip.setContentDescription("Μέρες του μήνα για " + h.name);
        rv.strip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCalendar(h);
            }
        });
        col.addView(rv.strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current) toggleToday(h);
                else openCalendar(h);
            }
        });
        if (current) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showOptions(row, h);
                    return true;
                }
            });
        }
        rows.put(h.id, rv);
        return row;
    }

    /** Shows the habit's current state on its row; only changes colours and text, never layout. */
    private void bindRow(MonthStore.Habit h, RowViews rv) {
        boolean current = isCurrent();
        int days = shown.lengthOfMonth();
        int day = today.getDayOfMonth();
        Rules.Doc doc = doc();
        Rules.Rule r = doc.rule(h.id);
        boolean doneToday = current && h.isDone(day);
        rv.name.setTextColor(doneToday ? DIM : TEXT);
        if (current) {
            rv.checkCircle.setColor(doneToday ? ACCENT : 0);
            rv.checkCircle.setStroke(dp(2), doneToday ? ACCENT : RING);
            if ((rv.check.getDrawable() != null) != doneToday) {
                rv.check.setImageDrawable(doneToday ? getDrawable(R.drawable.ic_check) : null);
            }
            String meta = currentMeta(h.count(), store.streak(h.id, today, month));
            if (rules.wanted().drop.contains(h.id)) meta = "Διαγράφεται αύριο";
            rv.meta.setText(meta);
            rv.row.setContentDescription(h.name + (doneToday ? ", έγινε σήμερα" : ", δεν έγινε ακόμα σήμερα"));

            // Right side: the time, coloured by how close it is; a lock where one applies.
            String right = "";
            int color = DIM;
            boolean lockIcon = false;
            if (r.avoid && r.block) {
                right = Ui.time(r.from) + "–" + Ui.time(Math.min(r.until, 24 * 60 - 1));
                lockIcon = true;
            } else if (!r.avoid && r.minute >= 0) {
                right = Ui.time(r.minute);
                lockIcon = r.level == Rules.LOCK;
                int left = r.minute - Engine.minuteOf(LocalDateTime.now());
                if (!doneToday && !month.isRest(day) && doc.on) color = left <= 0 ? RED : left < 60 ? WARN : TEXT;
            }
            if (!doc.on) lockIcon = false;
            rv.right.setText(right);
            rv.right.setTextColor(color);
            Drawable icon = null;
            if (lockIcon) {
                icon = getDrawable(R.drawable.ic_lock).mutate();
                icon.setTint(color);
                icon.setBounds(0, 0, dp(14), dp(14));
            }
            rv.right.setCompoundDrawablesRelative(icon, null, null, null);
        } else {
            rv.meta.setText(h.count() + " από " + (days - Math.min(h.since, days) + 1) + " μέρες");
            rv.right.setText("");
        }
        rv.strip.set(days, h.days, current ? day : Integer.MAX_VALUE, h.since, month.rest);
    }

    private static String currentMeta(int count, int streak) {
        if (count == 0) return streak >= 2 ? streak + " μέρες στη σειρά, συνέχισε σήμερα" : "Καμία φορά ακόμα";
        String s = count + (count == 1 ? " φορά" : " φορές");
        if (streak >= 2) s += ", σερί " + streak;
        return s;
    }

    private void renderFooter() {
        footer.removeAllViews();
        if (!isCurrent()) {
            if (!month.habits.isEmpty()) footer.addView(hint("Πάτα μια συνήθεια για να δεις ή να διορθώσεις τις μέρες της."));
            return;
        }
        if (month.habits.size() < MonthStore.MAX_HABITS) {
            footer.addView(textButton("+ Νέα συνήθεια", ACCENT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openNameDialog(null);
                }
            }));
        }
        if (!month.habits.isEmpty() && !month.isRest(today.getDayOfMonth())) {
            int left = Engine.restLeft(month);
            footer.addView(textButton("Ρεπό σήμερα (μένουν " + left + ")", DIM, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askRest();
                }
            }));
        }
        if (!month.habits.isEmpty()) {
            footer.addView(hint("Πάτα μια συνήθεια μόλις την κάνεις. Κράτα την πατημένη για ώρα, "
                    + "ειδοποιήσεις, κλείδωμα και χρονόμετρο. Τα μεσάνυχτα ξετικάρονται όλες για τη νέα μέρα."));
        }
    }

    private TextView textButton(String label, int color, View.OnClickListener l) {
        TextView t = text(15, color, true);
        t.setText(label);
        t.setPadding(0, dp(14), dp(12), dp(14));
        t.setBackground(Ui.withRipple(this, null, dp(10)));
        t.setOnClickListener(l);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(12, MUTED, false);
        t.setLineSpacing(0, 1.3f);
        t.setText(s);
        t.setPadding(0, dp(14), dp(12), 0);
        return t;
    }

    private void addEmptyState() {
        TextView title = text(22, TEXT, true);
        title.setText("Ξεκίνα από τα μικρά");
        title.setPadding(0, dp(24), 0, dp(6));
        list.addView(title);
        TextView body = text(15, DIM, false);
        body.setLineSpacing(0, 1.3f);
        body.setText("Διάλεξε 3 έως 6 καθημερινές συνήθειες που σου κάνουν καλό. Τις τικάρεις όταν τις κάνεις "
                + "και κάθε μέρα ξεκινούν από την αρχή. Σε όποια θες, βάζεις ώρα, ειδοποιήσεις ή κλείδωμα.");
        body.setPadding(0, 0, dp(8), dp(8));
        list.addView(body);
    }

    /** In the first days of a month, how the previous one went. */
    private void addLastMonthBanner() {
        if (today.getDayOfMonth() > 3) return;
        final YearMonth prev = shown.minusMonths(1);
        if (!store.hasData(prev)) return;
        MonthStore.Month p = store.load(prev);
        if (p.total() == 0) return;
        list.addView(textButton("Ο " + Ui.MONTHS[prev.getMonthValue() - 1] + " έκλεισε στο "
                + p.percent(prev.lengthOfMonth()) + "%. Δες τον", ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(prev);
            }
        }));
    }

    // ---------------------------------------------------------------- interactions

    private void toggleToday(MonthStore.Habit h) {
        // The screen was still showing yesterday: just bring it up to date.
        if (checkNewDay()) return;
        int d = today.getDayOfMonth();
        boolean done = !h.isDone(d);
        Engine.setDone(this, h.id, today, done);
        refresh();
        RowViews rv = rows.get(h.id);
        if (rv != null && rv.check != null) {
            rv.check.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (done) {
                rv.check.setScaleX(0.6f);
                rv.check.setScaleY(0.6f);
                rv.check.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(2.5f)).start();
            }
        }
    }

    private void askRest() {
        String blocker = Engine.restBlocker(this, LocalDateTime.now());
        if (blocker != null) {
            Toast.makeText(this, blocker, Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog d = Ui.dialog(this)
                .setTitle("Ρεπό σήμερα;")
                .setMessage("Καμία ειδοποίηση και κανένα κλείδωμα για σήμερα, και το σερί σου δεν σπάει. "
                        + "Μένουν " + Engine.restLeft(month) + " ρεπό τον " + Ui.monthAcc(today.getMonthValue()) + ".")
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Ρεπό", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Engine.restBlocker(MainActivity.this, LocalDateTime.now()) == null) {
                            Engine.setRest(MainActivity.this, today, true);
                        }
                        refresh();
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }

    /** A month calendar for one habit, where any day up to today can be ticked or unticked. */
    private void openCalendar(final MonthStore.Habit h) {
        // Keep the month the calendar was opened for, even if the shown one changes meanwhile.
        final MonthStore.Month m = month;
        final YearMonth ym = m.ym;
        final int days = ym.lengthOfMonth();
        final LocalDate opened = today;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(4));

        TextView title = text(19, TEXT, true);
        title.setText(h.name);
        title.setPadding(dp(4), 0, dp(4), 0);
        content.addView(title);
        final TextView sub = text(13, DIM, false);
        sub.setPadding(dp(4), dp(2), dp(4), dp(14));
        content.addView(sub);

        LinearLayout head = new LinearLayout(this);
        for (String w : WEEKDAY_INITIALS) {
            TextView t = text(12, DIM, true);
            t.setText(w);
            t.setGravity(Gravity.CENTER);
            head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        head.setPadding(0, 0, 0, dp(6));
        content.addView(head);

        final TextView[] cells = new TextView[days + 1];
        final Runnable redraw = new Runnable() {
            @Override
            public void run() {
                int c = h.count();
                sub.setText(Ui.MONTHS[ym.getMonthValue() - 1] + " " + ym.getYear() + ", "
                        + (c == 0 ? "καμία φορά" : c + (c == 1 ? " φορά" : " φορές")));
                for (int d = 1; d <= days; d++) styleDay(cells[d], ym.atDay(d), h.isDone(d), m.isRest(d), opened);
            }
        };

        int offset = ym.atDay(1).getDayOfWeek().getValue() - 1;
        int weeks = (offset + days + 6) / 7;
        for (int w = 0; w < weeks; w++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 7; c++) {
                FrameLayout slot = new FrameLayout(this);
                final int d = w * 7 + c - offset + 1;
                if (d >= 1 && d <= days) {
                    TextView cell = text(14, TEXT, false);
                    cell.setText(String.valueOf(d));
                    cell.setGravity(Gravity.CENTER);
                    cell.setFontFeatureSettings("tnum");
                    if (!ym.atDay(d).isAfter(opened)) {
                        cell.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                h.setDone(d, !h.isDone(d));
                                store.save(m);
                                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                redraw.run();
                                // Also updates streaks that run into the month being edited.
                                Engine.changed(MainActivity.this);
                                refresh();
                            }
                        });
                    }
                    slot.addView(cell, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
                    cells[d] = cell;
                }
                row.addView(slot, new LinearLayout.LayoutParams(0, dp(44), 1));
            }
            content.addView(row);
        }
        redraw.run();

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(content);
        AlertDialog dialog = Ui.dialog(this).setView(wrapper).setPositiveButton("Τέλος", null).show();
        Ui.styleDialog(this, dialog);
    }

    private void styleDay(TextView cell, LocalDate date, boolean done, boolean rest, LocalDate opened) {
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        boolean future = date.isAfter(opened);
        if (done) {
            oval.setColor(ACCENT);
            cell.setTextColor(ON_ACCENT);
        } else if (date.equals(opened)) {
            oval.setStroke(dp(1.5f), ACCENT);
            cell.setTextColor(ACCENT);
        } else if (rest) {
            oval.setStroke(dp(1), FAINT);
            cell.setTextColor(DIM);
        } else {
            oval.setColor(future ? 0 : CELL);
            cell.setTextColor(future ? MUTED : TEXT);
        }
        cell.setTypeface(done || date.equals(opened) ? Ui.MEDIUM : Typeface.DEFAULT);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        cell.setBackground(future ? oval : new RippleDrawable(ColorStateList.valueOf(RIPPLE), oval, mask));
    }

    private void showOptions(View anchor, final MonthStore.Habit h) {
        int index = month.habits.indexOf(h);
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.END);
        Menu m = pm.getMenu();
        m.add(0, 6, 0, "Ώρα, ειδοποιήσεις, κλείδωμα");
        m.add(0, 1, 1, "Μετονομασία");
        m.add(0, 2, 2, "Ημερολόγιο μήνα");
        if (index > 0) m.add(0, 3, 3, "Μετακίνηση πάνω");
        if (index < month.habits.size() - 1) m.add(0, 4, 4, "Μετακίνηση κάτω");
        m.add(0, 5, 5, "Διαγραφή");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 6:
                        openRules(h);
                        return true;
                    case 1:
                        openNameDialog(h);
                        return true;
                    case 2:
                        openCalendar(h);
                        return true;
                    case 3:
                    case 4:
                        checkNewDay();
                        int i = indexOf(h.id);
                        int j = item.getItemId() == 3 ? i - 1 : i + 1;
                        if (isCurrent() && i >= 0 && j >= 0 && j < month.habits.size()) {
                            Collections.swap(month.habits, i, j);
                            store.save(month);
                            Engine.changed(MainActivity.this);
                            render();
                        }
                        return true;
                    case 5:
                        confirmDelete(h);
                        return true;
                }
                return false;
            }
        });
        pm.show();
    }

    private void confirmDelete(final MonthStore.Habit h) {
        final boolean held = doc().rule(h.id).active();
        AlertDialog d = Ui.dialog(this)
                .setTitle("Διαγραφή «" + h.name + "»;")
                .setMessage(held
                        ? "Έχει ενεργή ώρα ή κλείδωμα σήμερα, οπότε φεύγει από αύριο. Οι προηγούμενοι μήνες μένουν όπως είναι."
                        : "Φεύγει από αυτόν τον μήνα μαζί με τα τικ του. Οι προηγούμενοι μήνες μένουν όπως είναι.")
                .setNegativeButton("Άκυρο", null)
                .setNeutralButton("Διαγραφή", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Past midnight into a new month, the habit to remove is that month's copy.
                        checkNewDay();
                        int i = indexOf(h.id);
                        if (!isCurrent() || i < 0) return;
                        Rules.Doc want = rules.wanted().copy();
                        want.habits.remove(h.id);
                        if (doc().rule(h.id).active()) {
                            // Its rules still hold today: it leaves the list tomorrow.
                            want.drop.add(h.id);
                        } else {
                            month.habits.remove(i);
                            store.save(month);
                        }
                        rules.edit(today, want);
                        Engine.changed(MainActivity.this);
                        render();
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }

    /** Adds a habit ({@code existing} null) or renames one. */
    private void openNameDialog(final MonthStore.Habit existing) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(4));

        TextView title = text(19, TEXT, true);
        title.setText(existing == null ? "Νέα συνήθεια" : "Μετονομασία");
        content.addView(title);
        if (existing == null) {
            TextView sub = text(13, DIM, false);
            sub.setText("Κάτι μικρό που θες να κάνεις κάθε μέρα");
            sub.setPadding(0, dp(2), 0, 0);
            content.addView(sub);
        }

        final EditText input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint("π.χ. Διάβασμα 20 λεπτά");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MonthStore.MAX_NAME)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        if (existing != null) {
            input.setText(existing.name);
            input.setSelection(input.getText().length());
        }
        LinearLayout.LayoutParams inLp = Ui.matchWrap();
        inLp.topMargin = dp(12);
        content.addView(input, inLp);

        if (existing == null) {
            LinearLayout row = new LinearLayout(this);
            for (final String s : SUGGESTIONS) {
                if (hasHabitNamed(month, s)) continue;
                TextView c = Ui.chip(this, s, false);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        input.setText(s);
                        input.setSelection(s.length());
                    }
                });
                LinearLayout.LayoutParams lp = Ui.wrap();
                lp.rightMargin = dp(6);
                row.addView(c, lp);
            }
            if (row.getChildCount() > 0) {
                TextView ideas = text(12, DIM, false);
                ideas.setText("Ιδέες");
                ideas.setPadding(0, dp(14), 0, dp(8));
                content.addView(ideas);
                HorizontalScrollView h = new HorizontalScrollView(this);
                h.setHorizontalScrollBarEnabled(false);
                h.addView(row);
                content.addView(h);
            }
        }

        final AlertDialog dialog = Ui.dialog(this)
                .setView(content)
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton(existing == null ? "Προσθήκη" : "Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String t = input.getText().toString().trim().replaceAll("\\s+", " ");
                        // Past midnight into a new month, the change belongs to that month.
                        checkNewDay();
                        if (t.isEmpty() || !isCurrent()) return;
                        if (existing != null) {
                            int i = indexOf(existing.id);
                            if (i < 0) return;
                            month.habits.get(i).name = t;
                        } else if (month.habits.size() < MonthStore.MAX_HABITS) {
                            MonthStore.Habit h = new MonthStore.Habit(store.nextId(), t);
                            h.since = today.getDayOfMonth();
                            month.habits.add(h);
                        } else {
                            return;
                        }
                        store.save(month);
                        Engine.changed(MainActivity.this);
                        render();
                    }
                })
                .create();
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            }
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        input.requestFocus();
        dialog.show();
        Ui.styleDialog(this, dialog);
    }

    /** Deadline, pressure level, lock reach and timer for one habit. */
    private void openRules(final MonthStore.Habit h) {
        final Rules.Rule r = rules.wanted().rule(h.id).copy();
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(4));
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = new Runnable() {
            @Override
            public void run() {
                content.removeAllViews();
                TextView title = text(19, TEXT, true);
                title.setText(h.name);
                content.addView(title);

                content.addView(Ui.label(MainActivity.this, "Είδος"));
                content.addView(choices(new String[]{"Κάτι που κάνω", "Κάτι που αποφεύγω"}, r.avoid ? 1 : 0, new Choice() {
                    @Override
                    public void picked(int i) {
                        r.avoid = i == 1;
                        redraw[0].run();
                    }
                }));

                if (!r.avoid) {
                    content.addView(Ui.label(MainActivity.this, "Ώρα-όριο"));
                    content.addView(timeRow(r.minute, "Χωρίς ώρα", new Choice() {
                        @Override
                        public void picked(int minute) {
                            r.minute = minute;
                            if (minute < 0) r.level = Rules.NONE;
                            redraw[0].run();
                        }
                    }));
                    if (r.minute >= 0) {
                        content.addView(Ui.label(MainActivity.this, "Πόσο να σε πιέζει"));
                        content.addView(choices(LEVELS, r.level, new Choice() {
                            @Override
                            public void picked(int i) {
                                r.level = i;
                                redraw[0].run();
                            }
                        }));
                        TextView help = text(13, DIM, false);
                        help.setText(LEVEL_HELP[r.level]);
                        help.setPadding(0, dp(8), 0, 0);
                        content.addView(help);
                        if (r.level == Rules.LOCK) {
                            content.addView(Ui.label(MainActivity.this, "Τι κλειδώνει"));
                            content.addView(choices(new String[]{"Όλες τις εφαρμογές", "Μόνο τη λίστα μου"}, r.all ? 0 : 1, new Choice() {
                                @Override
                                public void picked(int i) {
                                    r.all = i == 0;
                                    redraw[0].run();
                                }
                            }));
                            TextView note = text(13, DIM, false);
                            note.setText(r.all ? "Εκτός από τις εξαιρέσεις, τις κλήσεις και τις Ρυθμίσεις."
                                    : "Η λίστα σου αλλάζει από τις ρυθμίσεις.");
                            note.setPadding(0, dp(8), 0, 0);
                            content.addView(note);
                        }
                    }
                    String[] timers = new String[TIMERS.length];
                    int sel = 0;
                    for (int i = 0; i < TIMERS.length; i++) {
                        timers[i] = TIMERS[i] == 0 ? "Χωρίς" : TIMERS[i] + "'";
                        if (TIMERS[i] == r.timer) sel = i;
                    }
                    content.addView(Ui.label(MainActivity.this, "Χρονόμετρο"));
                    content.addView(choices(timers, sel, new Choice() {
                        @Override
                        public void picked(int i) {
                            r.timer = TIMERS[i];
                            redraw[0].run();
                        }
                    }));
                } else {
                    content.addView(Ui.label(MainActivity.this, "Μπλοκάρισμα της λίστας σου"));
                    content.addView(choices(new String[]{"Όχι", "Ναι"}, r.block ? 1 : 0, new Choice() {
                        @Override
                        public void picked(int i) {
                            r.block = i == 1;
                            redraw[0].run();
                        }
                    }));
                    if (r.block) {
                        content.addView(Ui.label(MainActivity.this, "Από"));
                        content.addView(timeRow(r.from, null, new Choice() {
                            @Override
                            public void picked(int minute) {
                                r.from = minute;
                                redraw[0].run();
                            }
                        }));
                        content.addView(Ui.label(MainActivity.this, "Έως"));
                        content.addView(timeRow(r.until >= 24 * 60 ? 24 * 60 - 1 : r.until, null, new Choice() {
                            @Override
                            public void picked(int minute) {
                                r.until = minute == 24 * 60 - 1 ? 24 * 60 : minute;
                                redraw[0].run();
                            }
                        }));
                        TextView note = text(13, DIM, false);
                        note.setText("Τα μεσάνυχτα τικάρεται μόνη της, αν δεν χρειάστηκε πάσο.");
                        note.setPadding(0, dp(8), 0, 0);
                        content.addView(note);
                    }
                }
                TextView rule = text(12, MUTED, false);
                rule.setText("Ό,τι σε κάνει πιο αυστηρό ισχύει αμέσως. Ό,τι σε χαλαρώνει ισχύει από αύριο.");
                rule.setPadding(0, dp(18), 0, dp(4));
                content.addView(rule);
            }
        };
        redraw[0].run();

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(content);
        AlertDialog d = Ui.dialog(this)
                .setView(wrapper)
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveRule(h, r);
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }

    private void saveRule(MonthStore.Habit h, Rules.Rule r) {
        checkNewDay();
        if (r.avoid && r.from >= r.until) r.until = 24 * 60;
        Rules.Doc want = rules.wanted().copy();
        want.habits.put(h.id, r);
        boolean later = rules.edit(today, want);
        Engine.changed(this);
        refresh();
        if (later) Toast.makeText(this, "Ό,τι χαλαρώνει ισχύει από αύριο. Σήμερα μένουν οι αυστηρότεροι κανόνες.", Toast.LENGTH_LONG).show();
        if (r.active() && !hasNotificationPermission()) requestNotificationPermission();
        boolean locks = !r.avoid && r.level == Rules.LOCK || r.avoid && r.block;
        if (locks && !LockService.isOn(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (locks && want.block.isEmpty() && (r.avoid || !r.all)) {
            Toast.makeText(this, "Η λίστα σου είναι άδεια: διάλεξε εφαρμογές από τις ρυθμίσεις.", Toast.LENGTH_LONG).show();
        }
    }

    private interface Choice {
        void picked(int value);
    }

    private View choices(String[] labels, int selected, final Choice choice) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView c = Ui.chip(this, labels[i], i == selected);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choice.picked(k);
                }
            });
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.rightMargin = dp(6);
            row.addView(c, lp);
        }
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(row);
        return h;
    }

    /** A time (or {@code none} when it can be cleared), opening a picker when tapped. */
    private View timeRow(final int minute, final String none, final Choice choice) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.chip(this, minute >= 0 ? Ui.time(minute) : none, minute >= 0);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int m = minute >= 0 ? minute : 20 * 60;
                TimePickerDialog p = new TimePickerDialog(MainActivity.this, R.style.DialogTheme,
                        new TimePickerDialog.OnTimeSetListener() {
                            @Override
                            public void onTimeSet(TimePicker view, int hour, int min) {
                                choice.picked(hour * 60 + min);
                            }
                        }, m / 60, m % 60, true);
                p.show();
            }
        });
        row.addView(t);
        if (none != null && minute >= 0) {
            TextView clear = Ui.chip(this, none, false);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choice.picked(-1);
                }
            });
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = dp(6);
            row.addView(clear, lp);
        }
        return row;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                || !Engine.state(this).getBoolean("asked_notif", false)) {
            Engine.state(this).edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        } else {
            // Android will not ask again: the switch is in the app's notification settings.
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private int indexOf(int habitId) {
        for (int i = 0; i < month.habits.size(); i++) {
            if (month.habits.get(i).id == habitId) return i;
        }
        return -1;
    }

    private static boolean hasHabitNamed(MonthStore.Month m, String name) {
        for (MonthStore.Habit h : m.habits) {
            if (h.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
