package com.omiros.gymnotes;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPopupMenu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The app driven through its real Activity: the whole flow of a workout, data shared between
 * programs, export / import, upgrades from older databases and speed with two years of logs.
 * With {@code -Dshots=<folder>} the flow also writes screenshots there (the ones in gym/docs).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h890dp-xxhdpi")
public class GymUiTest {
    static final String SHOTS = System.getProperty("shots");
    MainActivity a;

    // ---------------------------------------------------------------- helpers

    static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    static List<View> all(View root, List<View> out) {
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), out);
        }
        return out;
    }

    static boolean shown(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /** The dialog on top, or the activity. */
    View root() {
        Dialog d = ShadowDialog.getLatestDialog();
        if (d != null && d.isShowing()) return d.getWindow().getDecorView();
        return a.getWindow().getDecorView();
    }

    View screen() {
        return a.getWindow().getDecorView();
    }

    static String dump(View root) {
        StringBuilder sb = new StringBuilder();
        for (View v : all(root, new ArrayList<View>())) {
            if (v instanceof TextView && shown(v)) sb.append("  [").append(((TextView) v).getText()).append("]\n");
        }
        return sb.toString();
    }

    /** A visible text view with exactly this text, else one containing it. */
    static TextView find(View root, String text) {
        for (int pass = 0; pass < 2; pass++) {
            for (View v : all(root, new ArrayList<View>())) {
                if (!(v instanceof TextView) || v instanceof EditText || !shown(v)) continue;
                String t = ((TextView) v).getText().toString();
                if (pass == 0 ? t.equals(text) : t.contains(text)) return (TextView) v;
            }
        }
        throw new AssertionError("no view with text: " + text + "\n" + dump(root));
    }

    static int count(View root, String text) {
        int n = 0;
        for (View v : all(root, new ArrayList<View>())) {
            if (v instanceof TextView && !(v instanceof EditText) && shown(v)
                    && ((TextView) v).getText().toString().contains(text)) n++;
        }
        return n;
    }

    static int exact(View root, String text) {
        int n = 0;
        for (View v : all(root, new ArrayList<View>())) {
            if (v instanceof TextView && !(v instanceof EditText) && shown(v)
                    && ((TextView) v).getText().toString().equals(text)) n++;
        }
        return n;
    }

    static boolean has(View root, String text) {
        return count(root, text) > 0;
    }

    static List<View> described(View root, String description) {
        List<View> out = new ArrayList<>();
        for (View v : all(root, new ArrayList<View>())) {
            if (shown(v) && description.contentEquals(String.valueOf(v.getContentDescription()))) out.add(v);
        }
        return out;
    }

    static List<EditText> fields(View root) {
        List<EditText> out = new ArrayList<>();
        for (View v : all(root, new ArrayList<View>())) {
            if (v instanceof EditText && shown(v)) out.add((EditText) v);
        }
        return out;
    }

    /** Clicks the view with this text, or its nearest clickable parent. */
    void click(String text) {
        View v = find(root(), text);
        while (!v.isClickable() && v.getParent() instanceof View) v = (View) v.getParent();
        assertTrue("not clickable: " + text, v.performClick());
        idle();
    }

    void type(String text) {
        fields(root()).get(0).setText(text);
        idle();
    }

    void dialogButton(int which) {
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(which).performClick();
        idle();
    }

    void pickMenu(String title) {
        PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("no menu open", menu);
        for (int i = 0; i < menu.getMenu().size(); i++) {
            MenuItem item = menu.getMenu().getItem(i);
            if (title.contentEquals(item.getTitle())) {
                shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(item);
                idle();
                return;
            }
        }
        throw new AssertionError("no menu item: " + title);
    }

    void back() {
        a.onBackPressed();
        idle();
    }

    /** Waits for the background export / import to show a dialog with this text. */
    void waitForDialog(String text) throws Exception {
        for (int i = 0; i < 400; i++) {
            idle();
            Dialog d = ShadowDialog.getLatestDialog();
            if (d != null && d.isShowing() && has(d.getWindow().getDecorView(), text)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("no dialog with: " + text + "\n" + dump(root()));
    }

    void start() {
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
    }

    static Session session(long day, double w, int... reps) {
        Session s = new Session(day);
        for (int r : reps) s.add(w, r);
        return s;
    }

    static void draw(View decor, Canvas c) {
        int w = View.MeasureSpec.makeMeasureSpec(decor.getWidth(), View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(decor.getHeight(), View.MeasureSpec.EXACTLY);
        decor.measure(w, h);
        decor.layout(0, 0, decor.getWidth(), decor.getHeight());
        decor.draw(c);
    }

    /** Writes the screen (with the open dialog over it, if any) to the shots folder. */
    void shot(String name) throws Exception {
        if (SHOTS == null) return;
        idle();
        View decor = screen();
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        draw(decor, c);
        Dialog d = ShadowDialog.getLatestDialog();
        if (d != null && d.isShowing()) {
            View dv = d.getWindow().getDecorView();
            c.drawColor(0x99000000);
            c.save();
            c.translate((decor.getWidth() - dv.getWidth()) / 2f, (decor.getHeight() - dv.getHeight()) / 2f);
            draw(dv, c);
            c.restore();
        }
        File dir = new File(SHOTS);
        dir.mkdirs();
        try (FileOutputStream f = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    ScrollView scroller() {
        for (View v : all(screen(), new ArrayList<View>())) {
            if (v instanceof ScrollView && shown(v)) return (ScrollView) v;
        }
        throw new AssertionError("no scroll view");
    }

    // ---------------------------------------------------------------- the flow

    @Test
    public void aWorkoutFromAnEmptyApp() throws Exception {
        start();
        assertTrue(has(screen(), "Δεν έχεις προγράμματα ακόμα"));
        assertTrue(has(screen(), "Export your workout data"));
        assertTrue(has(screen(), "Insert your workout data"));

        // A program with three new exercises.
        click("Νέο πρόγραμμα");
        type("Δευτέρα – Πλάτη");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        assertTrue(a.top() instanceof PresetScreen);
        click("Προσθήκη άσκησης");
        for (String name : new String[]{"Lat pulldown", "Seated cable row", "Bicep curl"}) {
            type(name);
            click("Νέα άσκηση «" + name + "»");
        }
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        Db db = a.db;
        long lat = db.findExercise("lat PULLDOWN");
        long row = db.findExercise("Seated cable row");
        long curl = db.findExercise("Bicep curl");
        assertTrue(lat > 0 && row > 0 && curl > 0);
        assertEquals(3, db.presetExercises(db.presets(0).get(0).id).size());

        // Three weeks of history, as if logged on earlier days.
        long t = Fmt.today();
        db.saveSession(lat, session(t - 21, 45, 10, 10, 9));
        db.saveSession(lat, session(t - 14, 47.5, 10, 9, 8));
        db.saveSession(lat, session(t - 7, 50, 10, 9, 8));
        db.saveSession(row, session(t - 7, 40, 12, 12, 10));
        db.saveSession(curl, session(t - 7, 12, 10, 10));

        // A second program that shares Lat pulldown.
        back();
        click("Νέο πρόγραμμα");
        type("Παρασκευή – Full body");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        click("Προσθήκη άσκησης");
        type("lat");
        click("Lat pulldown");
        type("Squat");
        click("Νέα άσκηση «Squat»");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        assertEquals("one shared exercise, not a copy", lat, db.findExercise("Lat pulldown"));
        assertEquals(4, db.exercises().size());

        // Log it from Friday's program. Target: 50 x 10, 9, 8 last time -> one more rep each.
        click("Lat pulldown");
        assertTrue(a.top() instanceof ExerciseScreen);
        View r = screen();
        assertTrue(dump(r), has(r, "Στόχος σήμερα"));
        assertTrue(dump(r), has(r, "50 kg × 11, 10, 9"));
        assertTrue(dump(r), has(r, "Εύρος 8–12 reps, βήμα 2.5 kg, αυτόματα"));
        assertTrue(has(r, "Προηγούμενη φορά"));
        assertEquals(1, exact(r, "50 kg × 10"));
        List<EditText> f = fields(r);
        assertEquals("three sets, already filled with the target", 6, f.size());
        assertEquals("50", f.get(0).getText().toString());
        assertEquals("11", f.get(1).getText().toString());
        assertEquals("9", f.get(5).getText().toString());
        assertFalse("nothing is asked before a set is ticked", has(r, "Πώς ήταν;"));
        assertFalse(has(r, "Επόμενη φορά"));
        shot("target");

        // Set 1: 50 x 10, the same as last time: no PR. Rated easy.
        f.get(1).setText("10");
        described(r, "Έγινε").get(0).performClick();
        idle();
        assertFalse(has(r, "Νέο PR"));
        assertEquals("asked under the ticked set only", 1, count(r, "Πώς ήταν;"));
        assertEquals(1, db.session(lat, t).n);
        described(r, "Χαλαρό").get(0).performClick();
        idle();
        assertEquals(Coach.EASY, db.session(lat, t).feel[0]);
        // Set 2: 52.5 x 9 is heavier than ever. Changing its weight carries to the unticked set 3.
        f.get(2).setText("52.5");
        f.get(3).setText("9");
        f.get(5).setText("8");
        idle();
        assertEquals("52.5", f.get(4).getText().toString());
        described(r, "Έγινε").get(1).performClick();
        idle();
        assertTrue(dump(r), has(r, "Νέο PR"));
        assertTrue(dump(r), has(r, "Βαρύτερο σετ: 52.5 kg × 9"));
        scroller().scrollTo(0, scroller().getChildAt(0).getHeight() / 3);
        shot("rate");
        scroller().scrollTo(0, 0);
        described(r, "Μέτριο").get(0).performClick();
        idle();
        described(r, "Έγινε").get(2).performClick();
        idle();
        described(r, "Στο όριο").get(0).performClick();
        idle();
        Session saved = db.session(lat, t);
        assertEquals("50 kg × 10 · 52.5 kg × 9, 8", Fmt.sets(saved));
        assertArrayEquals(new int[]{Coach.EASY, Coach.NORMAL, Coach.HARD}, Arrays.copyOf(saved.feel, 3));

        // 1 kg x 100 is never a PR; then the set is deleted again.
        click("Προσθήκη set");
        f = fields(r);
        assertEquals(8, f.size());
        f.get(6).setText("1");
        f.get(7).setText("100");
        described(r, "Έγινε").get(3).performClick();
        idle();
        assertEquals(4, db.session(lat, t).n);
        assertEquals(0, db.bestsBefore(lat, t).judge(1, 100));
        assertEquals("only the 52.5 kg set carries the trophy", 1, described(r, "PR").size());
        find(r, "4").performLongClick();
        idle();
        pickMenu("Διαγραφή set");
        assertEquals(3, db.session(lat, t).n);

        // Every set ticked: next time's target, set by set.
        assertTrue(dump(r), has(r, "Επόμενη φορά"));
        assertTrue(dump(r), has(r, "52.5 kg × 10, 8, 8"));
        assertTrue(dump(r), has(r, "+1 rep στα μέτρια, ίδια στα «στο όριο»"));
        assertEquals("answered sets show only the answer", 0, count(r, "έμεναν 3+"));
        scroller().scrollTo(0, scroller().getChildAt(0).getHeight());
        shot("next");
        scroller().scrollTo(0, 0);
        // Tapping the answer opens the choices; choosing the same one again clears the rating.
        find(r, "Στο όριο").performClick();
        idle();
        assertEquals(1, count(r, "έμεναν 3+"));
        described(r, "Στο όριο").get(0).performClick();
        idle();
        assertEquals(0, db.session(lat, t).feel[2]);
        assertTrue(dump(r), has(r, "52.5 kg × 10, 9, 8"));
        described(r, "Στο όριο").get(0).performClick();
        idle();
        assertEquals(Coach.HARD, db.session(lat, t).feel[2]);

        // Both programs show today's Lat pulldown.
        back();
        assertTrue(dump(screen()), has(screen(), "Σήμερα: 50 kg × 10 · 52.5 kg × 9, 8"));
        back();
        shot("home");
        assertTrue(dump(screen()), has(screen(), "Σήμερα 1 από 3"));
        assertTrue(has(screen(), "Σήμερα 1 από 2"));
        click("Δευτέρα – Πλάτη");
        assertTrue(dump(screen()), has(screen(), "Σήμερα: 50 kg × 10 · 52.5 kg × 9, 8"));
        assertTrue(has(screen(), "PR βάρους, PR 1RM σήμερα"));
        assertTrue(has(screen(), "Επόμενη φορά") && has(screen(), "52.5 kg × 10, 8, 8"));
        assertTrue(has(screen(), "Στόχος") && has(screen(), "40 kg × 12, 12, 11"));
        shot("preset");

        // History of the shared exercise, and its settings.
        click("Lat pulldown");
        described(screen(), "Ιστορικό").get(0).performClick();
        idle();
        assertTrue(a.top() instanceof HistoryScreen);
        ListView list = null;
        for (View v : all(screen(), new ArrayList<View>())) if (v instanceof ListView) list = (ListView) v;
        assertEquals("header + 4 sessions", 5, list.getAdapter().getCount());
        assertTrue(dump(screen()), has(screen(), "χαλαρό, μέτριο, στο όριο"));
        assertTrue(has(screen(), "Διακύμανση μέρας"));
        assertTrue(has(screen(), "Δευτέρα – Πλάτη, Παρασκευή – Full body"));
        shot("history");
        described(screen(), "Μενού").get(0).performClick();
        idle();
        pickMenu("Εύρος reps & βήμα κιλών");
        assertTrue(dump(root()), has(root(), "Αυτόματο (8–12)"));
        assertTrue(has(root(), "Αυτόματο (2.5)"));
        click("10–15");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        assertEquals(10, db.settings(lat).low);
        assertEquals(15, db.settings(lat).high);
        assertEquals(10, db.advice(lat, t + 1).low);
        back();
        back();
        back();
        assertTrue(a.top() instanceof HomeScreen);

        // The exercise library.
        click("Ασκήσεις");
        assertTrue(has(screen(), "Squat"));
        assertTrue(has(screen(), "Χωρίς καταγραφές ακόμα"));
        shot("exercises");
        click("Προγράμματα");

        // Never more than seven programs.
        for (int i = 0; i < 10; i++) db.addPreset("P" + i);
        assertEquals(Db.MAX_PRESETS, db.presetCount());
    }

    @Test
    public void theStackOfScreensSurvivesARestart() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        a = c.get();
        long preset = a.db.addPreset("Legs");
        long squat = a.db.addExercise("Squat");
        a.db.addToPreset(preset, squat);
        a.push(new PresetScreen(a, preset));
        a.push(new ExerciseScreen(a, squat, Fmt.today()));
        idle();
        Bundle state = new Bundle();
        c.pause().saveInstanceState(state);
        a.db.close();
        MainActivity again = Robolectric.buildActivity(MainActivity.class).setup(state).get();
        assertTrue(again.top() instanceof ExerciseScreen);
        again.onBackPressed();
        assertTrue(again.top() instanceof PresetScreen);
        again.onBackPressed();
        assertTrue(again.top() instanceof HomeScreen);
    }

    @Test
    public void lastTimeIsShownSetBySetWithItsRatings() throws Exception {
        start();
        Db db = a.db;
        long preset = db.addPreset("Δευτέρα – Πλάτη");
        long lat = db.addExercise("Lat pulldown");
        db.addToPreset(preset, lat);
        long t = Fmt.today();
        db.saveSession(lat, session(t - 21, 45, 10, 10, 9));
        db.saveSession(lat, session(t - 14, 47.5, 10, 9, 8));
        Session last = new Session(t - 7);
        last.add(30, 12);
        last.add(47.5, 11, Coach.EASY);
        last.add(47.5, 10, Coach.NORMAL);
        last.add(47.5, 9, Coach.HARD);
        db.saveSession(lat, last);
        a.restart();
        idle();
        click("Δευτέρα – Πλάτη");
        click("Lat pulldown");
        View r = screen();
        assertEquals(1, exact(r, "ζέσταμα"));
        assertEquals(1, exact(r, "χαλαρό"));
        assertEquals(1, exact(r, "μέτριο"));
        assertEquals(1, exact(r, "στο όριο"));
        // 11 with reps to spare was a great day (+7%): the easy set still goes to the top, the others stay.
        // The warm-up comes first, as last time.
        assertTrue(dump(r), has(r, "47.5 kg × 12, 10, 9"));
        assertTrue(has(r, "Πολύ καλή μέρα"));
        List<EditText> f = fields(r);
        assertEquals(8, f.size());
        assertEquals("30", f.get(0).getText().toString());
        assertEquals("12", f.get(1).getText().toString());
        assertEquals("47.5", f.get(2).getText().toString());
        assertEquals("12", f.get(3).getText().toString());
        shot("previous");
    }

    // ---------------------------------------------------------------- export / import

    @Test
    public void exportAndImportThroughTheApp() throws Exception {
        start();
        Db db = a.db;
        long preset = db.addPreset("Δευτέρα – Πλάτη");
        long lat = db.addExercise("Lat pulldown");
        long curl = db.addExercise("Bicep curl");
        db.addToPreset(preset, lat);
        db.addToPreset(preset, curl);
        long t = Fmt.today();
        Session rated = new Session(t - 7);
        rated.add(50, 10, Coach.EASY);
        rated.add(52.5, 9);
        db.saveSession(lat, rated);
        db.saveSession(curl, session(t - 7, 12, 10, 10));
        db.setSettings(curl, 10, 15, 2);
        a.restart();
        idle();

        // Export: the system file picker answers with a place to write to.
        Uri out = Uri.parse("content://files/backup.json");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        shadowOf(a.getContentResolver()).registerOutputStream(out, bytes);
        click("Export your workout data");
        Intent asked = shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.getAction());
        shadowOf(a).receiveResult(asked, Activity.RESULT_OK, new Intent().setData(out));
        waitForDialog("Αποθηκεύτηκαν 2 ασκήσεις και 2 καταγραφές");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        String json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"app\": \"gymnotes\""));
        assertTrue(json, json.contains("Lat pulldown"));

        Backup.Data data = Backup.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(2, data.exercises.size());
        assertEquals(1, data.presets.size());
        assertEquals(4, data.sets);

        // Something changes, then the file is merged back: the file's day wins, the new exercise stays.
        db.saveSession(lat, session(t - 7, 40, 5));
        long squat = db.addExercise("Squat");
        db.saveSession(squat, session(t, 80, 5));
        Uri in = Uri.parse("content://files/backup-in.json");
        shadowOf(a.getContentResolver()).registerInputStream(in, new ByteArrayInputStream(bytes.toByteArray()));
        click("Insert your workout data");
        Intent open = shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.getAction());
        shadowOf(a).receiveResult(open, Activity.RESULT_OK, new Intent().setData(in));
        waitForDialog("Συγχώνευση");
        assertTrue(dump(root()), has(root(), "Το αρχείο έχει 2 ασκήσεις, 1 προγράμματα και 2 καταγραφές (4 sets)."));
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        waitForDialog("Μπήκαν 2 ασκήσεις");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        assertEquals("50 kg × 10 · 52.5 kg × 9", Fmt.sets(db.session(lat, t - 7)));
        assertEquals(Coach.EASY, db.session(lat, t - 7).feel[0]);
        assertEquals(1, db.session(squat, t).n);
        assertEquals(10, db.settings(curl).low);
        assertEquals(2, db.settings(curl).step, 1e-9);
        assertTrue(a.top() instanceof HomeScreen);

        // Replace: only what the file has.
        Uri in2 = Uri.parse("content://files/backup-in2.json");
        shadowOf(a.getContentResolver()).registerInputStream(in2, new ByteArrayInputStream(bytes.toByteArray()));
        click("Insert your workout data");
        open = shadowOf(a).getNextStartedActivityForResult().intent;
        shadowOf(a).receiveResult(open, Activity.RESULT_OK, new Intent().setData(in2));
        waitForDialog("Αντικατάσταση");
        dialogButton(DialogInterface.BUTTON_NEUTRAL);
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        waitForDialog("Μπήκαν 2 ασκήσεις");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        assertEquals(-1, db.findExercise("Squat"));
        assertEquals(2, db.exercises().size());
        assertEquals(1, db.presetCount());

        // A file that is not a backup changes nothing.
        Uri bad = Uri.parse("content://files/bad.json");
        shadowOf(a.getContentResolver()).registerInputStream(bad,
                new ByteArrayInputStream("{\"hello\": [1, 2".getBytes(StandardCharsets.UTF_8)));
        click("Insert your workout data");
        open = shadowOf(a).getNextStartedActivityForResult().intent;
        shadowOf(a).receiveResult(open, Activity.RESULT_OK, new Intent().setData(bad));
        waitForDialog("Το αρχείο δεν είναι αντίγραφο του Gym Notes");
        dialogButton(DialogInterface.BUTTON_POSITIVE);
        assertEquals(2, db.exercises().size());
    }

    @Test
    public void olderBackupFilesStillRead() throws Exception {
        // Version 1.1 rated the whole day ("effort"); odd entries are skipped, not fatal.
        String json = "{\"app\":\"gymnotes\",\"format\":1,\"exercises\":["
                + "{\"name\":\"Lat pulldown\",\"reps\":[8,12],\"step\":2.5,\"log\":["
                + "{\"date\":\"2026-09-01\",\"effort\":1,\"sets\":[[50,10],[50,9]]},"
                + "{\"date\":\"2026-09-08\",\"effort\":3,\"sets\":[[50,10],[50,9,2],[50,8]]},"
                + "{\"date\":\"not a date\",\"sets\":[[50,10]]},"
                + "{\"date\":\"2026-09-15\",\"sets\":[[50,0],[-5,10],\"x\",[50,11,7]]}]},"
                + "{\"name\":\"  \"}, 42],"
                + "\"presets\":[{\"name\":\"Δευτέρα\",\"exercises\":[\"Lat pulldown\",\"lat PULLDOWN\",\"Squat\"]}]}";
        Backup.Data d = Backup.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, d.exercises.size());
        Backup.Ex ex = d.exercises.get(0);
        assertEquals(8, ex.low);
        assertEquals(12, ex.high);
        assertEquals(2.5, ex.step, 1e-9);
        assertEquals(3, ex.log.size());
        assertArrayEquals(new int[]{Coach.EASY, Coach.EASY}, Arrays.copyOf(ex.log.get(0).feel, 2));
        assertArrayEquals(new int[]{0, Coach.NORMAL, Coach.HARD}, Arrays.copyOf(ex.log.get(1).feel, 3));
        assertEquals("only the valid set, with an invalid rating dropped", 1, ex.log.get(2).n);
        assertEquals(0, ex.log.get(2).feel[0]);
        assertEquals(Arrays.asList("Lat pulldown", "Squat"), d.presets.get(0).exercises);

        start();
        Db.ImportResult r = a.db.importData(d, true);
        assertEquals(1, r.exercises);
        assertEquals(3, r.sessions);
        assertEquals(1, r.presets);
        assertTrue("the preset's unknown exercise is created", a.db.findExercise("Squat") > 0);
    }

    // ---------------------------------------------------------------- older databases

    static final String V1_EXERCISE = "CREATE TABLE exercise (id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL UNIQUE,"
            + " last_day INTEGER, sessions INTEGER NOT NULL DEFAULT 0, best_e1rm REAL NOT NULL DEFAULT 0,"
            + " max_weight REAL NOT NULL DEFAULT 0, last_pr INTEGER NOT NULL DEFAULT 0";
    static final String V2_COLUMNS = ", rep_low INTEGER NOT NULL DEFAULT 0, rep_high INTEGER NOT NULL DEFAULT 0,"
            + " step REAL NOT NULL DEFAULT 0, auto_low INTEGER NOT NULL DEFAULT 0, auto_high INTEGER NOT NULL DEFAULT 0,"
            + " auto_step REAL NOT NULL DEFAULT 0";

    /** A database as an older version of the app left it, with one program and Squat logged for three weeks. */
    static void oldDatabase(int version) {
        File path = RuntimeEnvironment.getApplication().getDatabasePath("gym.db");
        path.getParentFile().mkdirs();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(path, null);
        db.execSQL(V1_EXERCISE + (version >= 2 ? V2_COLUMNS : "") + ")");
        db.execSQL("CREATE TABLE preset (id INTEGER PRIMARY KEY, name TEXT NOT NULL, pos INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE preset_item (preset_id INTEGER NOT NULL REFERENCES preset(id) ON DELETE CASCADE,"
                + " exercise_id INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,"
                + " pos INTEGER NOT NULL, PRIMARY KEY (preset_id, exercise_id)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX preset_item_exercise ON preset_item(exercise_id)");
        db.execSQL("CREATE TABLE log_set (exercise_id INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,"
                + " day INTEGER NOT NULL, idx INTEGER NOT NULL, weight REAL NOT NULL, reps INTEGER NOT NULL,"
                + (version >= 3 ? " feel INTEGER NOT NULL DEFAULT 0," : "") + " PRIMARY KEY (exercise_id, day, idx)) WITHOUT ROWID");
        if (version == 2) {
            db.execSQL("CREATE TABLE log_note (exercise_id INTEGER NOT NULL REFERENCES exercise(id) ON DELETE CASCADE,"
                    + " day INTEGER NOT NULL, effort INTEGER NOT NULL, PRIMARY KEY (exercise_id, day)) WITHOUT ROWID");
        }
        long t = Fmt.today();
        db.execSQL("INSERT INTO exercise (id, name, key, last_day, sessions, best_e1rm, max_weight) VALUES (1, 'Squat', 'squat', "
                + (t - 7) + ", 3, 106.7, 80)");
        db.execSQL("INSERT INTO preset (id, name, pos) VALUES (1, 'Legs', 1)");
        db.execSQL("INSERT INTO preset_item (preset_id, exercise_id, pos) VALUES (1, 1, 1)");
        int[][] reps = {{10, 9, 8}, {10, 10, 9}, {10, 10, 9}};
        for (int s = 0; s < 3; s++) {
            long day = t - 21 + 7L * s;
            // A warm-up at 40 kg, then the work sets at 80 kg.
            db.execSQL("INSERT INTO log_set (exercise_id, day, idx, weight, reps) VALUES (1, " + day + ", 0, 40, 10)");
            for (int i = 0; i < 3; i++) {
                db.execSQL("INSERT INTO log_set (exercise_id, day, idx, weight, reps) VALUES (1, " + day + ", " + (i + 1)
                        + ", 80, " + reps[s][i] + ")");
            }
        }
        if (version == 2) {
            db.execSQL("INSERT INTO log_note (exercise_id, day, effort) VALUES (1, " + (t - 14) + ", 1)");
            db.execSQL("INSERT INTO log_note (exercise_id, day, effort) VALUES (1, " + (t - 7) + ", 3)");
        }
        if (version >= 3) {
            db.execSQL("UPDATE log_set SET feel = 2 WHERE day = " + (t - 7) + " AND idx = 1");
            // What 1.3 had cached: the 40 -> 80 kg warm-up jump read as a 5 kg step.
            db.execSQL("UPDATE exercise SET auto_low = 8, auto_high = 12, auto_step = 5");
        }
        db.setVersion(version);
        db.close();
    }

    void checkUpgraded() {
        start();
        Db db = a.db;
        long t = Fmt.today();
        assertEquals(4, db.getReadableDatabase().getVersion());
        Db.Exercise squat = db.exercise(1);
        assertEquals(3, squat.sessions);
        assertEquals(t - 7, squat.lastDay);
        assertEquals(80, squat.maxWeight, 1e-9);
        assertEquals("40 kg × 10 · 80 kg × 10, 10, 9", Fmt.sets(db.session(1, t - 7)));
        // The automatic range and step are there, the step without the warm-up jump.
        Coach.Settings st = db.coachSettings(1);
        assertEquals(8, st.autoLow);
        assertEquals(12, st.autoHigh);
        assertEquals(0, st.autoStep, 1e-9);
        Coach.Advice advice = db.advice(1, t);
        assertEquals(80, advice.weight, 1e-9);
        assertEquals(2.5, advice.step, 1e-9);
        click("Legs");
        assertTrue(dump(screen()), has(screen(), "Στόχος"));
        click("Squat");
        assertTrue(a.top() instanceof ExerciseScreen);
        assertTrue(dump(screen()), has(screen(), "Στόχος σήμερα"));
    }

    @Test
    public void upgradesFromVersion1() {
        oldDatabase(1);
        checkUpgraded();
        assertFalse(a.db.session(1, Fmt.today() - 7).rated());
        assertTrue(dump(screen()), has(screen(), "80 kg × 11, 11, 10"));
    }

    @Test
    public void upgradesFromVersion2() {
        oldDatabase(2);
        checkUpgraded();
        long t = Fmt.today();
        // A day rated easy: every set of it. A day at the limit: its last set.
        assertArrayEquals(new int[]{1, 1, 1, 1}, Arrays.copyOf(a.db.session(1, t - 14).feel, 4));
        assertArrayEquals(new int[]{0, 0, 0, 3}, Arrays.copyOf(a.db.session(1, t - 7).feel, 4));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Arrays.copyOf(a.db.session(1, t - 21).feel, 4));
        assertTrue(dump(screen()), has(screen(), "80 kg × 11, 11, 9"));
    }

    @Test
    public void upgradesFromVersion3() {
        oldDatabase(3);
        checkUpgraded();
        assertEquals(Coach.NORMAL, a.db.session(1, Fmt.today() - 7).feel[1]);
    }

    // ---------------------------------------------------------------- years of data

    @Test
    public void twoYearsOfLogsStayFast() throws Exception {
        start();
        Db db = a.db;
        long t = Fmt.today();
        Backup.Data data = new Backup.Data();
        Backup.Pre pre = new Backup.Pre();
        pre.name = "Κάθε μέρα";
        int sets = 0;
        for (int e = 0; e < 12; e++) {
            Backup.Ex ex = new Backup.Ex();
            ex.name = "Άσκηση " + (e + 1);
            pre.exercises.add(ex.name);
            double w = 30 + 5 * e;
            for (int s = 0; s < 312; s++) {
                Session day = new Session(t - 2 - 7L * (312 - s) / 3);
                if (s % 13 == 12) w += 2.5;
                day.add(w * 0.5, 10);
                for (int i = 0; i < 3; i++) day.add(w, 8 + (s % 13) / 3 - i / 2, 1 + (s + i) % 3);
                if (ex.log.isEmpty() || ex.log.get(ex.log.size() - 1).day != day.day) {
                    ex.log.add(day);
                    sets += day.n;
                }
            }
            data.exercises.add(ex);
        }
        data.presets.add(pre);
        db.importData(data, true);
        assertTrue("about 15,000 sets: " + sets, sets > 12000);
        long preset = db.presets(t).get(0).id;
        long first = db.findExercise("Άσκηση 1");

        // Warm up the JVM and the database once, then measure the best of a few runs each.
        StringBuilder times = new StringBuilder();
        double program = time(times, "program with 12 targets", () -> {
            for (Db.Exercise e : db.presetExercises(preset)) {
                db.session(e.id, e.lastDay);
                db.advice(e.id, t);
            }
        });
        double home = time(times, "home lists", () -> {
            db.presets(t);
            db.exercises();
            db.presetNamesByExercise();
        });
        double log = time(times, "open one exercise", () -> {
            db.bestsBefore(first, t);
            db.lastDayBefore(first, t);
            db.advice(first, t);
        });
        double save = time(times, "save a session", () -> db.saveSession(first, session(t, 80, 10, 9, 8)));
        System.out.println(times);
        // Generous limits (a phone is slower than this machine); they catch anything that starts scanning the whole log.
        assertTrue(times.toString(), program < 60);
        assertTrue(times.toString(), home < 30);
        assertTrue(times.toString(), log < 30);
        assertTrue(times.toString(), save < 100);

        // The screens open on it, and the whole thing exports and reads back.
        a.restart();
        idle();
        click("Κάθε μέρα");
        assertEquals(12, count(screen(), "Άσκηση"));
        click("Άσκηση 1");
        assertTrue(a.top() instanceof ExerciseScreen);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] n = Backup.export(db, out);
        assertEquals(12, n[0]);
        Backup.Data back = Backup.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(n[1], back.sessions);
        assertTrue(out.size() < 2_000_000);
    }

    /** Best of several runs, in milliseconds. */
    static double time(StringBuilder log, String what, Runnable r) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            long start = System.nanoTime();
            r.run();
            best = Math.min(best, (System.nanoTime() - start) / 1e6);
        }
        log.append(String.format(java.util.Locale.ROOT, "%s: %.1f ms%n", what, best));
        return best;
    }
}
