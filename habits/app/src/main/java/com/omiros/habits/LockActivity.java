package com.omiros.habits;

import static com.omiros.habits.Theme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** Goes over a locked app: do the habit (or start its timer), spend a pass, or go home. */
public class LockActivity extends Activity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 28);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goHome();
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void render() {
        LocalDateTime now = LocalDateTime.now();
        Engine.Lock lock = Engine.lockAt(this, now);
        if (lock == null) {
            finish();
            return;
        }
        content.removeAllViews();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(RED));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        content.addView(icon, ilp);

        TextView title = Ui.text(this, 34, TEXT, false);
        title.setTypeface(Ui.CONDENSED);
        title.setText("Κλειδωμένο");
        title.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 20));
        content.addView(title);

        Rules.Doc doc = Rules.get(this).on(now.toLocalDate());
        for (final MonthStore.Habit h : lock.habits) {
            final Rules.Rule r = doc.rule(h.id);
            TextView name = Ui.text(this, 20, TEXT, true);
            name.setText(h.name);
            content.addView(name);
            TextView why = Ui.text(this, 14, DIM, false);
            why.setText("Έπρεπε να γίνει ως τις " + Ui.time(r.minute) + ".");
            why.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
            content.addView(why);
            LinearLayout row = new LinearLayout(this);
            TextView done = Ui.button(this, "Έγινε", true, ACCENT);
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Engine.setDone(LockActivity.this, h.id, LocalDate.now(), true);
                    render();
                }
            });
            row.addView(done);
            if (r.timer > 0) {
                TextView timer = Ui.button(this, "Χρονόμετρο " + r.timer + "'", false, TEXT);
                timer.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Alarms.startTimer(LockActivity.this, h.id);
                        Toast.makeText(LockActivity.this, "Ξεκλειδώνει μόλις τελειώσει το χρονόμετρο", Toast.LENGTH_LONG).show();
                    }
                });
                LinearLayout.LayoutParams lp = Ui.wrap();
                lp.leftMargin = Ui.dp(this, 10);
                row.addView(timer, lp);
            }
            LinearLayout.LayoutParams rlp = Ui.matchWrap();
            rlp.bottomMargin = Ui.dp(this, 24);
            content.addView(row, rlp);
        }
        for (MonthStore.Habit h : lock.avoids) {
            TextView name = Ui.text(this, 20, TEXT, true);
            name.setText(h.name);
            content.addView(name);
            TextView why = Ui.text(this, 14, DIM, false);
            why.setText("Η λίστα σου είναι μπλοκαρισμένη ως τις " + Ui.time(Math.min(lock.until, 24 * 60 - 1)) + ".");
            why.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 24));
            content.addView(why);
        }

        final MonthStore.Month m = MonthStore.get(this).load(YearMonth.from(now));
        int left = Engine.passesLeft(m);
        TextView pass = Ui.button(this, left > 0 ? "Πάσο ως τα μεσάνυχτα (μένουν " + left + ")" : "Δεν έμειναν πάσα",
                false, left > 0 ? RED : MUTED);
        pass.setEnabled(left > 0);
        pass.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPass();
            }
        });
        content.addView(pass, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView home = Ui.button(this, "Αρχική οθόνη", false, DIM);
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        });
        LinearLayout.LayoutParams hlp = Ui.matchWrap();
        hlp.topMargin = Ui.dp(this, 10);
        content.addView(home, hlp);
    }

    private void confirmPass() {
        Rules.Doc doc = Rules.get(this).on(LocalDate.now());
        String msg = "Όλα ξεκλειδώνουν μέχρι τα μεσάνυχτα και η μέρα μετράει ως «δεν έγινε»."
                + (doc.hasFriends() ? " Οι φίλοι σου θα ενημερωθούν." : "");
        AlertDialog d = Ui.dialog(this)
                .setTitle("Να χρησιμοποιηθεί πάσο;")
                .setMessage(msg)
                .setNegativeButton("Άκυρο", null)
                .setNeutralButton("Πάσο", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Engine.usePass(LockActivity.this)) finish();
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }
}
