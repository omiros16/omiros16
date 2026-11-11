package com.omiros.habits;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.YearMonth;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w400dp-h880dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetTest extends Base {
    @Test
    public void showsTodayAndTicksWithOneTap() throws Exception {
        setClock("2026-10-17 18:42:00");
        seed(YearMonth.of(2026, 10), month(habit(1, "Διάβασμα 20'"), habit(2, "Γυμναστική", 17),
                habit(3, "Διαλογισμός"), habit(4, "Χωρίς social media")));
        seedRules(rules(rule(1, 20 * 60, Rules.LOCK, true, 20), rule(3, 22 * 60, Rules.NAG, true, 0)));
        RemoteViews rv = Widget.views(ctx());
        FrameLayout parent = new FrameLayout(ctx());
        View w = rv.apply(ctx(), parent);
        int width = Ui.dp(ctx(), 330);
        int height = Ui.dp(ctx(), 230);
        w.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        w.layout(0, 0, width, height);
        assertNotNull(find(w, "Σήμερα 1 από 4"));
        assertNotNull(find(w, "Επόμενο: Διάβασμα 20' ως τις 20:00"));
        assertNotNull(find(w, "○  Διάβασμα 20'   20:00"));
        assertNotNull(find(w, "✓  Γυμναστική"));
        Bitmap bmp = Bitmap.createBitmap(width + 80, height + 80, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF6B7A88);
        c.translate(40, 40);
        w.draw(c);
        save(bmp, "widget");

        new AlarmReceiver().onReceive(ctx(), new Intent(Alarms.ACTION_TOGGLE).putExtra(Alarms.EXTRA_ID, 1));
        assertTrue(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(1).isDone(17));
        new AlarmReceiver().onReceive(ctx(), new Intent(Alarms.ACTION_TOGGLE).putExtra(Alarms.EXTRA_ID, 2));
        assertFalse(MonthStore.get(ctx()).load(YearMonth.of(2026, 10)).find(2).isDone(17));
    }
}
