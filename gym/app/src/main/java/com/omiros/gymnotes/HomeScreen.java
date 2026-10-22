package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Start page: the presets (with export / import) and the exercise library. */
final class HomeScreen extends Screen {
    private int tab;
    private final TextView[] tabs = new TextView[2];
    private View presetsPage;
    private View exercisesPage;
    private TextView presetsEmpty;
    private LinearLayout presetList;
    private LinearLayout addPreset;
    private TextView addPresetTitle;
    private TextView addPresetCount;
    private EditText search;
    private ExerciseAdapter adapter;
    private TextView exercisesEmpty;

    HomeScreen(MainActivity act, int tab) {
        super(act);
        this.tab = tab == 1 ? 1 : 0;
    }

    @Override
    String key() {
        return "home:" + tab;
    }

    @Override
    View build() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(BG);
        root.addView(header(false));

        LinearLayout tabRow = ui.row();
        tabRow.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), 0);
        String[] names = {"Προγράμματα", "Ασκήσεις"};
        for (int i = 0; i < 2; i++) {
            final int which = i;
            tabs[i] = ui.tab(names[i]);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(which);
                }
            });
            tabRow.addView(tabs[i], Ui.fill());
        }
        root.addView(tabRow);
        root.addView(ui.hairline(0));

        FrameLayout pages = new FrameLayout(act);
        presetsPage = buildPresetsPage();
        exercisesPage = buildExercisesPage();
        pages.addView(presetsPage);
        pages.addView(exercisesPage);
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        selectTab(tab);
        return root;
    }

    private void selectTab(int which) {
        tab = which;
        presetsPage.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        exercisesPage.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2; i++) ui.styleTab(tabs[i], i == which);
        ui.hideKeyboard(search);
    }

    @Override
    boolean onBack() {
        if (tab == 1) {
            selectTab(0);
            return true;
        }
        return false;
    }

    @Override
    void refresh() {
        setTitle("Gym Notes", Fmt.dayLong(Fmt.today()));
        refreshPresets();
        adapter.set(db.exercises(), db.presetNamesByExercise());
    }

    // ---------------------------------------------------------------- presets tab

    private View buildPresetsPage() {
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        LinearLayout col = ui.column();
        col.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(32));
        sv.addView(col);

        presetsEmpty = ui.text("Δεν έχεις προγράμματα ακόμα. Φτιάξε έως " + Db.MAX_PRESETS
                + ", π.χ. ένα για κάθε μέρα προπόνησης, και βάλε μέσα τις ασκήσεις της ημέρας.", 15, SOFT, false);
        presetsEmpty.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(16));
        col.addView(presetsEmpty);
        presetList = ui.group();
        col.addView(presetList, Ui.matchWrap());

        addPreset = ui.actionRow(R.drawable.ic_add, "Νέο πρόγραμμα", null);
        addPresetTitle = (TextView) ((LinearLayout) addPreset.getChildAt(1)).getChildAt(0);
        addPresetCount = ui.text(14, DIM, false);
        addPresetCount.setPadding(ui.dp(8), 0, ui.dp(6), 0);
        addPreset.addView(addPresetCount);
        addPreset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newPreset();
            }
        });

        col.addView(ui.label("Τα δεδομένα σου"));
        LinearLayout data = ui.group();
        LinearLayout export = ui.actionRow(R.drawable.ic_upload, "Export your workout data",
                "Όλα σε ένα αρχείο .json");
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startExport();
            }
        });
        ui.addRow(data, export);
        LinearLayout insert = ui.actionRow(R.drawable.ic_download, "Insert your workout data",
                "Επαναφορά από αρχείο εξαγωγής");
        insert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startImport();
            }
        });
        ui.addRow(data, insert);
        col.addView(data, Ui.matchWrap());
        return sv;
    }

    private void refreshPresets() {
        presetList.removeAllViews();
        List<Db.Preset> presets = db.presets(Fmt.today());
        presetsEmpty.setVisibility(presets.isEmpty() ? View.VISIBLE : View.GONE);
        for (final Db.Preset p : presets) {
            final LinearLayout item = ui.groupRow();
            LinearLayout col = ui.column();
            TextView name = ui.text(p.name, 17, TEXT, ui.semibold);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            LinearLayout sub = ui.row();
            sub.setPadding(0, ui.dp(5), 0, 0);
            String count = p.exercises == 0 ? "Χωρίς ασκήσεις ακόμα"
                    : p.exercises == 1 ? "1 άσκηση" : p.exercises + " ασκήσεις";
            boolean started = p.doneToday > 0 && p.exercises > 0;
            sub.addView(ui.text(started ? "Σήμερα " + p.doneToday + " από " + p.exercises : count, 14,
                    started ? SOFT : DIM, false));
            if (started) {
                LinearLayout.LayoutParams lp = Ui.wrap();
                lp.leftMargin = ui.dp(10);
                sub.addView(progress(p.doneToday, p.exercises), lp);
            }
            col.addView(sub);
            item.addView(col, Ui.fill());
            item.addView(ui.icon(R.drawable.ic_chevron_right, DIM), ui.size(24, 24));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.push(new PresetScreen(act, p.id));
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    presetMenu(p, item);
                    return true;
                }
            });
            ui.addRow(presetList, item);
        }
        boolean full = presets.size() >= Db.MAX_PRESETS;
        addPreset.setEnabled(!full);
        addPresetTitle.setText(full ? "Έχεις όλα τα προγράμματα" : "Νέο πρόγραμμα");
        addPresetTitle.setTextColor(full ? DIM : TEXT);
        addPreset.getChildAt(0).setAlpha(full ? 0.4f : 1f);
        addPresetCount.setText(presets.size() + " από " + Db.MAX_PRESETS);
        ui.addRow(presetList, addPreset);
    }

    /** One short bar per exercise of the day, filled for the ones done today. */
    private View progress(int done, int total) {
        LinearLayout bars = ui.row();
        for (int i = 0; i < Math.min(total, 12); i++) {
            View b = new View(act);
            b.setBackground(ui.round(i < done ? TEXT : FIELD, 2));
            LinearLayout.LayoutParams lp = ui.size(12, 4);
            if (i > 0) lp.leftMargin = ui.dp(3);
            bars.addView(b, lp);
        }
        return bars;
    }

    private void newPreset() {
        ui.prompt("Νέο πρόγραμμα", "π.χ. Δευτέρα – Πλάτη & δικέφαλα", null, "Δημιουργία", new Ui.OnText() {
            @Override
            public void run(String name) {
                long id = db.addPreset(name);
                if (id < 0) {
                    ui.toast("Μέχρι " + Db.MAX_PRESETS + " προγράμματα");
                    return;
                }
                act.push(new PresetScreen(act, id));
            }
        });
    }

    private void presetMenu(final Db.Preset p, View anchor) {
        ui.menu(anchor, new String[]{"Μετονομασία", "Μετακίνηση πάνω", "Μετακίνηση κάτω", "Διαγραφή"},
                new Ui.OnPick() {
                    @Override
                    public void run(int which) {
                        switch (which) {
                            case 0:
                                ui.prompt("Μετονομασία", "Όνομα προγράμματος", p.name, "Αποθήκευση", new Ui.OnText() {
                                    @Override
                                    public void run(String name) {
                                        db.renamePreset(p.id, name);
                                        refresh();
                                    }
                                });
                                break;
                            case 1:
                            case 2:
                                db.movePreset(p.id, which == 1 ? -1 : 1);
                                refresh();
                                break;
                            case 3:
                                confirmDeletePreset(ui, db, p.id, p.name, new Runnable() {
                                    @Override
                                    public void run() {
                                        refresh();
                                    }
                                });
                                break;
                        }
                    }
                });
    }

    static void confirmDeletePreset(Ui ui, final Db db, final long id, String name, final Runnable after) {
        ui.confirm("Διαγραφή «" + name + "»;", "Σβήνεται μόνο το πρόγραμμα. Οι ασκήσεις και το ιστορικό τους μένουν.",
                "Διαγραφή", true, new Runnable() {
                    @Override
                    public void run() {
                        db.deletePreset(id);
                        after.run();
                    }
                });
    }

    // ---------------------------------------------------------------- exercises tab

    private View buildExercisesPage() {
        LinearLayout col = ui.column();
        LinearLayout top = ui.row();
        top.setPadding(ui.dp(16), ui.dp(16), ui.dp(8), ui.dp(8));
        search = ui.field("Αναζήτηση άσκησης");
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(DIM));
        search.setCompoundDrawablePadding(ui.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
            }
        });
        top.addView(search, Ui.fill());
        ImageView add = ui.iconButton(R.drawable.ic_add, "Νέα άσκηση");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newExercise();
            }
        });
        LinearLayout.LayoutParams alp = ui.size(48, 48);
        alp.leftMargin = ui.dp(4);
        top.addView(add, alp);
        col.addView(top);

        FrameLayout frame = new FrameLayout(act);
        final ListView list = new ListView(act);
        list.setDivider(new InsetDrawable(new ColorDrawable(LINE), ui.dp(20), 0, 0, 0));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(0));
        list.setClipToPadding(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setPadding(0, ui.dp(4), 0, ui.dp(32));
        adapter = new ExerciseAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ui.hideKeyboard(search);
                act.push(new HistoryScreen(act, adapter.getItem(position).id));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                exerciseMenu(adapter.getItem(position), view);
                return true;
            }
        });
        frame.addView(list);
        exercisesEmpty = ui.text("", 15, SOFT, false);
        exercisesEmpty.setGravity(Gravity.CENTER_HORIZONTAL);
        exercisesEmpty.setPadding(ui.dp(32), ui.dp(40), ui.dp(32), 0);
        frame.addView(exercisesEmpty);
        col.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    private void newExercise() {
        String typed = Db.clean(search.getText().toString());
        ui.prompt("Νέα άσκηση", "π.χ. Lat pulldown", typed.isEmpty() ? null : typed, "Προσθήκη", new Ui.OnText() {
            @Override
            public void run(String name) {
                if (db.findExercise(name) >= 0) {
                    ui.toast("Υπάρχει ήδη: " + name);
                    return;
                }
                db.addExercise(name);
                search.setText("");
                refresh();
                ui.toast("Προστέθηκε. Βάλ' τη σε πρόγραμμα από το «Προσθήκη άσκησης» του προγράμματος.");
            }
        });
    }

    private void exerciseMenu(final Db.Exercise e, View anchor) {
        final Runnable reload = new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        };
        ui.menu(anchor, new String[]{"Ιστορικό", "Μετονομασία", "Διαγραφή"}, new Ui.OnPick() {
            @Override
            public void run(int which) {
                if (which == 0) act.push(new HistoryScreen(act, e.id));
                else if (which == 1) renameExercise(e, reload);
                else deleteExercise(e, reload);
            }
        });
    }

    private final class ExerciseAdapter extends BaseAdapter {
        private List<Db.Exercise> all = Collections.emptyList();
        private final List<String> keys = new ArrayList<>();
        private final List<Db.Exercise> shown = new ArrayList<>();
        private Map<Long, String> presetNames = Collections.emptyMap();
        private String query = "";
        private String typed = "";

        void set(List<Db.Exercise> exercises, Map<Long, String> names) {
            all = exercises;
            presetNames = names;
            keys.clear();
            for (Db.Exercise e : all) keys.add(Db.key(e.name));
            apply();
        }

        void filter(String q) {
            typed = Db.clean(q);
            query = Db.key(q);
            apply();
        }

        private void apply() {
            shown.clear();
            for (int i = 0; i < all.size(); i++) {
                if (query.isEmpty() || keys.get(i).contains(query)) shown.add(all.get(i));
            }
            notifyDataSetChanged();
            if (all.isEmpty()) {
                exercisesEmpty.setText("Δεν υπάρχουν ασκήσεις ακόμα.\n\nΠάτα + για να προσθέσεις, ή πρόσθεσέ τες "
                        + "κατευθείαν μέσα από ένα πρόγραμμα.");
            } else {
                exercisesEmpty.setText("Καμία άσκηση με «" + typed + "».\nΠάτα + για να τη φτιάξεις.");
            }
            exercisesEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Db.Exercise getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                h = new Holder();
                LinearLayout item = ui.row();
                item.setBackground(ui.ripple(0, 0));
                item.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
                LinearLayout left = ui.column();
                h.name = ui.text(17, TEXT, ui.semibold);
                h.name.setSingleLine(true);
                h.name.setEllipsize(TextUtils.TruncateAt.END);
                h.stats = ui.text(14, SOFT, false);
                h.stats.setPadding(0, ui.dp(5), 0, 0);
                h.presets = ui.text(13, DIM, false);
                h.presets.setSingleLine(true);
                h.presets.setEllipsize(TextUtils.TruncateAt.END);
                h.presets.setPadding(0, ui.dp(3), 0, 0);
                left.addView(h.name);
                left.addView(h.stats);
                left.addView(h.presets);
                item.addView(left, Ui.fill());
                LinearLayout right = ui.column();
                right.setGravity(Gravity.END);
                right.setPadding(ui.dp(12), 0, 0, 0);
                h.recordLabel = ui.text("≈1RM", 12, DIM, false);
                h.recordLabel.setGravity(Gravity.END);
                h.record = ui.figures(20, TEXT, true);
                h.record.setGravity(Gravity.END);
                right.addView(h.recordLabel);
                right.addView(h.record);
                item.addView(right);
                item.setTag(h);
                convertView = item;
            } else {
                h = (Holder) convertView.getTag();
            }
            Db.Exercise e = shown.get(position);
            h.name.setText(e.name);
            h.stats.setText(statsLine(e));
            String in = presetNames.get(e.id);
            h.presets.setText(in == null ? "Σε κανένα πρόγραμμα" : in);
            boolean record = e.bestE1rm > 0;
            h.record.setText(record ? Ui.quiet(Fmt.num1(e.bestE1rm) + " kg") : "");
            h.recordLabel.setVisibility(record ? View.VISIBLE : View.GONE);
            h.record.setVisibility(record ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    private static final class Holder {
        TextView name;
        TextView stats;
        TextView presets;
        TextView recordLabel;
        TextView record;
    }

    static String statsLine(Db.Exercise e) {
        if (e.lastDay == Db.NO_DAY) return "Χωρίς καταγραφές ακόμα";
        return "Τελευταία φορά: " + Fmt.day(e.lastDay, Fmt.today());
    }
}
