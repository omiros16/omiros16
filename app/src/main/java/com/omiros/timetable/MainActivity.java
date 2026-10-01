package com.omiros.timetable;

import static com.omiros.timetable.Theme.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
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
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
    private static final int[] QUICK_DURATIONS = {15, 30, 45, 60, 90, 120, 180};
    private static final int REQ_NOTIF = 1;

    private static final Locale GREEK = new Locale("el", "GR");
    private static final DateTimeFormatter TITLE_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM", GREEK);
    private static final DateTimeFormatter SHORT_FMT = DateTimeFormatter.ofPattern("EEE d/M", GREEK);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            int now = nowSlot();
            LocalDate today = LocalDate.now();
            if (now != lastNowSlot || !today.equals(lastToday)) render();
            else positionNowLine();
            handler.postDelayed(this, 30_000);
        }
    };

    private DayStore store;
    private int lastNowSlot = -2;
    private LocalDate lastToday;
    private LocalDate date;
    private DayStore.Day day;

    private TextView titleView;
    private TextView subtitleView;
    private ImageView bell;
    private TextView todayChip;
    private TextView tomorrowChip;
    private TextView progressText;
    private View barDone;
    private View barMissed;
    private View barRest;
    private TextView banner;
    private ScrollView scroll;
    private View nowLine;
    private final View[] rows = new View[DayStore.SLOTS];
    private final TextView[] timeViews = new TextView[DayStore.SLOTS];
    private final TextView[] boxViews = new TextView[DayStore.SLOTS];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new DayStore(this);
        String saved = savedInstanceState == null ? null : savedInstanceState.getString("date");
        date = saved != null ? LocalDate.parse(saved) : LocalDate.now();
        setContentView(buildUi());
        showDate(date, true);
        if (savedInstanceState == null && store.notificationsOn() && !hasNotificationPermission()) {
            requestNotificationPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
        Reminders.reschedule(this);
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
        header.setPadding(dp(16), dp(12), dp(8), dp(14));
        root.addView(header);

        // Row 1: date title + notification bell + menu
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(top);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setBackground(ripple(0, dp(10)));
        titleCol.setPadding(0, dp(2), dp(8), dp(2));
        subtitleView = text(13, ACCENT_SOFT, true);
        titleView = text(22, TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(subtitleView);
        titleCol.addView(titleView);
        titleCol.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate(false);
            }
        });
        top.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        bell = iconButton(R.drawable.ic_bell);
        bell.setContentDescription("Ειδοποιήσεις");
        bell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleNotifications();
            }
        });
        top.addView(bell);

        final ImageView menu = iconButton(R.drawable.ic_more);
        menu.setContentDescription("Μενού");
        menu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(menu);
            }
        });
        top.addView(menu);

        // Row 2: quick day chips + prev/next
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(12), 0, 0);
        header.addView(nav);

        todayChip = chip("Σήμερα");
        todayChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(LocalDate.now(), true);
            }
        });
        nav.addView(todayChip);

        tomorrowChip = chip("Αύριο");
        tomorrowChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(LocalDate.now().plusDays(1), true);
            }
        });
        LinearLayout.LayoutParams chipLp = wrap();
        chipLp.leftMargin = dp(8);
        nav.addView(tomorrowChip, chipLp);

        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        ImageView prev = iconButton(R.drawable.ic_chevron_left);
        prev.setContentDescription("Προηγούμενη μέρα");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(date.minusDays(1), true);
            }
        });
        nav.addView(prev);

        ImageView next = iconButton(R.drawable.ic_chevron_right);
        next.setContentDescription("Επόμενη μέρα");
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(date.plusDays(1), true);
            }
        });
        nav.addView(next);

        // Row 3: progress
        progressText = text(12, DIM, false);
        progressText.setPadding(0, dp(12), 0, dp(6));
        header.addView(progressText);

        LinearLayout bar = new LinearLayout(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(FAINT);
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        bar.setClipToOutline(true);
        barDone = new View(this);
        barDone.setBackgroundColor(GREEN);
        barMissed = new View(this);
        barMissed.setBackgroundColor(RED);
        barRest = new View(this);
        bar.addView(barDone, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0));
        bar.addView(barMissed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0));
        bar.addView(barRest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        barLp.rightMargin = dp(8);
        header.addView(bar, barLp);

        View divider = new View(this);
        divider.setBackgroundColor(0xFF1C2028);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        banner = text(14, ACCENT_SOFT, false);
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        banner.setBackground(ripple(ACCENT_BG, dp(12)));
        banner.setText("Δεν έχεις γράψει ακόμα το αύριο. Πάτα εδώ για να το σχεδιάσεις →");
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDate(LocalDate.now().plusDays(1), true);
            }
        });
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(banner, bannerLp);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(16), dp(24));
        for (int i = 0; i < DayStore.SLOTS; i++) {
            list.addView(buildRow(i));
        }
        TextView hint = text(12, DIM, false);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.3f);
        hint.setPadding(dp(16), dp(20), dp(16), 0);
        hint.setText("Πάτα ένα κουτί για να γράψεις τι θα κάνεις.\n"
                + "Κράτα πατημένο ένα γεμάτο κουτί για νέα δραστηριότητα από εκείνη την ώρα.\n"
                + "Πάτα ένα γεμάτο κουτί για να το σημειώσεις ✓ έγινε ή ✕ δεν έγινε.");
        list.addView(hint);
        // The list plus a "now" line drawn over it at the current minute.
        android.widget.FrameLayout canvas = new android.widget.FrameLayout(this);
        canvas.addView(list);
        nowLine = buildNowLine();
        android.widget.FrameLayout.LayoutParams nlp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        nlp.leftMargin = dp(12 + 54 - 5);
        nlp.rightMargin = dp(16);
        canvas.addView(nowLine, nlp);
        scroll.addView(canvas);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private View buildNowLine() {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        View d = new View(this);
        d.setBackground(dot);
        line.addView(d, new LinearLayout.LayoutParams(dp(10), dp(10)));
        View bar = new View(this);
        bar.setBackgroundColor(ACCENT);
        line.addView(bar, new LinearLayout.LayoutParams(0, dp(2), 1));
        line.setVisibility(View.GONE);
        return line;
    }

    /** Places the now line at the current minute; rows are a fixed 44dp below an 8dp list padding. */
    private void positionNowLine() {
        if (nowSlot() < 0) {
            nowLine.setVisibility(View.GONE);
            return;
        }
        LocalTime t = LocalTime.now();
        float minutes = t.getHour() * 60 + t.getMinute() + t.getSecond() / 60f - DayStore.START_MIN;
        float y = dp(8) + minutes / DayStore.SLOT_MIN * dp(44);
        nowLine.setTranslationY(y - dp(5));
        nowLine.setVisibility(View.VISIBLE);
    }

    private View buildRow(final int i) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        boolean hour = i % 4 == 0;
        TextView time = text(hour ? 14 : 11, hour ? TEXT : DIM, hour);
        time.setText(DayStore.formatMin(DayStore.slotStart(i)));
        time.setFontFeatureSettings("tnum");
        row.addView(time, new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView box = text(15, TEXT, false);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), 0, dp(12), 0);
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
                openEditor(i, 1, false);
                return true;
            }
        });
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));


        rows[i] = row;
        timeViews[i] = time;
        boxViews[i] = box;
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
        lastToday = today;
        lastNowSlot = nowSlot();
        titleView.setText(capitalize(date.format(TITLE_FMT)));
        subtitleView.setText(relativeLabel(date, today));
        styleChip(todayChip, date.equals(today));
        styleChip(tomorrowChip, date.equals(today.plusDays(1)));

        boolean notif = store.notificationsOn();
        bell.setImageResource(notif ? R.drawable.ic_bell : R.drawable.ic_bell_off);
        bell.setImageTintList(ColorStateList.valueOf(notif ? ACCENT : DIM));

        boolean tomorrowEmpty = date.equals(today) && !store.hasPlan(today.plusDays(1));
        banner.setVisibility(tomorrowEmpty && LocalTime.now().getHour() >= 18 ? View.VISIBLE : View.GONE);

        int now = nowSlot();
        boolean past = date.isBefore(today);
        int planned = 0;
        int done = 0;
        int missed = 0;
        float r = dp(8);
        // The block (or empty slot) the current time falls in gets a slightly lighter fill.
        int activeFrom = -1;
        int activeTo = -1;
        if (now >= 0) {
            activeFrom = day.text[now] == null ? now : blockStart(now);
            activeTo = day.text[now] == null ? now : activeFrom + blockLen(activeFrom) - 1;
        }

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
            boolean active = i >= activeFrom && i <= activeTo;
            if (!filled) color = active ? EMPTY_NOW : EMPTY_BOX;
            else if (st == DayStore.DONE) color = DONE_BOX;
            else if (st == DayStore.MISSED) color = MISSED_BOX;
            else color = active ? BLOCK_NOW : BLOCK;
            bg.setColor(color);
            float top = joinPrev ? 0 : r;
            float bottom = joinNext ? 0 : r;
            bg.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});

            TextView box = boxViews[i];
            box.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
            box.setText(filled && !joinPrev ? t : "");
            box.setTextColor(st == DayStore.MISSED ? DIM : TEXT);
            box.setPaintFlags(st == DayStore.MISSED && filled
                    ? box.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : box.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
            lp.topMargin = joinPrev ? 0 : dp(2);
            lp.bottomMargin = joinNext ? 0 : dp(2);
            box.setLayoutParams(lp);

            TextView tv = timeViews[i];
            boolean hour = i % 4 == 0;
            tv.setTextColor(i == now ? ACCENT : hour ? TEXT : DIM);
            tv.setAlpha(past || (now >= 0 && i < now) ? 0.4f : 1f);
        }

        if (planned == 0) {
            progressText.setText("Κενή μέρα · πάτα ένα κουτί για να ξεκινήσεις");
        } else {
            String s = "Ολοκληρώθηκαν " + formatDuration(done * DayStore.SLOT_MIN)
                    + " από " + formatDuration(planned * DayStore.SLOT_MIN);
            if (missed > 0) s += " · χάθηκαν " + formatDuration(missed * DayStore.SLOT_MIN);
            progressText.setText(s);
        }
        setWeight(barDone, done);
        setWeight(barMissed, missed);
        setWeight(barRest, planned == 0 ? 1 : planned - done - missed);
        positionNowLine();
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

    private void toggleNotifications() {
        boolean on = !store.notificationsOn();
        store.setNotificationsOn(on);
        Reminders.reschedule(this);
        render();
        toast(on ? "Ειδοποιήσεις ενεργές" : "Ειδοποιήσεις απενεργοποιημένες");
        if (on && !hasNotificationPermission()) requestNotificationPermission();
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIF && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Για ειδοποιήσεις, δώσε άδεια από Ρυθμίσεις → Εφαρμογές → Timetable");
        }
    }

    private void openEditor(final int start, int initialLen, final boolean existing) {
        final int origLen = existing ? initialLen : 0;
        final int maxLen = DayStore.SLOTS - start;
        final int[] len = {initialLen};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(4));

        final TextView range = text(20, TEXT, true);
        range.setFontFeatureSettings("tnum");
        content.addView(range);

        // Accountability: mark an existing block as done / not done (saves and closes right away).
        final AlertDialog[] holder = new AlertDialog[1];
        final EditText input = new EditText(this);
        if (existing) {
            LinearLayout statusRow = new LinearLayout(this);
            statusRow.setPadding(0, dp(14), 0, dp(2));
            final int current = day.status[start];
            int[] values = {DayStore.DONE, DayStore.MISSED};
            String[] labels = {"✓  Έγινε", "✕  Δεν έγινε"};
            for (int k = 0; k < 2; k++) {
                final int value = values[k];
                TextView c = chip(labels[k]);
                styleStatusChip(c, value, current == value);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String t = input.getText().toString().trim();
                        applyEdit(start, origLen, len[0], t.isEmpty() ? day.text[start] : t,
                                current == value ? DayStore.NONE : value);
                        holder[0].dismiss();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (k == 0) lp.rightMargin = dp(8);
                c.setGravity(Gravity.CENTER);
                c.setPadding(dp(14), dp(10), dp(14), dp(10));
                statusRow.addView(c, lp);
            }
            content.addView(statusRow);
        }

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
        inLp.topMargin = dp(10);
        content.addView(input, inLp);

        TextView durTitle = label("Διάρκεια");
        content.addView(durTitle);

        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.setPadding(0, 0, 0, dp(10));
        TextView minusText = stepperButton("−");
        final TextView durLabel = text(16, TEXT, true);
        durLabel.setGravity(Gravity.CENTER);
        TextView plusText = stepperButton("+");
        stepper.addView(minusText);
        stepper.addView(durLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stepper.addView(plusText);
        content.addView(stepper);

        final LinearLayout quick = new LinearLayout(this);
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                range.setText(DayStore.formatMin(DayStore.slotStart(start)) + " – "
                        + DayStore.formatMin(DayStore.slotStart(start + len[0])));
                durLabel.setText(formatDuration(len[0] * DayStore.SLOT_MIN));
                for (int k = 0; k < quick.getChildCount(); k++) {
                    styleChip((TextView) quick.getChildAt(k), QUICK_DURATIONS[k] == len[0] * DayStore.SLOT_MIN);
                }
            }
        };
        minusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (len[0] > 1) len[0]--;
                refresh.run();
            }
        });
        plusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (len[0] < maxLen) len[0]++;
                refresh.run();
            }
        });

        for (final int minutes : QUICK_DURATIONS) {
            TextView c = chip(formatDurationShort(minutes));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    len[0] = Math.min(maxLen, minutes / DayStore.SLOT_MIN);
                    refresh.run();
                }
            });
            LinearLayout.LayoutParams lp = wrap();
            lp.rightMargin = dp(6);
            quick.addView(c, lp);
        }
        content.addView(hscroll(quick));

        List<String> recent = store.recent();
        if (!recent.isEmpty()) {
            content.addView(label("Πρόσφατα"));
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
                LinearLayout.LayoutParams lp = wrap();
                lp.rightMargin = dp(6);
                recentRow.addView(c, lp);
            }
            content.addView(hscroll(recentRow));
        }
        refresh.run();

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(content);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setView(wrapper)
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String t = input.getText().toString().trim();
                        if (t.isEmpty()) {
                            if (existing) clearRange(start, origLen);
                        } else {
                            applyEdit(start, origLen, len[0], t, -1);
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
        holder[0] = dialog;
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

    /** @param status new status for the block, or -1 to keep it when the text is unchanged */
    private void applyEdit(int start, int origLen, int len, String t, int status) {
        String oldText = origLen > 0 ? day.text[start] : null;
        int oldStatus = origLen > 0 ? day.status[start] : DayStore.NONE;
        for (int k = start; k < start + origLen; k++) {
            day.text[k] = null;
            day.status[k] = DayStore.NONE;
        }
        int keep = status >= 0 ? status : t.equals(oldText) ? oldStatus : DayStore.NONE;
        for (int k = start; k < Math.min(DayStore.SLOTS, start + len); k++) {
            day.text[k] = t;
            day.status[k] = keep;
        }
        store.addRecent(t);
        persist();
    }

    private void clearRange(int start, int len) {
        for (int k = start; k < start + len; k++) {
            day.text[k] = null;
            day.status[k] = DayStore.NONE;
        }
        persist();
    }

    /** Saves the shown day, re-arms the next reminder and redraws. */
    private void persist() {
        store.save(date, day);
        Reminders.reschedule(this);
        render();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        final Menu m = pm.getMenu();
        m.add(0, 1, 0, "Αντιγραφή από προηγούμενη μέρα");
        m.add(0, 2, 1, "Αντιγραφή από ημερομηνία…");
        m.add(0, 3, 2, "Μηδενισμός ✓ / ✕");
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
                        persist();
                        return true;
                    case 4:
                        confirm("Να σβηστεί όλο το πρόγραμμα αυτής της μέρας;", new Runnable() {
                            @Override
                            public void run() {
                                day = new DayStore.Day();
                                persist();
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
        DatePickerDialog dlg = new DatePickerDialog(this, R.style.DialogTheme,
                new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int y, int m, int d) {
                        LocalDate picked = LocalDate.of(y, m + 1, d);
                        if (forCopy) copyFrom(picked);
                        else showDate(picked, true);
                    }
                }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        dlg.show();
        styleButtons(dlg);
    }

    private void copyFrom(final LocalDate src) {
        final DayStore.Day from = store.load(src);
        if (from.isEmpty()) {
            toast("Η μέρα " + src.format(SHORT_FMT) + " είναι άδεια");
            return;
        }
        Runnable doCopy = new Runnable() {
            @Override
            public void run() {
                day = new DayStore.Day();
                System.arraycopy(from.text, 0, day.text, 0, DayStore.SLOTS);
                persist();
                toast("Αντιγράφηκε");
            }
        };
        if (day.isEmpty()) doCopy.run();
        else confirm("Το τωρινό πρόγραμμα θα αντικατασταθεί. Συνέχεια;", doCopy);
    }

    private void confirm(String msg, final Runnable onYes) {
        AlertDialog d = new AlertDialog.Builder(this, R.style.DialogTheme)
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

    /** Mixed-case buttons (Greek all-caps would otherwise keep the accents). */
    private void styleButtons(AlertDialog d) {
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
        if (diff > 0) return "Σε " + diff + " μέρες";
        return "Πριν " + (-diff) + " μέρες";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(GREEK) + s.substring(1);
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    private TextView label(String s) {
        TextView tv = text(12, DIM, false);
        tv.setText(s);
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private ImageView iconButton(int res) {
        ImageView iv = new ImageView(this);
        if (res != 0) iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(TEXT));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setBackground(ripple(0, dp(22)));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return iv;
    }

    private TextView stepperButton(String label) {
        TextView tv = text(22, TEXT, false);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(ripple(0xFF232833, dp(22)));
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return tv;
    }

    private TextView chip(String label) {
        TextView tv = text(14, TEXT, true);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(200));
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        styleChip(tv, false);
        return tv;
    }

    private void styleChip(TextView c, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        if (selected) {
            g.setColor(ACCENT_BG);
            g.setStroke(dp(1), ACCENT);
            c.setTextColor(ACCENT_SOFT);
        } else {
            g.setColor(0xFF1A1E26);
            g.setStroke(dp(1), FAINT);
            c.setTextColor(TEXT);
        }
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
    }

    private void styleStatusChip(TextView c, int value, boolean selected) {
        int color = value == DayStore.DONE ? GREEN : RED;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        if (selected) {
            g.setColor(value == DayStore.DONE ? DONE_BOX : MISSED_BOX);
            g.setStroke(dp(1), color);
            c.setTextColor(color);
        } else {
            g.setColor(0xFF1A1E26);
            g.setStroke(dp(1), FAINT);
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
