package com.omiros.timetable;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = 0xFF0E0E10;
    private static final int SURFACE = 0xFF1A1A1E;
    private static final int EMPTY_BOX = 0xFF18181C;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int DIM = 0xFF8A8A93;
    private static final int ACCENT = 0xFFFF6A00;
    private static final int GREEN = 0xFF2ECC71;
    private static final int RED = 0xFFE74C3C;
    private static final int DONE_BOX = 0xFF1E4A2C;
    private static final int MISSED_BOX = 0xFF4A2020;
    private static final int[] PALETTE = {
            0xFF2B3A55, 0xFF3B2F55, 0xFF22474A, 0xFF4A3A22, 0xFF483045, 0xFF33414A, 0xFF4A3328,
    };
    private static final int[] QUICK_DURATIONS = {15, 30, 45, 60, 90, 120, 180};

    private static final Locale GREEK = new Locale("el", "GR");
    private static final DateTimeFormatter TITLE_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM", GREEK);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 30_000);
        }
    };

    private DayStore store;
    private LocalDate date;
    private DayStore.Day day;

    private TextView titleView;
    private TextView subtitleView;
    private TextView todayChip;
    private TextView tomorrowChip;
    private TextView progressText;
    private View barDone;
    private View barMissed;
    private View barRest;
    private TextView banner;
    private ScrollView scroll;
    private final View[] rows = new View[DayStore.SLOTS];
    private final TextView[] timeViews = new TextView[DayStore.SLOTS];
    private final TextView[] boxViews = new TextView[DayStore.SLOTS];
    private final TextView[] statusViews = new TextView[DayStore.SLOTS];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new DayStore(this);
        String saved = savedInstanceState == null ? null : savedInstanceState.getString("date");
        date = saved != null ? LocalDate.parse(saved) : LocalDate.now();
        setContentView(buildUi());
        showDate(date, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("date", date.toString());
    }

    // ---------------------------------------------------------------- UI construction

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(SURFACE);
        header.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(header);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(nav);

        TextView prev = iconButton("‹");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(date.minusDays(1), true);
            }
        });
        nav.addView(prev);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setGravity(Gravity.CENTER);
        titleCol.setPadding(0, dp(4), 0, dp(4));
        titleCol.setBackground(ripple(0, dp(12)));
        titleView = text(20, TEXT, true);
        titleView.setGravity(Gravity.CENTER);
        subtitleView = text(13, DIM, false);
        subtitleView.setGravity(Gravity.CENTER);
        titleCol.addView(titleView);
        titleCol.addView(subtitleView);
        titleCol.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate(false);
            }
        });
        nav.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView next = iconButton("›");
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(date.plusDays(1), true);
            }
        });
        nav.addView(next);

        final TextView menu = iconButton("⋮");
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(menu);
            }
        });
        nav.addView(menu);

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(dp(12), dp(6), dp(12), dp(10));
        header.addView(chips);

        todayChip = chip("Σήμερα");
        todayChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(LocalDate.now(), true);
            }
        });
        chips.addView(todayChip);

        tomorrowChip = chip("Αύριο");
        tomorrowChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(LocalDate.now().plusDays(1), true);
            }
        });
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(8);
        chips.addView(tomorrowChip, chipLp);

        progressText = text(13, DIM, false);
        progressText.setGravity(Gravity.END);
        chips.addView(progressText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout bar = new LinearLayout(this);
        barDone = new View(this);
        barDone.setBackgroundColor(GREEN);
        barMissed = new View(this);
        barMissed.setBackgroundColor(RED);
        barRest = new View(this);
        barRest.setBackgroundColor(0xFF2A2A30);
        bar.addView(barDone, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0));
        bar.addView(barMissed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0));
        bar.addView(barRest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        banner = text(14, TEXT, false);
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        banner.setBackground(ripple(0xFF2A1A0C, 0));
        banner.setText("🌙  Δεν έχεις γράψει ακόμα το αύριο — πάτα εδώ για να το σχεδιάσεις");
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(LocalDate.now().plusDays(1), true);
            }
        });
        root.addView(banner);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(8), dp(24));
        for (int i = 0; i < DayStore.SLOTS; i++) {
            list.addView(buildRow(i));
        }
        TextView hint = text(12, DIM, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(20), dp(16), 0);
        hint.setText("Πάτα ένα κουτί για να γράψεις τι θα κάνεις.\n"
                + "Κράτα πατημένο ένα γεμάτο κουτί για νέα δραστηριότητα από εκείνη την ώρα.\n"
                + "Πάτα τον κύκλο δεξιά: ✓ έγινε · ✗ δεν έγινε.");
        list.addView(hint);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private View buildRow(final int i) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        boolean hour = i % 4 == 0;
        TextView time = text(hour ? 15 : 12, hour ? TEXT : DIM, hour);
        time.setText(formatMin(slotStart(i)));
        time.setFontFeatureSettings("tnum");
        row.addView(time, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView box = text(15, TEXT, false);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(12), 0);
        box.setSingleLine(true);
        box.setEllipsize(TextUtils.TruncateAt.END);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBoxTap(i);
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onBoxLongPress(i);
                return true;
            }
        });
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        TextView status = text(18, DIM, true);
        status.setGravity(Gravity.CENTER);
        status.setBackground(ripple(0, dp(20)));
        status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleStatus(i);
            }
        });
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        stLp.leftMargin = dp(4);
        row.addView(status, stLp);

        rows[i] = row;
        timeViews[i] = time;
        boxViews[i] = box;
        statusViews[i] = status;
        return row;
    }

    // ---------------------------------------------------------------- rendering

    private void showDate(LocalDate d, boolean scrollToFocus) {
        date = d;
        day = store.load(d);
        render();
        if (scrollToFocus) {
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    int now = nowSlot();
                    int target = now >= 0 ? Math.max(0, now - 2) : 0;
                    scroll.scrollTo(0, rows[target].getTop());
                }
            });
        }
    }

    private void render() {
        LocalDate today = LocalDate.now();
        titleView.setText(capitalize(date.format(TITLE_FMT)));
        subtitleView.setText(relativeLabel(date, today));
        styleChip(todayChip, date.equals(today));
        styleChip(tomorrowChip, date.equals(today.plusDays(1)));

        boolean tomorrowEmpty = date.equals(today) && store.load(today.plusDays(1)).isEmpty();
        banner.setVisibility(tomorrowEmpty && LocalTime.now().getHour() >= 18 ? View.VISIBLE : View.GONE);

        int now = nowSlot();
        boolean past = date.isBefore(today);
        int planned = 0;
        int done = 0;
        int missed = 0;
        float r = dp(10);

        for (int i = 0; i < DayStore.SLOTS; i++) {
            String t = day.text[i];
            boolean filled = t != null;
            boolean joinPrev = filled && i > 0 && t.equals(day.text[i - 1]);
            boolean joinNext = filled && i < DayStore.SLOTS - 1 && t.equals(day.text[i + 1]);
            int st = day.status[i];

            if (filled) {
                planned++;
                if (st == DayStore.DONE) done++;
                if (st == DayStore.MISSED) missed++;
            }

            GradientDrawable bg = new GradientDrawable();
            int color;
            if (!filled) color = EMPTY_BOX;
            else if (st == DayStore.DONE) color = DONE_BOX;
            else if (st == DayStore.MISSED) color = MISSED_BOX;
            else color = PALETTE[(t.hashCode() & 0x7fffffff) % PALETTE.length];
            bg.setColor(color);
            float top = joinPrev ? 0 : r;
            float bottom = joinNext ? 0 : r;
            bg.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
            if (i == now) bg.setStroke(dp(2), ACCENT);

            TextView box = boxViews[i];
            box.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
            box.setText(filled && !joinPrev ? t : "");
            box.setPaintFlags(st == DayStore.MISSED && filled
                    ? box.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    : box.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
            lp.topMargin = joinPrev ? 0 : dp(2);
            lp.bottomMargin = joinNext ? 0 : dp(2);
            box.setLayoutParams(lp);

            TextView sv = statusViews[i];
            if (filled && !joinPrev) {
                sv.setVisibility(View.VISIBLE);
                sv.setText(st == DayStore.DONE ? "✓" : st == DayStore.MISSED ? "✗" : "○");
                sv.setTextColor(st == DayStore.DONE ? GREEN : st == DayStore.MISSED ? RED : DIM);
            } else {
                sv.setVisibility(View.INVISIBLE);
            }

            TextView tv = timeViews[i];
            boolean hour = i % 4 == 0;
            tv.setTextColor(i == now ? ACCENT : hour ? TEXT : DIM);
            tv.setAlpha(past || (now >= 0 && i < now) ? 0.45f : 1f);
        }

        if (planned == 0) {
            progressText.setText("Άδεια μέρα");
        } else {
            String s = "✓ " + formatDuration(done * DayStore.SLOT_MIN) + " / " + formatDuration(planned * DayStore.SLOT_MIN);
            if (missed > 0) s += "   ✗ " + formatDuration(missed * DayStore.SLOT_MIN);
            progressText.setText(s);
        }
        setWeight(barDone, done);
        setWeight(barMissed, missed);
        setWeight(barRest, planned == 0 ? 1 : planned - done - missed);
    }

    // ---------------------------------------------------------------- interactions

    private void onBoxTap(int i) {
        if (day.text[i] == null) {
            openEditor(i, 1, false);
        } else {
            int s = blockStart(i);
            openEditor(s, blockLen(s), true);
        }
    }

    private void onBoxLongPress(int i) {
        // Start a new activity at exactly this slot, splitting an existing block.
        openEditor(i, 1, false);
    }

    private void cycleStatus(int i) {
        if (day.text[i] == null) return;
        int s = blockStart(i);
        int next = (day.status[s] + 1) % 3;
        for (int k = s, n = s + blockLen(s); k < n; k++) day.status[k] = next;
        store.save(date, day);
        render();
    }

    private void openEditor(final int start, int initialLen, final boolean existing) {
        final int origLen = existing ? initialLen : 0;
        final int maxLen = DayStore.SLOTS - start;
        final int[] len = {initialLen};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(4));

        final TextView range = text(18, ACCENT, true);
        content.addView(range);

        final EditText input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(DIM);
        input.setHint("Τι θα κάνεις;");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        if (existing) {
            input.setText(day.text[start]);
            input.setSelection(input.getText().length());
        }
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = dp(8);
        content.addView(input, inLp);

        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.setPadding(0, dp(12), 0, dp(4));
        TextView minus = iconButton("−");
        final TextView durLabel = text(16, TEXT, true);
        durLabel.setGravity(Gravity.CENTER);
        TextView plus = iconButton("+");
        stepper.addView(minus);
        stepper.addView(durLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stepper.addView(plus);
        content.addView(stepper);

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                range.setText(formatMin(slotStart(start)) + " – " + formatMin(slotStart(start + len[0])));
                durLabel.setText(formatDuration(len[0] * DayStore.SLOT_MIN));
            }
        };
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (len[0] > 1) len[0]--;
                refresh.run();
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (len[0] < maxLen) len[0]++;
                refresh.run();
            }
        });

        LinearLayout quick = new LinearLayout(this);
        for (final int minutes : QUICK_DURATIONS) {
            TextView c = chip(formatDurationShort(minutes));
            styleChip(c, false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    len[0] = Math.min(maxLen, minutes / DayStore.SLOT_MIN);
                    refresh.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            quick.addView(c, lp);
        }
        content.addView(hscroll(quick));

        List<String> recent = store.recent();
        if (!recent.isEmpty()) {
            TextView label = text(12, DIM, false);
            label.setText("Πρόσφατα");
            label.setPadding(0, dp(14), 0, dp(6));
            content.addView(label);
            final LinearLayout recentRow = new LinearLayout(this);
            for (final String r : recent) {
                final TextView c = chip(r);
                styleChip(c, false);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        input.setText(r);
                        input.setSelection(r.length());
                    }
                });
                c.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        store.removeRecent(r);
                        recentRow.removeView(c);
                        toast("Αφαιρέθηκε από τα πρόσφατα");
                        return true;
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                recentRow.addView(c, lp);
            }
            content.addView(hscroll(recentRow));
        }
        refresh.run();

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(content);

        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(wrapper)
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String t = input.getText().toString().trim();
                        if (t.isEmpty()) {
                            if (existing) clearRange(start, origLen);
                        } else {
                            applyEdit(start, origLen, len[0], t);
                        }
                    }
                });
        if (existing) {
            b.setNeutralButton("Διαγραφή", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    clearRange(start, origLen);
                }
            });
        }
        final AlertDialog dialog = b.create();
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
        if (!existing) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        }
        dialog.show();
        styleButtons(dialog);
    }

    private void applyEdit(int start, int origLen, int len, String t) {
        String oldText = origLen > 0 ? day.text[start] : null;
        int oldStatus = origLen > 0 ? day.status[start] : DayStore.NONE;
        for (int k = start; k < start + origLen; k++) {
            day.text[k] = null;
            day.status[k] = DayStore.NONE;
        }
        int keep = t.equals(oldText) ? oldStatus : DayStore.NONE;
        for (int k = start; k < Math.min(DayStore.SLOTS, start + len); k++) {
            day.text[k] = t;
            day.status[k] = keep;
        }
        store.save(date, day);
        store.addRecent(t);
        render();
    }

    private void clearRange(int start, int len) {
        for (int k = start; k < start + len; k++) {
            day.text[k] = null;
            day.status[k] = DayStore.NONE;
        }
        store.save(date, day);
        render();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        final Menu m = pm.getMenu();
        m.add(0, 1, 0, "Αντιγραφή από προηγούμενη μέρα");
        m.add(0, 2, 1, "Αντιγραφή από ημερομηνία…");
        m.add(0, 3, 2, "Μηδενισμός ✓ / ✗");
        m.add(0, 4, 3, "Καθαρισμός ημέρας");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        copyFrom(date.minusDays(1));
                        return true;
                    case 2:
                        pickDate(true);
                        return true;
                    case 3:
                        for (int k = 0; k < DayStore.SLOTS; k++) day.status[k] = DayStore.NONE;
                        store.save(date, day);
                        render();
                        return true;
                    case 4:
                        confirm("Να σβηστεί όλο το πρόγραμμα αυτής της μέρας;", new Runnable() {
                            @Override
                            public void run() {
                                day = new DayStore.Day();
                                store.save(date, day);
                                render();
                            }
                        });
                        return true;
                }
                return false;
            }
        });
        pm.show();
    }

    private void pickDate(final boolean forCopy) {
        DatePickerDialog dlg = new DatePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert,
                new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int y, int m, int d) {
                        LocalDate picked = LocalDate.of(y, m + 1, d);
                        if (forCopy) copyFrom(picked);
                        else showDate(picked, true);
                    }
                }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        dlg.show();
    }

    private void copyFrom(final LocalDate src) {
        final DayStore.Day from = store.load(src);
        if (from.isEmpty()) {
            toast("Η μέρα " + src.format(DateTimeFormatter.ofPattern("d/M", GREEK)) + " είναι άδεια");
            return;
        }
        Runnable doCopy = new Runnable() {
            @Override
            public void run() {
                day = new DayStore.Day();
                System.arraycopy(from.text, 0, day.text, 0, DayStore.SLOTS);
                store.save(date, day);
                render();
                toast("Αντιγράφηκε");
            }
        };
        if (day.isEmpty()) doCopy.run();
        else confirm("Το τωρινό πρόγραμμα θα αντικατασταθεί. Συνέχεια;", doCopy);
    }

    private void confirm(String msg, final Runnable onYes) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(msg)
                .setNegativeButton("Όχι", null)
                .setPositiveButton("Ναι", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onYes.run();
                    }
                })
                .show();
        styleButtons(d);
    }

    /** Accent-coloured, mixed-case buttons (Greek all-caps would otherwise keep the accents). */
    private void styleButtons(AlertDialog d) {
        int[] ids = {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL};
        for (int id : ids) {
            android.widget.Button b = d.getButton(id);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextColor(id == DialogInterface.BUTTON_NEUTRAL ? RED : id == DialogInterface.BUTTON_POSITIVE ? ACCENT : DIM);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        }
    }

    // ---------------------------------------------------------------- helpers

    private int blockStart(int i) {
        while (i > 0 && day.text[i].equals(day.text[i - 1])) i--;
        return i;
    }

    private int blockLen(int start) {
        int n = 1;
        while (start + n < DayStore.SLOTS && day.text[start].equals(day.text[start + n])) n++;
        return n;
    }

    /** Index of the slot containing the current time, or -1 if not viewing today / outside hours. */
    private int nowSlot() {
        if (!date.equals(LocalDate.now())) return -1;
        LocalTime t = LocalTime.now();
        int m = t.getHour() * 60 + t.getMinute() - DayStore.START_MIN;
        if (m < 0) return -1;
        int i = m / DayStore.SLOT_MIN;
        return i < DayStore.SLOTS ? i : -1;
    }

    private static int slotStart(int i) {
        return DayStore.START_MIN + i * DayStore.SLOT_MIN;
    }

    private static String formatMin(int m) {
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }

    private static String formatDuration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) return m + " λεπτά";
        if (m == 0) return h + (h == 1 ? " ώρα" : " ώρες");
        return h + "ω " + m + "λ";
    }

    private static String formatDurationShort(int minutes) {
        if (minutes < 60) return minutes + "'";
        if (minutes % 60 == 0) return (minutes / 60) + "ω";
        return (minutes / 60) + "½ω";
    }

    private static String relativeLabel(LocalDate d, LocalDate today) {
        long diff = ChronoUnit.DAYS.between(today, d);
        if (diff == 0) return "Σήμερα";
        if (diff == 1) return "Αύριο";
        if (diff == -1) return "Χθες";
        if (diff > 0) return "σε " + diff + " μέρες";
        return "πριν " + (-diff) + " μέρες";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(GREEK) + s.substring(1);
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    private TextView iconButton(String label) {
        TextView tv = text(26, TEXT, false);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(ripple(0, dp(24)));
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return tv;
    }

    private TextView chip(String label) {
        TextView tv = text(14, TEXT, true);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(200));
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        return tv;
    }

    private void styleChip(TextView c, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        if (selected) {
            g.setColor(ACCENT);
            c.setTextColor(0xFF000000);
        } else {
            g.setColor(0xFF26262C);
            g.setStroke(dp(1), 0xFF3A3A42);
            c.setTextColor(TEXT);
        }
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
    }

    private Drawable ripple(int color, int radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(color);
        content.setCornerRadius(radius);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), color == 0 ? new ColorDrawable(0) : content, mask);
    }

    private HorizontalScrollView hscroll(View child) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(child);
        return h;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
