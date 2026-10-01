package com.omiros.gymnotes;

import static com.omiros.gymnotes.Theme.*;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
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
    private LinearLayout presetList;
    private TextView addPreset;
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
        tabRow.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(10));
        String[] names = {"Προγράμματα", "Ασκήσεις"};
        for (int i = 0; i < 2; i++) {
            final int which = i;
            tabs[i] = ui.pill(names[i]);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(which);
                }
            });
            LinearLayout.LayoutParams lp = Ui.fill();
            if (i == 0) lp.rightMargin = ui.dp(8);
            tabRow.addView(tabs[i], lp);
        }
        root.addView(tabRow);

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
        for (int i = 0; i < 2; i++) ui.stylePill(tabs[i], i == which);
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
        col.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(28));
        sv.addView(col);

        presetList = ui.column();
        col.addView(presetList);

        addPreset = ui.button("+ Νέο πρόγραμμα", false);
        addPreset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newPreset();
            }
        });
        col.addView(addPreset, ui.spaced(12));

        col.addView(ui.label("Τα δεδομένα σου"));
        LinearLayout export = ui.bigButton(R.drawable.ic_upload, "Export your workout data",
                "Αποθήκευση όλων σε αρχείο (.json)");
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startExport();
            }
        });
        col.addView(export, Ui.matchWrap());
        LinearLayout insert = ui.bigButton(R.drawable.ic_download, "Insert your workout data",
                "Επαναφορά από αρχείο εξαγωγής");
        insert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startImport();
            }
        });
        col.addView(insert, ui.spaced(10));
        return sv;
    }

    private void refreshPresets() {
        presetList.removeAllViews();
        List<Db.Preset> presets = db.presets(Fmt.today());
        if (presets.isEmpty()) {
            TextView empty = ui.text("Δεν έχεις προγράμματα ακόμα.\n\nΦτιάξε έως " + Db.MAX_PRESETS
                    + " (π.χ. ένα για κάθε μέρα προπόνησης) και βάλε μέσα τις ασκήσεις της ημέρας.", 15, SOFT, false);
            empty.setLineSpacing(0, 1.15f);
            empty.setBackground(ui.round(CARD, 16));
            empty.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
            presetList.addView(empty, Ui.matchWrap());
        }
        for (int i = 0; i < presets.size(); i++) {
            final Db.Preset p = presets.get(i);
            final LinearLayout card = ui.row();
            card.setBackground(ui.ripple(CARD, 16));
            card.setPadding(ui.dp(18), ui.dp(16), ui.dp(10), ui.dp(16));
            LinearLayout col = ui.column();
            TextView name = ui.text(p.name, 18, TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            String sub = p.exercises == 0 ? "Χωρίς ασκήσεις ακόμα"
                    : p.exercises == 1 ? "1 άσκηση" : p.exercises + " ασκήσεις";
            TextView subView = ui.text(sub, 13, DIM, false);
            if (p.doneToday > 0) {
                subView.setText(sub + "  ·  σήμερα " + p.doneToday + "/" + p.exercises + " ✓");
                subView.setTextColor(GREEN);
            }
            subView.setPadding(0, ui.dp(3), 0, 0);
            col.addView(subView);
            card.addView(col, Ui.fill());
            card.addView(ui.icon(R.drawable.ic_chevron_right, DIM), ui.size(28, 28));
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.push(new PresetScreen(act, p.id));
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    presetMenu(p, card);
                    return true;
                }
            });
            presetList.addView(card, ui.spaced(i == 0 ? 0 : 10));
        }
        boolean full = presets.size() >= Db.MAX_PRESETS;
        addPreset.setEnabled(!full);
        addPreset.setText(full ? "Έχεις " + Db.MAX_PRESETS + "/" + Db.MAX_PRESETS + " προγράμματα (το μέγιστο)"
                : "+ Νέο πρόγραμμα  (" + presets.size() + "/" + Db.MAX_PRESETS + ")");
        addPreset.setTextColor(full ? DIM : ACCENT_SOFT);
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
        top.setPadding(ui.dp(16), 0, ui.dp(8), ui.dp(4));
        search = ui.field("Αναζήτηση άσκησης");
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
        add.setImageTintList(ColorStateList.valueOf(ACCENT));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newExercise();
            }
        });
        top.addView(add);
        col.addView(top);

        FrameLayout frame = new FrameLayout(act);
        final ListView list = new ListView(act);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setClipToPadding(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(28));
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
        exercisesEmpty = ui.text("", 15, DIM, false);
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
                ui.toast("Προστέθηκε. Βάλ' τη σε πρόγραμμα από το «+ Προσθήκη άσκησης» του προγράμματος.");
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
                LinearLayout wrap = ui.column();
                wrap.setPadding(0, ui.dp(5), 0, ui.dp(5));
                LinearLayout card = ui.column();
                card.setBackground(ui.ripple(CARD, 16));
                card.setDuplicateParentStateEnabled(true);
                card.setPadding(ui.dp(18), ui.dp(14), ui.dp(16), ui.dp(14));
                h.name = ui.text(17, TEXT, true);
                h.stats = ui.text(13, SOFT, false);
                h.stats.setPadding(0, ui.dp(3), 0, 0);
                h.presets = ui.text(13, ACCENT_SOFT, false);
                h.presets.setPadding(0, ui.dp(2), 0, 0);
                card.addView(h.name);
                card.addView(h.stats);
                card.addView(h.presets);
                wrap.addView(card, Ui.matchWrap());
                wrap.setTag(h);
                convertView = wrap;
            } else {
                h = (Holder) convertView.getTag();
            }
            Db.Exercise e = shown.get(position);
            h.name.setText(e.name);
            h.stats.setText(statsLine(e));
            String in = presetNames.get(e.id);
            h.presets.setText(in == null ? "Σε κανένα πρόγραμμα" : in);
            h.presets.setTextColor(in == null ? DIM : ACCENT_SOFT);
            return convertView;
        }
    }

    private static final class Holder {
        TextView name;
        TextView stats;
        TextView presets;
    }

    static String statsLine(Db.Exercise e) {
        if (e.lastDay == Db.NO_DAY) return "Χωρίς καταγραφές ακόμα";
        String s = "Τελευταία: " + Fmt.day(e.lastDay, Fmt.today());
        if (e.bestE1rm > 0) s += "  ·  Ρεκόρ ≈1RM " + Fmt.num1(e.bestE1rm) + " kg";
        return s;
    }
}
