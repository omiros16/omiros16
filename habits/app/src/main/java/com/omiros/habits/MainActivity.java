package com.omiros.habits;

import static com.omiros.habits.Theme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final String[] MONTHS = {"Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος",
            "Ιούνιος", "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος"};
    private static final String[] MONTHS_GEN = {"Ιανουαρίου", "Φεβρουαρίου", "Μαρτίου", "Απριλίου", "Μαΐου",
            "Ιουνίου", "Ιουλίου", "Αυγούστου", "Σεπτεμβρίου", "Οκτωβρίου", "Νοεμβρίου", "Δεκεμβρίου"};
    private static final String[] WEEKDAYS = {"Δευτέρα", "Τρίτη", "Τετάρτη", "Πέμπτη", "Παρασκευή", "Σάββατο", "Κυριακή"};
    private static final String[] WEEKDAY_INITIALS = {"Δ", "Τ", "Τ", "Π", "Π", "Σ", "Κ"};
    private static final String[] SUGGESTIONS = {"Γυμναστική", "Διάβασμα", "Διαλογισμός", "Περπάτημα",
            "2 λίτρα νερό", "Ύπνος πριν τις 12", "Χωρίς social media", "Ημερολόγιο", "Διατάσεις"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            checkNewDay();
            handler.postDelayed(this, 30_000);
        }
    };

    private MonthStore store;
    private LocalDate today;
    private YearMonth shown;
    private MonthStore.Month month;

    private TextView subtitleView;
    private TextView titleView;
    private ImageView historyButton;
    private ImageView prevButton;
    private ImageView nextButton;
    private TextView statusView;
    private LinearLayout bar;
    private LinearLayout list;
    /** The cards on screen, by habit id, so a tick updates one card in place instead of rebuilding. */
    private final SparseArray<CardViews> cards = new SparseArray<>();

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** The parts of a habit card that change when it is ticked. */
    private static final class CardViews {
        View card;
        GradientDrawable background;
        ImageView check;
        GradientDrawable checkCircle;
        TextView meta;
        DotStrip strip;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MonthStore(this);
        today = LocalDate.now();
        String saved = savedInstanceState == null ? null : savedInstanceState.getString("month");
        shown = saved != null ? YearMonth.parse(saved) : YearMonth.from(today);
        if (shown.isAfter(YearMonth.from(today))) shown = YearMonth.from(today);
        setContentView(buildUi());
        showMonth(shown);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNewDay();
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
        // From an earlier month, Back returns to the current one before leaving the app.
        if (!isCurrent()) showMonth(YearMonth.from(today));
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("month", shown.toString());
    }

    /**
     * Follows the clock past midnight: a new month starts from zero, and if the shown month was
     * the current one the screen moves on to the new current month. Returns true if the day changed.
     */
    private boolean checkNewDay() {
        LocalDate now = LocalDate.now();
        if (now.equals(today)) return false;
        YearMonth was = YearMonth.from(today);
        YearMonth current = YearMonth.from(now);
        today = now;
        if ((shown.equals(was) && !was.equals(current)) || shown.isAfter(current)) showMonth(current);
        else render();
        return true;
    }

    private boolean isCurrent() {
        return shown.equals(YearMonth.from(today));
    }

    // ---------------------------------------------------------------- UI construction

    private View buildUi() {
        // No background of its own: the window's is the same colour, and drawing it twice costs every frame.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(10), dp(18));
        root.addView(header);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(top);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        subtitleView = text(13, ACCENT, true);
        titleView = text(30, TEXT, true);
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

        historyButton = iconButton(R.drawable.ic_history);
        historyButton.setContentDescription("Προηγούμενοι μήνες");
        historyButton.setImageTintList(ColorStateList.valueOf(DIM));
        historyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(shown.minusMonths(1));
            }
        });
        top.addView(historyButton);

        prevButton = iconButton(R.drawable.ic_chevron_left);
        prevButton.setContentDescription("Προηγούμενος μήνας");
        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(shown.minusMonths(1));
            }
        });
        top.addView(prevButton);

        nextButton = iconButton(R.drawable.ic_chevron_right);
        nextButton.setContentDescription("Επόμενος μήνας");
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(shown.plusMonths(1));
            }
        });
        top.addView(nextButton);

        statusView = text(13, DIM, false);
        statusView.setPadding(0, dp(14), 0, dp(8));
        header.addView(statusView);

        bar = new LinearLayout(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        barLp.rightMargin = dp(10);
        header.addView(bar, barLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(2), dp(16), dp(28));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    // ---------------------------------------------------------------- rendering

    private void showMonth(YearMonth ym) {
        shown = ym;
        month = store.load(ym);
        render();
    }

    /** Rebuilds the whole screen: after a change of month, day or habit list. */
    private void render() {
        renderHeader();
        boolean current = isCurrent();
        int n = month.habits.size();

        list.removeAllViews();
        cards.clear();
        if (current) addLastMonthBanner();
        if (n == 0 && current) addEmptyState();

        for (int i = 0; i < n; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            list.addView(buildCard(month.habits.get(i), current), lp);
        }

        if (current && n < MonthStore.MAX_HABITS) list.addView(buildAddButton());

        if (n > 0) {
            TextView hint = text(12, DIM, false);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(0, 1.35f);
            hint.setPadding(dp(12), dp(18), dp(12), 0);
            hint.setText(current
                    ? "Πάτα μια συνήθεια μόλις την κάνεις.\n"
                    + "Τα μεσάνυχτα ξετικάρονται όλες για τη νέα μέρα.\n"
                    + "Πάτα τις τελείες για προηγούμενες μέρες.\n"
                    + "Κράτα πατημένη μια συνήθεια για αλλαγές."
                    : "Πάτα μια συνήθεια για να δεις ή να διορθώσεις τις μέρες της.");
            list.addView(hint);
        }
    }

    /** Updates the header and every card in place: after ticks, which change no layout. */
    private void refresh() {
        for (MonthStore.Habit h : month.habits) {
            if (cards.get(h.id) == null) {
                render();
                return;
            }
        }
        renderHeader();
        for (MonthStore.Habit h : month.habits) bindCard(h, cards.get(h.id));
    }

    private void renderHeader() {
        boolean current = isCurrent();
        int n = month.habits.size();
        int todayNum = today.getDayOfMonth();
        int elapsed = current ? todayNum : shown.lengthOfMonth();

        // Header: today's date, or the month being looked back at.
        YearMonth earliest = store.earliest();
        if (current) {
            subtitleView.setText(WEEKDAYS[today.getDayOfWeek().getValue() - 1] + " " + todayNum
                    + " " + MONTHS_GEN[today.getMonthValue() - 1]);
            titleView.setText("Σήμερα");
        } else {
            subtitleView.setText("Ιστορικό · " + shown.getYear());
            titleView.setText(MONTHS[shown.getMonthValue() - 1]);
        }
        historyButton.setVisibility(current && earliest != null && earliest.isBefore(shown) ? View.VISIBLE : View.GONE);
        prevButton.setVisibility(current ? View.GONE : View.VISIBLE);
        nextButton.setVisibility(current ? View.GONE : View.VISIBLE);
        setEnabled(prevButton, earliest != null && shown.isAfter(earliest));

        int doneToday = 0;
        if (current) {
            for (MonthStore.Habit h : month.habits) {
                if (h.isDone(todayNum)) doneToday++;
            }
        }
        int pct = month.percent(elapsed);
        bar.removeAllViews();
        // The current month's empty state below explains itself.
        statusView.setVisibility(n == 0 && current ? View.GONE : View.VISIBLE);
        if (n == 0) {
            statusView.setText("Δεν υπάρχουν καταγραφές γι' αυτόν τον μήνα");
            bar.setVisibility(View.GONE);
        } else if (current) {
            String name = MONTHS[shown.getMonthValue() - 1];
            // "τον Οκτώβριο": every month name takes the accusative by dropping its final ς.
            statusView.setText((doneToday == n ? "Έγιναν όλες" : "Έγιναν " + doneToday + " από " + n)
                    + " · " + pct + "% τον " + name.substring(0, name.length() - 1));
            bar.setVisibility(View.VISIBLE);
            // One segment per habit, filled as they get done today.
            for (int i = 0; i < n; i++) {
                View seg = new View(this);
                seg.setBackground(rounded(i < doneToday ? ACCENT : FAINT, dp(3)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (i > 0) lp.leftMargin = dp(5);
                bar.addView(seg, lp);
            }
        } else {
            int total = month.total();
            statusView.setText("Ολοκλήρωση " + pct + "% · " + total + (total == 1 ? " φορά" : " φορές") + " συνολικά");
            bar.setVisibility(View.VISIBLE);
            bar.setBackground(rounded(FAINT, dp(3)));
            bar.setClipToOutline(true);
            View fill = new View(this);
            fill.setBackground(rounded(ACCENT, dp(3)));
            bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
        }
        if (current) bar.setBackground(null);
    }

    private View buildCard(final MonthStore.Habit h, final boolean current) {
        final CardViews cv = new CardViews();
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(14), dp(10));
        cv.card = card;
        cv.background = roundedRect(CARD, LINE, false);
        card.setBackground(withRipple(cv.background));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(17, TEXT, true);
        name.setText(h.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        cv.meta = text(13, DIM, false);
        cv.meta.setPadding(0, dp(2), 0, 0);
        col.addView(cv.meta);
        top.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (current) {
            cv.check = new ImageView(this);
            cv.check.setScaleType(ImageView.ScaleType.CENTER);
            cv.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cv.check.setImageTintList(ColorStateList.valueOf(ON_ACCENT));
            cv.checkCircle = new GradientDrawable();
            cv.checkCircle.setShape(GradientDrawable.OVAL);
            cv.check.setBackground(cv.checkCircle);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(40), dp(40));
            clp.leftMargin = dp(12);
            top.addView(cv.check, clp);
        }

        DotStrip strip = new DotStrip(this);
        cv.strip = strip;
        strip.setPadding(0, dp(10), dp(4), dp(10));
        strip.setBackground(ripple(0, dp(8)));
        strip.setContentDescription("Μέρες του μήνα για " + h.name);
        strip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCalendar(h);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        slp.topMargin = dp(6);
        card.addView(strip, slp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current) toggleToday(h);
                else openCalendar(h);
            }
        });
        if (current) {
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showOptions(card, h);
                    return true;
                }
            });
        }
        cards.put(h.id, cv);
        bindCard(h, cv);
        return card;
    }

    /** Shows the habit's current state on its card; only changes colours and text, never layout. */
    private void bindCard(MonthStore.Habit h, CardViews cv) {
        boolean current = isCurrent();
        int days = shown.lengthOfMonth();
        boolean doneToday = current && h.isDone(today.getDayOfMonth());
        cv.background.setColor(doneToday ? CARD_DONE : CARD);
        cv.background.setStroke(dp(1), doneToday ? LINE_DONE : LINE);
        if (current) {
            cv.meta.setText(currentMeta(h.count(), store.streak(h.id, today, month)));
            cv.checkCircle.setColor(doneToday ? ACCENT : 0);
            cv.checkCircle.setStroke(dp(2), doneToday ? ACCENT : RING);
            if ((cv.check.getDrawable() != null) != doneToday) {
                cv.check.setImageDrawable(doneToday ? getDrawable(R.drawable.ic_check) : null);
            }
            cv.card.setContentDescription(h.name + (doneToday ? ", έγινε σήμερα" : ", δεν έγινε ακόμα σήμερα"));
        } else {
            cv.meta.setText(h.count() + " από " + (days - Math.min(h.since, days) + 1) + " μέρες");
        }
        cv.strip.set(days, h.days, current ? today.getDayOfMonth() : Integer.MAX_VALUE, h.since);
    }

    private static String currentMeta(int count, int streak) {
        // A streak carried over from last month, before the first tick of this one.
        if (count == 0) return streak >= 2 ? streak + " μέρες στη σειρά · συνέχισε σήμερα" : "Καμία φορά ακόμα";
        String s = count + (count == 1 ? " φορά" : " φορές");
        if (streak >= 2) s += " · " + streak + " μέρες στη σειρά";
        return s;
    }

    private View buildAddButton() {
        TextView add = text(15, ACCENT, true);
        add.setText("+  Νέα συνήθεια");
        add.setGravity(Gravity.CENTER);
        add.setBackground(withRipple(roundedRect(0, ADD_BORDER, true)));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNameDialog(null);
            }
        });
        add.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return add;
    }

    private void addEmptyState() {
        TextView title = text(20, TEXT, true);
        title.setText("Ξεκίνα από τα μικρά");
        title.setPadding(dp(4), dp(28), dp(4), dp(6));
        list.addView(title);
        TextView body = text(15, DIM, false);
        body.setLineSpacing(0, 1.3f);
        body.setText("Διάλεξε 3 έως 6 καθημερινές συνήθειες που σου κάνουν καλό. "
                + "Τις τικάρεις όταν τις κάνεις και κάθε μέρα ξεκινούν από την αρχή, "
                + "ενώ μετράνε πόσες μέρες τις έκανες μέσα στον μήνα.");
        body.setPadding(dp(4), 0, dp(4), dp(24));
        list.addView(body);
    }

    /** In the first days of a month, a reminder of how the previous one went. */
    private void addLastMonthBanner() {
        if (today.getDayOfMonth() > 3) return;
        final YearMonth prev = shown.minusMonths(1);
        if (!store.hasData(prev)) return;
        MonthStore.Month p = store.load(prev);
        if (p.total() == 0) return;
        TextView banner = text(14, ACCENT, true);
        banner.setText("Ο " + MONTHS[prev.getMonthValue() - 1] + " έκλεισε στο "
                + p.percent(prev.lengthOfMonth()) + "%  ·  δες τον ›");
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackground(withRipple(roundedRect(ACCENT_SOFT, ACCENT_SOFT, false)));
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonth(prev);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        list.addView(banner, lp);
    }

    // ---------------------------------------------------------------- interactions

    private void toggleToday(MonthStore.Habit h) {
        // The screen was still showing yesterday: just bring it up to date.
        if (checkNewDay()) return;
        int d = today.getDayOfMonth();
        boolean done = !h.isDone(d);
        h.setDone(d, done);
        store.save(month);
        refresh();
        CardViews cv = cards.get(h.id);
        if (cv != null && cv.check != null) {
            cv.check.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (done) {
                cv.check.setScaleX(0.6f);
                cv.check.setScaleY(0.6f);
                cv.check.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(2.5f)).start();
            }
        }
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
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                int c = h.count();
                sub.setText(MONTHS[ym.getMonthValue() - 1] + " " + ym.getYear() + " · "
                        + (c == 0 ? "καμία φορά" : c + (c == 1 ? " φορά" : " φορές")));
                for (int d = 1; d <= days; d++) styleDay(cells[d], ym.atDay(d), h.isDone(d), opened);
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
                                refresh.run();
                                // Also updates streaks that run into the month being edited.
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
        refresh.run();

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setView(wrapper)
                .setPositiveButton("Τέλος", null)
                .show();
        styleDialog(dialog);
    }

    private void styleDay(TextView cell, LocalDate date, boolean done, LocalDate opened) {
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        boolean future = date.isAfter(opened);
        if (done) {
            oval.setColor(ACCENT);
            cell.setTextColor(ON_ACCENT);
        } else if (date.equals(opened)) {
            oval.setStroke(dp(1.5f), ACCENT);
            cell.setTextColor(ACCENT);
        } else {
            oval.setColor(future ? 0 : CELL);
            cell.setTextColor(future ? MUTED : TEXT);
        }
        cell.setTypeface(done || date.equals(opened) ? MEDIUM : Typeface.DEFAULT);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        cell.setBackground(future ? oval : new RippleDrawable(ColorStateList.valueOf(RIPPLE), oval, mask));
    }

    private void showOptions(View anchor, final MonthStore.Habit h) {
        int index = month.habits.indexOf(h);
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.END);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Μετονομασία");
        m.add(0, 2, 1, "Ημερολόγιο μήνα");
        if (index > 0) m.add(0, 3, 2, "Μετακίνηση πάνω");
        if (index < month.habits.size() - 1) m.add(0, 4, 3, "Μετακίνηση κάτω");
        m.add(0, 5, 4, "Διαγραφή");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
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
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Διαγραφή «" + h.name + "»;")
                .setMessage("Φεύγει από αυτόν τον μήνα μαζί με τα τικ του. Οι προηγούμενοι μήνες μένουν όπως είναι.")
                .setNegativeButton("Άκυρο", null)
                .setNeutralButton("Διαγραφή", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Past midnight into a new month, the habit to remove is that month's copy.
                        checkNewDay();
                        int i = indexOf(h.id);
                        if (!isCurrent() || i < 0) return;
                        month.habits.remove(i);
                        store.save(month);
                        render();
                    }
                })
                .show();
        styleDialog(d);
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
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = dp(12);
        content.addView(input, inLp);

        if (existing == null) {
            LinearLayout row = new LinearLayout(this);
            for (final String s : SUGGESTIONS) {
                if (hasHabitNamed(month, s)) continue;
                TextView c = chip(s);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        input.setText(s);
                        input.setSelection(s.length());
                    }
                });
                LinearLayout.LayoutParams lp = wrap();
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

        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
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
        styleDialog(dialog);
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

    /** Rounded corners and mixed-case buttons (Greek all-caps would otherwise keep the accents). */
    private void styleDialog(AlertDialog d) {
        d.getWindow().setBackgroundDrawable(new InsetDrawable(rounded(CARD, dp(24)), dp(16), dp(24), dp(16), dp(24)));
        int[] ids = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int id : ids) {
            Button b = d.getButton(id);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextColor(id == DialogInterface.BUTTON_NEUTRAL ? RED : id == DialogInterface.BUTTON_POSITIVE ? ACCENT : DIM);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.25f);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(MEDIUM);
        return tv;
    }

    private ImageView iconButton(int res) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(TEXT));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackground(ripple(0, dp(22)));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return iv;
    }

    private TextView chip(String label) {
        TextView tv = text(14, TEXT, false);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(CHIP);
        g.setStroke(dp(1), LINE);
        tv.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), g, null));
        return tv;
    }

    private Drawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Rounded card shape with a hairline border; {@code dashed} for the "add" placeholder. */
    private GradientDrawable roundedRect(int color, int stroke, boolean dashed) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        if (dashed) g.setStroke(dp(1.5f), stroke, dp(6), dp(5));
        else g.setStroke(dp(1), stroke);
        return g;
    }

    /** Touch feedback over a card shape; the shape stays live, so its colours can change later. */
    private Drawable withRipple(GradientDrawable shape) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(18));
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, mask);
    }

    private Drawable ripple(int color, int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE),
                color == 0 ? new ColorDrawable(0) : rounded(color, radius), mask);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
