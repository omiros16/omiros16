package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Minimal line chart of one value per session; PR sessions get a gold dot. */
final class ChartView extends View {
    private float[] values = new float[0];
    private boolean[] pr = new boolean[0];
    private String firstLabel = "";
    private String lastLabel = "";
    private final float density;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    ChartView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        line.setColor(ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        grid.setColor(FAINT);
        grid.setStrokeWidth(density);
        text.setColor(DIM);
        text.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
    }

    void setData(float[] values, boolean[] pr, String firstLabel, String lastLabel) {
        this.values = values;
        this.pr = pr;
        this.firstLabel = firstLabel;
        this.lastLabel = lastLabel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = values.length;
        if (n < 2) return;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 1) {
            max += 1;
            min -= 1;
        }
        float pad = (max - min) * 0.1f;
        min -= pad;
        max += pad;

        float left = text.measureText(Fmt.num1(max) + "  ");
        float right = getWidth() - 8 * density;
        float top = 8 * density;
        float bottom = getHeight() - 20 * density;

        for (int k = 0; k <= 2; k++) {
            float v = min + pad + (max - min - 2 * pad) * k / 2f;
            float y = bottom - (v - min) / (max - min) * (bottom - top);
            c.drawLine(left, y, right, y, grid);
            c.drawText(Fmt.num1(v), 0, y + text.getTextSize() / 3, text);
        }
        c.drawText(firstLabel, left, getHeight() - 4 * density, text);
        c.drawText(lastLabel, right - text.measureText(lastLabel), getHeight() - 4 * density, text);

        path.reset();
        float step = (right - left) / (n - 1);
        for (int i = 0; i < n; i++) {
            float x = left + i * step;
            float y = bottom - (values[i] - min) / (max - min) * (bottom - top);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, line);
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            if (!pr[i] && !last) continue;
            float x = left + i * step;
            float y = bottom - (values[i] - min) / (max - min) * (bottom - top);
            dot.setColor(pr[i] ? GOLD : ACCENT);
            c.drawCircle(x, y, 3.8f * density, dot);
        }
    }
}
