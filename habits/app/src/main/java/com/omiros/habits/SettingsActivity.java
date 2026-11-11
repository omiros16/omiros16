package com.omiros.habits;

import static com.omiros.habits.Theme.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** The main switch, the lock set-up, the app lists, friends and the Sunday review. */
public class SettingsActivity extends Activity {
    private static final int REQ_SMS = 2;
    private LinearLayout content;
    private Rules rules;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rules = Rules.get(this);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(12), dp(22), dp(40));
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Permissions may have changed in the system settings meanwhile.
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    private void render() {
        content.removeAllViews();
        Rules.Doc now = rules.on(today());
        Rules.Doc want = rules.wanted();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setImageTintList(ColorStateList.valueOf(TEXT));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Πίσω");
        back.setBackground(Ui.withRipple(this, null, dp(22)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = Ui.text(this, 26, TEXT, true);
        title.setText("Ρυθμίσεις");
        title.setPadding(dp(4), 0, 0, 0);
        top.addView(title);
        content.addView(top);

        // ------------------------------------------------------------ pressure
        content.addView(Ui.label(this, "Πίεση"));
        String state = now.on ? (want.on ? "Ειδοποιήσεις και κλειδώματα σύμφωνα με κάθε συνήθεια."
                : "Κλείνουν από αύριο. Σήμερα ισχύουν ακόμα.") : "Σκέτο tracker: χωρίς ειδοποιήσεις και κλειδώματα.";
        content.addView(switchRow("Ειδοποιήσεις και κλειδώματα", state, want.on, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Rules.Doc w = rules.wanted().copy();
                w.on = on;
                edit(w);
            }
        }));

        // ------------------------------------------------------------ lock
        content.addView(Ui.label(this, "Κλείδωμα"));
        boolean lockOn = LockService.isOn(this);
        content.addView(line(lockOn ? "Ενεργό στο κινητό." : "Δεν έχει ενεργοποιηθεί στο κινητό ακόμα.", lockOn ? ACCENT : WARN));
        if (!lockOn) {
            content.addView(action("Ενεργοποίηση κλειδώματος", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lockSetup();
                }
            }));
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            content.addView(line("Η εξοικονόμηση μπαταρίας μπορεί να καθυστερεί ειδοποιήσεις και κλειδώματα.", DIM));
            content.addView(action("Χωρίς περιορισμό μπαταρίας", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }
            }));
        }
        content.addView(row("Η λίστα μου", appsSummary(want.block, "Καμία εφαρμογή ακόμα"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApps(true);
            }
        }));
        content.addView(row("Εξαιρέσεις όταν κλειδώνουν όλες", appsSummary(want.allow, "Καμία"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApps(false);
            }
        }));
        MonthStore.Month m = MonthStore.get(this).load(YearMonth.from(today()));
        String monthAcc = Ui.monthAcc(today().getMonthValue());
        content.addView(line("Πάσα τον " + monthAcc + ": μένουν " + Engine.passesLeft(m) + " από " + Rules.PASSES_PER_MONTH
                + ". Ρεπό: μένουν " + Engine.restLeft(m) + " από " + Rules.REST_PER_MONTH + ".", DIM));

        // ------------------------------------------------------------ friends
        content.addView(Ui.label(this, "Φίλοι"));
        content.addView(line("Παίρνουν μήνυμα όταν χρησιμοποιείς πάσο, και τα μεσάνυχτα για όποια συνήθεια "
                + "με κλείδωμα δεν έγινε.", DIM));
        content.addView(row("Τηλέφωνα για SMS", want.phones.isEmpty() ? "Κανένα" : join(want.phones), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editList(true);
            }
        }));
        if (!want.phones.isEmpty() && !Friends.canSms(this)) {
            content.addView(action("Επίτρεψε την αποστολή SMS", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
                }
            }));
        }
        content.addView(row("Email φίλων", want.emails.isEmpty() ? "Κανένα" : join(want.emails), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editList(false);
            }
        }));
        String gmail = Friends.secret(this).getString("gmail", "");
        content.addView(row("Αποστολή email από", gmail.isEmpty() ? "Δεν έχει ρυθμιστεί" : gmail, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editGmail();
            }
        }));
        if (now.hasFriends()) {
            content.addView(action("Στείλε δοκιμαστικό μήνυμα", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Friends.send(SettingsActivity.this, rules.on(today()), "Habits: δοκιμή",
                            "Habits: Δοκιμή. Από εδώ θα μαθαίνεις όταν δεν κάνω τις συνήθειές μου.");
                    Toast.makeText(SettingsActivity.this, "Στάλθηκε", Toast.LENGTH_SHORT).show();
                }
            }));
        }

        // ------------------------------------------------------------ review
        content.addView(Ui.label(this, "Ανασκόπηση"));
        content.addView(switchRow("Ανασκόπηση Κυριακής", "Κάθε Κυριακή στις 21:00, η εβδομάδα σου συνήθεια προς συνήθεια.",
                want.review, new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean on) {
                        Rules.Doc w = rules.wanted().copy();
                        w.review = on;
                        edit(w);
                    }
                }));

        TextView rule = Ui.text(this, 12, MUTED, false);
        rule.setText("Ό,τι σε κάνει πιο αυστηρό ισχύει αμέσως. Ό,τι σε χαλαρώνει ισχύει από αύριο.");
        rule.setPadding(0, dp(28), 0, 0);
        content.addView(rule);
    }

    private void edit(Rules.Doc want) {
        boolean later = rules.edit(today(), want);
        Engine.changed(this);
        if (later) Toast.makeText(this, "Ισχύει από αύριο.", Toast.LENGTH_SHORT).show();
        render();
    }

    // ---------------------------------------------------------------- rows

    private View switchRow(String title, String sub, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, 16, TEXT, true);
        t.setText(title);
        col.addView(t);
        TextView s = Ui.text(this, 13, DIM, false);
        s.setText(sub);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, DIM}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{ACCENT_SOFT, FAINT}));
        sw.setOnCheckedChangeListener(l);
        sw.setContentDescription(title);
        row.addView(sw);
        return row;
    }

    private View row(String title, String sub, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(10), 0, dp(10));
        col.setBackground(Ui.withRipple(this, null, dp(10)));
        TextView t = Ui.text(this, 16, TEXT, true);
        t.setText(title);
        col.addView(t);
        TextView s = Ui.text(this, 13, DIM, false);
        s.setText(sub);
        col.addView(s);
        col.setOnClickListener(l);
        return col;
    }

    private TextView line(String s, int color) {
        TextView t = Ui.text(this, 14, color, false);
        t.setText(s);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(4), 0, dp(6));
        return t;
    }

    private View action(String label, boolean primary, View.OnClickListener l) {
        TextView b = Ui.button(this, label, primary, primary ? ACCENT : TEXT);
        b.setOnClickListener(l);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(6), 0, dp(6));
        wrap.addView(b);
        return wrap;
    }

    private static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) b.append(b.length() > 0 ? ", " : "").append(x);
        return b.toString();
    }

    // ---------------------------------------------------------------- lock set-up

    private void lockSetup() {
        AlertDialog d = Ui.dialog(this)
                .setTitle("Ενεργοποίηση κλειδώματος")
                .setMessage("Για να κλειδώνει εφαρμογές, το Habits χρειάζεται την άδεια Προσβασιμότητας. Βλέπει μόνο "
                        + "ποια εφαρμογή είναι μπροστά, όχι τι γράφει.\n\n"
                        + "1. Πάτα «Πληροφορίες εφαρμογής», μετά ⋮ πάνω δεξιά και «Να επιτρέπονται οι περιορισμένες "
                        + "ρυθμίσεις». Το Android το ζητά επειδή η εφαρμογή δεν ήρθε από το Play Store.\n"
                        + "2. Πάτα «Προσβασιμότητα», βρες το Habits και ενεργοποίησέ το.\n\n"
                        + "Οι κλήσεις, η αρχική οθόνη και οι Ρυθμίσεις δεν κλειδώνουν ποτέ, ώστε να μπορείς πάντα "
                        + "να το κλείσεις από εκεί.")
                .setNegativeButton("Άκυρο", null)
                .setNeutralButton("Πληροφορίες εφαρμογής", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setPositiveButton("Προσβασιμότητα", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                })
                .show();
        Ui.styleDialog(this, d);
        // The neutral button here leads on, it does not delete: not red.
        d.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(TEXT);
    }

    // ---------------------------------------------------------------- app lists

    /** Launchable apps by package, sorted by name. */
    private TreeMap<String, String> launchable() {
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        PackageManager pm = getPackageManager();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            byLabel.put(r.loadLabel(pm) + "\u0000" + pkg, pkg);
        }
        return byLabel;
    }

    private String appsSummary(Set<String> pkgs, String none) {
        if (pkgs.isEmpty()) return none;
        List<String> names = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String p : pkgs) {
            try {
                names.add(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString());
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (names.isEmpty()) return none;
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < names.size() && i < 4; i++) s.append(i > 0 ? ", " : "").append(names.get(i));
        if (names.size() > 4) s.append(" και ").append(names.size() - 4).append(" ακόμα");
        return s.toString();
    }

    private void pickApps(final boolean blockList) {
        TreeMap<String, String> apps = launchable();
        final String[] pkgs = new String[apps.size()];
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        Set<String> current = blockList ? rules.wanted().block : rules.wanted().allow;
        int i = 0;
        for (java.util.Map.Entry<String, String> e : apps.entrySet()) {
            pkgs[i] = e.getValue();
            labels[i] = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            checked[i] = current.contains(pkgs[i]);
            i++;
        }
        AlertDialog d = Ui.dialog(this)
                .setTitle(blockList ? "Η λίστα μου" : "Εξαιρέσεις")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Rules.Doc w = rules.wanted().copy();
                        TreeSet<String> set = blockList ? w.block : w.allow;
                        for (int k = 0; k < pkgs.length; k++) {
                            if (checked[k]) set.add(pkgs[k]);
                            else set.remove(pkgs[k]);
                        }
                        edit(w);
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }

    // ---------------------------------------------------------------- friends

    private void editList(final boolean phones) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        List<String> now = new ArrayList<>(phones ? rules.wanted().phones : rules.wanted().emails);
        final EditText[] fields = new EditText[Rules.MAX_FRIENDS];
        for (int i = 0; i < fields.length; i++) {
            EditText e = field(phones ? "Τηλέφωνο " + (i + 1) : "Email " + (i + 1),
                    phones ? InputType.TYPE_CLASS_PHONE : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            if (i < now.size()) e.setText(now.get(i));
            fields[i] = e;
            box.addView(e, Ui.matchWrap());
        }
        AlertDialog d = Ui.dialog(this)
                .setTitle(phones ? "Τηλέφωνα για SMS" : "Email φίλων")
                .setView(box)
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Rules.Doc w = rules.wanted().copy();
                        TreeSet<String> set = phones ? w.phones : w.emails;
                        set.clear();
                        for (EditText e : fields) {
                            String v = e.getText().toString().replace(" ", "");
                            if (phones ? v.replaceAll("[^0-9]", "").length() >= 5 : v.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                                set.add(v);
                            }
                        }
                        edit(w);
                        if (phones && !set.isEmpty() && !Friends.canSms(SettingsActivity.this)) {
                            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
                        }
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }

    private void editGmail() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        TextView help = Ui.text(this, 13, DIM, false);
        help.setLineSpacing(0, 1.25f);
        help.setText("Τα email φεύγουν από δικό σου λογαριασμό Gmail. Χρειάζεται «κωδικός εφαρμογής», όχι ο "
                + "κανονικός σου κωδικός: Λογαριασμός Google, Ασφάλεια, Επαλήθευση σε 2 βήματα, Κωδικοί εφαρμογών. "
                + "Μένει αποθηκευμένος μόνο σε αυτό το κινητό και δεν μπαίνει στα αντίγραφα ασφαλείας.");
        box.addView(help);
        final EditText user = field("Διεύθυνση Gmail", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        user.setText(Friends.secret(this).getString("gmail", ""));
        box.addView(user, Ui.matchWrap());
        final EditText pass = field("Κωδικός εφαρμογής (16 γράμματα)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass, Ui.matchWrap());
        AlertDialog d = Ui.dialog(this)
                .setTitle("Αποστολή email από")
                .setView(box)
                .setNegativeButton("Άκυρο", null)
                .setNeutralButton("Αφαίρεση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Friends.secret(SettingsActivity.this).edit().clear().commit();
                        render();
                    }
                })
                .setPositiveButton("Αποθήκευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String u = user.getText().toString().trim();
                        String p = pass.getText().toString().replace(" ", "");
                        android.content.SharedPreferences.Editor e = Friends.secret(SettingsActivity.this).edit().putString("gmail", u);
                        if (!p.isEmpty()) e.putString("password", p);
                        e.commit();
                        Friends.retry(SettingsActivity.this);
                        render();
                    }
                })
                .show();
        Ui.styleDialog(this, d);
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        return e;
    }
}
