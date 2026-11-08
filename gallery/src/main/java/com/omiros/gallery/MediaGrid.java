package com.omiros.gallery;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Thumbnail grid built from ListView rows (the framework has no RecyclerView), with day headers,
 * pinch to change the number of columns, and long-press multi-select that extends while the finger slides.
 */
final class MediaGrid extends ListView {
    interface Listener {
        void onOpen(Media m);

        void onSelectionChanged();

        void onColumnsChanged(int cols);
    }

    static final int MIN_COLS = 2;
    static final int MAX_COLS = 6;

    private final Listener listener;
    private final Adapter adapter = new Adapter();
    private final ScaleGestureDetector scale;
    private final int gap;
    private List<Media> items = Collections.emptyList();
    private List<int[]> rows = Collections.emptyList();
    private boolean byDay;
    private int cols;
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
    private boolean selecting;
    private float pinch = 1;
    private boolean multiTouch;

    // drag-select: long-press a cell, then slide to select (or deselect) everything between it and the finger
    private boolean touching;
    private boolean dragging;
    private boolean dragCancelSent;
    private boolean dragAdds;
    private int anchor;
    private int lastDrag;
    private float lastX;
    private float lastY;
    private final HashSet<Long> base = new HashSet<>();
    private final int edge;
    private final int maxSpeed;
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging) return;
            // near the top or bottom edge the list scrolls by itself, faster the closer the finger is
            float speed = 0;
            if (lastY < edge) speed = -Math.min(1, (edge - lastY) / edge);
            else if (lastY > getHeight() - edge) speed = Math.min(1, (lastY - getHeight() + edge) / edge);
            if (speed != 0) {
                scrollListBy(Math.round(speed * maxSpeed));
                dragTo(indexAt(lastX, lastY));
            }
            postOnAnimation(this);
        }
    };

    MediaGrid(Context c, int cols, Listener listener) {
        super(c);
        this.listener = listener;
        this.cols = cols;
        gap = Theme.dp(c, 2);
        edge = Theme.dp(c, 72);
        maxSpeed = Theme.dp(c, 18);
        setDivider(null);
        setSelector(android.R.color.transparent);
        setFastScrollEnabled(true);
        setClipToPadding(false);
        setPadding(0, 0, 0, Theme.dp(c, 16));
        setAdapter(adapter);
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinch = 1;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                pinch *= d.getScaleFactor();
                if (pinch > 1.3f && MediaGrid.this.cols > MIN_COLS) {
                    setColumns(MediaGrid.this.cols - 1);
                    pinch = 1;
                } else if (pinch < 0.77f && MediaGrid.this.cols < MAX_COLS) {
                    setColumns(MediaGrid.this.cols + 1);
                    pinch = 1;
                }
                return true;
            }
        });
    }

    void setItems(List<Media> items, boolean byDay) {
        this.items = items;
        this.byDay = byDay;
        HashSet<Long> ids = new HashSet<>();
        for (Media m : items) ids.add(m.id);
        boolean dropped = selected.retainAll(ids); // items deleted meanwhile
        if (dropped) selecting = !selected.isEmpty();
        rebuild();
        if (dropped) listener.onSelectionChanged();
    }

    List<Media> items() {
        return items;
    }

    private void rebuild() {
        long[] dates = new long[items.size()];
        for (int i = 0; i < dates.length; i++) dates[i] = items.get(i).date;
        rows = Sections.rows(dates, cols, byDay, ZoneId.systemDefault());
        adapter.notifyDataSetChanged();
    }

    private void setColumns(int c) {
        int first = 0;
        int pos = getFirstVisiblePosition();
        if (pos < rows.size()) first = rows.get(pos)[1];
        cols = c;
        rebuild();
        setSelection(Sections.rowOf(rows, first));
        listener.onColumnsChanged(c);
    }

    boolean isSelecting() {
        return selecting;
    }

    List<Media> getSelected() {
        List<Media> out = new ArrayList<>();
        for (Media m : items) if (selected.contains(m.id)) out.add(m);
        return out;
    }

    void clearSelection() {
        selected.clear();
        setSelecting(false);
    }

    void selectAll() {
        for (Media m : items) selected.add(m.id);
        setSelecting(true);
    }

    private void setSelecting(boolean on) {
        selecting = on;
        adapter.notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    private void toggle(Media m) {
        if (!selected.remove(m.id)) selected.add(m.id);
        setSelecting(!selected.isEmpty());
    }

    private void startDrag(int index) {
        base.clear();
        base.addAll(selected);
        dragAdds = !selected.contains(items.get(index).id);
        anchor = index;
        lastDrag = -1;
        dragging = true;
        dragCancelSent = false;
        dragTo(index);
        postOnAnimation(autoScroll);
    }

    private void dragTo(int index) {
        if (index < 0 || index == lastDrag) return;
        lastDrag = index;
        selected.clear();
        selected.addAll(base);
        for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
            long id = items.get(i).id;
            if (dragAdds) selected.add(id);
            else selected.remove(id);
        }
        selecting = !selected.isEmpty();
        adapter.notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    /** The item under a point, or -1 over a day header or empty space. */
    private int indexAt(float x, float y) {
        int pos = pointToPosition((int) x, (int) Math.max(0, Math.min(getHeight() - 1, y)));
        if (pos == INVALID_POSITION || pos >= rows.size()) return -1;
        int[] row = rows.get(pos);
        if (row[0] != Sections.CELLS || getWidth() == 0) return -1;
        int col = (int) (Math.max(0, Math.min(getWidth() - 1, x)) * cols / getWidth());
        return Math.min(row[1] + col, row[2] - 1);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (dragging) {
            if (!dragCancelSent) {
                // the long-pressed cell still holds the touch: end it so the grid neither clicks nor scrolls
                dragCancelSent = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            lastX = ev.getX();
            lastY = ev.getY();
            if (action == MotionEvent.ACTION_MOVE) dragTo(indexAt(lastX, lastY));
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                touching = false;
                removeCallbacks(autoScroll);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) touching = true;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false;
        scale.onTouchEvent(ev);
        if (action == MotionEvent.ACTION_DOWN) multiTouch = false;
        if (ev.getPointerCount() > 1 && !multiTouch) {
            // a second finger turns the gesture into a pinch: cancel the scroll / tap in progress
            multiTouch = true;
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        if (multiTouch) return true;
        return super.dispatchTouchEvent(ev);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position)[0];
        }

        @Override
        public boolean isEnabled(int position) {
            return false; // the cells handle their own taps
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            int[] row = rows.get(position);
            Context c = parent.getContext();
            if (row[0] == Sections.HEADER) {
                TextView t = convert instanceof TextView ? (TextView) convert : header(c);
                Media first = items.get(row[1]);
                t.setText(Sections.label(first.date, LocalDate.now(), ZoneId.systemDefault()));
                return t;
            }
            LinearLayout line = convert instanceof LinearLayout ? (LinearLayout) convert : null;
            if (line == null || line.getChildCount() != cols) line = line(c);
            for (int i = 0; i < cols; i++) {
                CellView cell = (CellView) line.getChildAt(i);
                int index = row[1] + i;
                if (index < row[2]) {
                    final int at = index;
                    final Media m = items.get(index);
                    cell.setVisibility(VISIBLE);
                    cell.bind(m, selecting, selected.contains(m.id));
                    cell.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (selecting) toggle(m);
                            else listener.onOpen(m);
                        }
                    });
                    cell.setOnLongClickListener(new OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            if (touching && !multiTouch) startDrag(at);
                            else toggle(m); // TalkBack long-press: no finger to follow
                            return true;
                        }
                    });
                } else {
                    cell.setVisibility(INVISIBLE);
                    cell.bind(null, false, false);
                    cell.setOnClickListener(null);
                    cell.setOnLongClickListener(null);
                }
            }
            return line;
        }

        private TextView header(Context c) {
            TextView t = new TextView(c);
            t.setTextColor(Theme.TEXT);
            t.setTextSize(15);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t.setPadding(Theme.dp(c, 16), Theme.dp(c, 20), Theme.dp(c, 16), Theme.dp(c, 10));
            return t;
        }

        private LinearLayout line(Context c) {
            LinearLayout l = new LinearLayout(c);
            l.setPadding(0, 0, 0, gap);
            for (int i = 0; i < cols; i++) {
                CellView cell = new CellView(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (i > 0) lp.leftMargin = gap;
                l.addView(cell, lp);
            }
            return l;
        }
    }
}
