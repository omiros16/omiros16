package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single activity: a back stack of {@link Screen}s plus the export / import flows. */
public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1;
    private static final int REQ_IMPORT = 2;

    Db db;
    Ui ui;
    private final ArrayList<Screen> stack = new ArrayList<>();
    private FrameLayout host;
    private boolean resumedOnce;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new Db(this);
        ui = new Ui(this);
        host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        setContentView(host);
        ArrayList<String> keys = state == null ? null : state.getStringArrayList("screens");
        if (keys != null) {
            for (String k : keys) {
                Screen s = Screen.restore(this, k);
                if (s != null) stack.add(s);
            }
        }
        if (stack.isEmpty() || !(stack.get(0) instanceof HomeScreen)) stack.add(0, new HomeScreen(this, 0));
        show(top());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce) top().onResume();
        resumedOnce = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        top().onHide();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> keys = new ArrayList<>();
        for (Screen s : stack) keys.add(s.key());
        out.putStringArrayList("screens", keys);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (top().onBack()) return;
        if (stack.size() <= 1) {
            super.onBackPressed();
            return;
        }
        pop();
    }

    // ---------------------------------------------------------------- navigation

    Screen top() {
        return stack.get(stack.size() - 1);
    }

    void push(Screen s) {
        top().onHide();
        stack.add(s);
        show(s);
    }

    void pop() {
        if (stack.size() <= 1) return;
        stack.remove(stack.size() - 1).onHide();
        Screen t = top();
        show(t);
        t.refresh();
    }

    /** Back to a fresh home screen (after an import replaced the data under the open screens). */
    void restart() {
        top().onHide();
        stack.clear();
        stack.add(new HomeScreen(this, 0));
        show(top());
    }

    private void show(Screen s) {
        ui.hideKeyboard(host);
        host.removeAllViews();
        host.addView(s.view());
    }

    // ---------------------------------------------------------------- export / import

    void startExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "gym-notes-" + LocalDate.now() + ".json");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (ActivityNotFoundException e) {
            ui.toast("Δεν βρέθηκε εφαρμογή αρχείων");
        }
    }

    void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            ui.toast("Δεν βρέθηκε εφαρμογή αρχείων");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) exportTo(uri);
        else if (requestCode == REQ_IMPORT) importFrom(uri);
    }

    private void exportTo(final Uri uri) {
        background("Εξαγωγή…", new Job<int[]>() {
            @Override
            public int[] run() throws Exception {
                OutputStream out;
                try {
                    out = getContentResolver().openOutputStream(uri, "wt");
                } catch (FileNotFoundException | IllegalArgumentException | UnsupportedOperationException e) {
                    out = getContentResolver().openOutputStream(uri, "w");
                }
                if (out == null) throw new IOException("no stream");
                try {
                    return Backup.export(db, out);
                } finally {
                    out.close();
                }
            }
        }, new Done<int[]>() {
            @Override
            public void ok(int[] n) {
                ui.info("Έτοιμο", "Αποθηκεύτηκαν " + n[0] + " ασκήσεις και " + n[1]
                        + " καταγραφές προπόνησης.\n\nΚράτα το αρχείο κάπου ασφαλές (π.χ. Drive). "
                        + "Με «Insert your workout data» τα ξαναβάζεις σε αυτό ή σε άλλο κινητό.");
            }
        });
    }

    private void importFrom(final Uri uri) {
        background("Ανάγνωση αρχείου…", new Job<Backup.Data>() {
            @Override
            public Backup.Data run() throws Exception {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("no stream");
                try {
                    return Backup.read(in);
                } finally {
                    in.close();
                }
            }
        }, new Done<Backup.Data>() {
            @Override
            public void ok(Backup.Data d) {
                askImportMode(d);
            }
        });
    }

    private void askImportMode(final Backup.Data d) {
        String found = "Το αρχείο έχει " + d.exercises.size() + " ασκήσεις, " + d.presets.size()
                + " προγράμματα και " + d.sessions + " καταγραφές (" + d.sets + " sets).";
        if (db.isEmpty()) {
            applyImport(d, true);
            return;
        }
        AlertDialog.Builder b = ui.dialog().setTitle("Insert your workout data")
                .setMessage(found + "\n\n• Συγχώνευση: κρατάει τα τωρινά σου και προσθέτει όσα λείπουν "
                        + "(ίδιες μέρες αντικαθίστανται από το αρχείο).\n"
                        + "• Αντικατάσταση: σβήνει τα τωρινά δεδομένα και βάζει μόνο του αρχείου.")
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Συγχώνευση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyImport(d, false);
                    }
                })
                .setNeutralButton("Αντικατάσταση", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ui.confirm("Αντικατάσταση όλων;", "Τα τωρινά προγράμματα, ασκήσεις και το ιστορικό "
                                + "θα σβηστούν και θα μπουν μόνο όσα έχει το αρχείο.", "Αντικατάσταση", true,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        applyImport(d, true);
                                    }
                                });
                    }
                });
        ui.show(b, false);
    }

    private void applyImport(final Backup.Data d, final boolean replace) {
        background("Εισαγωγή…", new Job<Db.ImportResult>() {
            @Override
            public Db.ImportResult run() {
                return db.importData(d, replace);
            }
        }, new Done<Db.ImportResult>() {
            @Override
            public void ok(Db.ImportResult r) {
                restart();
                String msg = "Μπήκαν " + r.exercises + " ασκήσεις, " + r.presets + " προγράμματα και "
                        + r.sessions + " καταγραφές.";
                if (r.presetsSkipped > 0) {
                    msg += "\n\n" + r.presetsSkipped + " προγράμματα δεν χώρεσαν (όριο " + Db.MAX_PRESETS
                            + "). Οι ασκήσεις και το ιστορικό τους μπήκαν κανονικά.";
                }
                ui.info("Έτοιμο", msg);
            }
        });
    }

    // ---------------------------------------------------------------- background work

    interface Job<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void ok(T result);
    }

    /** Runs {@code job} off the main thread behind a small progress dialog. */
    private <T> void background(String message, final Job<T> job, final Done<T> done) {
        LinearLayout box = ui.row();
        box.setPadding(ui.dp(24), ui.dp(22), ui.dp(24), ui.dp(22));
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        box.addView(bar, ui.size(36, 36));
        TextView t = ui.text(message, 16, TEXT, false);
        t.setPadding(ui.dp(18), 0, 0, 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(t);
        final AlertDialog progress = ui.dialog().setView(box).setCancelable(false).show();
        io.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Exception error = null;
                try {
                    result = job.run();
                } catch (Exception e) {
                    error = e;
                }
                final T r = result;
                final Exception err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        progress.dismiss();
                        if (err == null) {
                            done.ok(r);
                        } else if (err instanceof Backup.BadFile || err instanceof IOException) {
                            ui.info("Δεν έγινε", err instanceof Backup.BadFile
                                    ? "Το αρχείο δεν είναι αντίγραφο του Gym Notes (ή είναι κατεστραμμένο)."
                                    : "Δεν ήταν δυνατή η πρόσβαση στο αρχείο.\n\n" + err.getMessage());
                        } else {
                            ui.info("Σφάλμα", String.valueOf(err));
                        }
                    }
                });
            }
        });
    }
}
