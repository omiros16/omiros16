package com.omiros.habits;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPopupMenu;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;

/** Shared helpers. Runs under libfaketime: the clock is whatever {@link #setClock} last wrote. */
public class Base {
    /**
     * The app keeps process-wide singletons; Robolectric gives each test fresh files but the same
     * classes, so they are cleared before every test.
     */
    @org.junit.Before
    public void resetSingletons() throws Exception {
        for (String[] f : new String[][]{{"MonthStore", "instance"}, {"Rules", "instance"}, {"LockService", "running"}}) {
            java.lang.reflect.Field field = Class.forName(PKG + "." + f[0]).getDeclaredField(f[1]);
            field.setAccessible(true);
            field.set(null, null);
        }
    }

    static final File OUT = new File(System.getProperty("shots.out", "out"));
    static final String PKG = "com.omiros.habits";

    /** Moves libfaketime's clock (atomic file swap) and waits until the new date shows. */
    static void setClock(String dateTime) throws Exception {
        Path f = new File(System.getenv("FAKETIME_TIMESTAMP_FILE")).toPath();
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        String want = dateTime.substring(0, 10);
        String hm = dateTime.substring(11, 16);
        for (int i = 0; i < 50; i++) {
            Files.write(tmp, ("@" + dateTime + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Thread.sleep(20);
            if (want.equals(LocalDate.now().toString()) && java.time.LocalTime.now().toString().startsWith(hm)) break;
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(want, LocalDate.now().toString());
            Thread.sleep(5);
        }
    }

    @SuppressWarnings("unchecked")
    static ActivityController<? extends Activity> launch(String cls) throws Exception {
        ActivityController<? extends Activity> c = Robolectric.buildActivity(
                (Class<? extends Activity>) Class.forName(PKG + "." + cls)).setup();
        idle();
        return c;
    }

    static Activity main() throws Exception {
        return launch("MainActivity").get();
    }

    static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    static android.app.Application ctx() {
        return RuntimeEnvironment.getApplication();
    }

    static void snap(Activity a, String name) throws Exception {
        idle();
        View root = a.getWindow().getDecorView();
        Bitmap bmp = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        root.draw(c);
        Dialog d = ShadowDialog.getLatestDialog();
        if (d != null && d.isShowing()) {
            c.drawColor(0x80000000);
            View dv = d.getWindow().getDecorView();
            c.save();
            c.translate((bmp.getWidth() - dv.getWidth()) / 2f, (bmp.getHeight() - dv.getHeight()) / 2f);
            dv.draw(c);
            c.restore();
        }
        save(bmp, name);
    }

    static void save(Bitmap bmp, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream f = new FileOutputStream(new File(OUT, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    static View find(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }

    static View find(Activity a, String text) {
        return find(a.getWindow().getDecorView(), text);
    }

    /** All texts on screen, for failure messages. */
    static String texts(View v) {
        StringBuilder s = new StringBuilder();
        collect(v, s);
        return s.toString();
    }

    private static void collect(View v, StringBuilder s) {
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) s.append(" | ").append(((TextView) v).getText());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), s);
        }
    }

    static void assertShows(Activity a, String text) {
        assertNotNull("missing «" + text + "» in" + texts(a.getWindow().getDecorView()), find(a, text));
    }

    static void click(Activity a, String text) {
        View v = find(a, text);
        assertNotNull("missing «" + text + "» in" + texts(a.getWindow().getDecorView()), v);
        while (!v.isClickable()) v = (View) v.getParent();
        v.performClick();
        idle();
    }

    /** The habit row that shows {@code name}: the first text with that name inside something clickable. */
    static View row(Activity a, String name) {
        ArrayList<View> all = new ArrayList<>();
        findAll(a.getWindow().getDecorView(), name, all);
        for (View v : all) {
            while (v != null && !v.isClickable()) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            if (v != null) return v;
        }
        fail(name + " in" + texts(a.getWindow().getDecorView()));
        return null;
    }

    static void findAll(View v, String text, ArrayList<View> out) {
        if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findAll(g.getChildAt(i), text, out);
        }
    }

    static boolean doneToday(Activity a, String name) {
        String desc = row(a, name).getContentDescription().toString();
        return desc.endsWith(", έγινε σήμερα");
    }

    static void clickByDescription(Activity a, String desc) {
        ArrayList<View> out = new ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(out, desc, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertFalse(desc, out.isEmpty());
        out.get(0).performClick();
        idle();
    }

    static void seed(YearMonth ym, String json) throws Exception {
        File dir = new File(ctx().getFilesDir(), "months");
        dir.mkdirs();
        Files.write(new File(dir, ym + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    static String month(String... habits) {
        return "{\"habits\":[" + String.join(",", habits) + "]}";
    }

    static File file(YearMonth ym) {
        return new File(ctx().getFilesDir(), "months/" + ym + ".json");
    }

    static String read(YearMonth ym) throws Exception {
        return new String(Files.readAllBytes(file(ym).toPath()), StandardCharsets.UTF_8);
    }

    static String habit(int id, String name, int... days) {
        StringBuilder s = new StringBuilder("{\"id\":" + id + ",\"name\":\"" + name + "\",\"days\":[");
        for (int i = 0; i < days.length; i++) s.append(i > 0 ? "," : "").append(days[i]);
        return s.append("]}").toString();
    }

    static int[] range(int from, int to, int... except) {
        ArrayList<Integer> l = new ArrayList<>();
        outer:
        for (int d = from; d <= to; d++) {
            for (int e : except) if (e == d) continue outer;
            l.add(d);
        }
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    /** Writes the rules doc as the one in force (and nothing pending). */
    static void seedRules(String nowJson) {
        ctx().getSharedPreferences("rules", Context.MODE_PRIVATE).edit().putString("now", nowJson).commit();
    }

    static String rule(int id, int minute, int level, boolean all, int timer) {
        return "\"" + id + "\":{\"min\":" + minute + ",\"level\":" + level + ",\"all\":" + all + ",\"timer\":" + timer + "}";
    }

    static String rules(String... habitRules) {
        return "{\"on\":true,\"habits\":{" + String.join(",", habitRules) + "},\"block\":[\"com.instagram.android\"],"
                + "\"allow\":[\"com.google.android.apps.maps\"],\"phones\":[],\"emails\":[],\"review\":true,\"drop\":[]}";
    }

    static AlertDialog dialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText r = findEdit(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    static void addHabit(Activity a, String name) {
        click(a, "+ Νέα συνήθεια");
        AlertDialog d = dialog();
        findEdit(d.getWindow().getDecorView()).setText(name);
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
    }

    static PopupMenu longPress(Activity a, String name) {
        row(a, name).performLongClick();
        idle();
        return ShadowPopupMenu.getLatestPopupMenu();
    }

    static void choose(PopupMenu pm, String title) {
        Menu m = pm.getMenu();
        for (int i = 0; i < m.size(); i++) {
            if (m.getItem(i).getTitle().toString().equals(title)) {
                shadowOf(pm).getOnMenuItemClickListener().onMenuItemClick(m.getItem(i));
                idle();
                return;
            }
        }
        fail("no menu item " + title);
    }

    /** Clicks a text inside the latest dialog. */
    static void clickInDialog(String text) {
        View v = find(dialog().getWindow().getDecorView(), text);
        assertNotNull("missing «" + text + "» in dialog" + texts(dialog().getWindow().getDecorView()), v);
        while (!v.isClickable()) v = (View) v.getParent();
        v.performClick();
        idle();
    }

    static Object call(String cls, String method, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = Class.forName(PKG + "." + cls).getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
}
