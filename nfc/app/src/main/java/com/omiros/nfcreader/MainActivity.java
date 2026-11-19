package com.omiros.nfcreader;

import static com.omiros.nfcreader.L.t;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    static final int BG = 0xFF0F1115;
    static final int CARD = 0xFF161920;
    static final int CARD_HI = 0xFF1B2638;
    static final int TEXT = 0xFFEEF1F6;
    static final int DIM = 0xFF7D8594;
    static final int ACCENT = 0xFF5B9DFF;
    static final int ACCENT_SOFT = 0xFF9CC3FF;
    static final int ACCENT_BG = 0xFF1C2E4A;
    static final int GREEN = 0xFF3DD68C;
    static final int RED = 0xFFF06A6A;

    private NfcAdapter nfc;
    private History history;
    private Scan current;
    /** NDEF message waiting to be written to the next tag that is tapped. */
    private byte[] pendingWrite;
    private boolean showingHistory;

    private TextView title;
    private TextView topButton;
    private TextView backButton;
    private LinearLayout content;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        nfc = NfcAdapter.getDefaultAdapter(this);
        history = new History(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(8), dp(10));
        backButton = textButton("‹", v -> onBackPressed());
        backButton.setTextSize(28);
        backButton.setPadding(dp(12), 0, dp(12), dp(4));
        bar.addView(backButton);
        title = new TextView(this);
        title.setTextColor(TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        topButton = textButton("", null);
        bar.addView(topButton);
        root.addView(bar);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc != null && nfc.isEnabled()) {
            Bundle opts = new Bundle();
            opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
            nfc.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                    | NfcAdapter.FLAG_READER_NFC_BARCODE, opts);
        }
        render(); // NFC may have been switched on/off in Settings meanwhile
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) {
            try {
                nfc.disableReaderMode(this);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (showingHistory) {
            showingHistory = false;
            render();
        } else if (pendingWrite != null) {
            pendingWrite = null;
            render();
        } else if (current != null) {
            current = null;
            render();
        } else {
            super.onBackPressed();
        }
    }

    /** Called on a background thread when a tag comes near the phone. */
    @Override
    public void onTagDiscovered(Tag tag) {
        byte[] toWrite = pendingWrite;
        if (toWrite != null) {
            String err = write(tag, toWrite);
            runOnUiThread(() -> {
                if (err == null) {
                    pendingWrite = null;
                    toast(t("Written to the tag ✓", "Γράφτηκε στην ετικέτα ✓"));
                    render();
                } else {
                    toast(err);
                }
            });
            return;
        }
        Scan s = TagReader.read(tag);
        runOnUiThread(() -> {
            history.add(s);
            current = s;
            showingHistory = false;
            render();
            scroll.scrollTo(0, 0);
        });
    }

    private static String write(Tag tag, byte[] data) {
        NdefMessage msg;
        try {
            msg = new NdefMessage(data);
        } catch (Exception e) {
            return t("Nothing to write", "Δεν υπάρχει κάτι για εγγραφή");
        }
        Ndef ndef = Ndef.get(tag);
        try {
            if (ndef != null) {
                ndef.connect();
                if (!ndef.isWritable()) return t("This tag is read-only", "Η ετικέτα είναι μόνο για ανάγνωση");
                if (ndef.getMaxSize() < msg.getByteArrayLength())
                    return t("This tag is too small (", "Η ετικέτα είναι πολύ μικρή (") + ndef.getMaxSize() + " < " + msg.getByteArrayLength() + " bytes)";
                ndef.writeNdefMessage(msg);
                return null;
            }
            NdefFormatable f = NdefFormatable.get(tag);
            if (f != null) {
                f.connect();
                f.format(msg);
                f.close();
                return null;
            }
            return t("This tag cannot store NDEF data", "Αυτή η ετικέτα δεν αποθηκεύει δεδομένα NDEF");
        } catch (Exception e) {
            return t("Write failed – keep the tag still and try again", "Η εγγραφή απέτυχε – κράτα την ετικέτα σταθερή και ξαναδοκίμασε");
        } finally {
            if (ndef != null) {
                try {
                    ndef.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- screens

    private void render() {
        content.removeAllViews();
        if (showingHistory) {
            renderHistory();
            return;
        }
        title.setText(current == null ? getString(R.string.app_name) : current.type);
        backButton.setVisibility(current != null || pendingWrite != null ? View.VISIBLE : View.GONE);
        topButton.setText(t("History", "Ιστορικό"));
        topButton.setOnClickListener(v -> {
            showingHistory = true;
            render();
            scroll.scrollTo(0, 0);
        });
        topButton.setVisibility(View.VISIBLE);

        if (nfc == null) {
            emptyState(t("This phone has no NFC", "Αυτό το κινητό δεν έχει NFC"),
                    t("NFC cards can only be read on phones with an NFC chip.", "Οι κάρτες NFC διαβάζονται μόνο από κινητά με NFC."));
            return;
        }
        if (!nfc.isEnabled()) {
            emptyState(t("NFC is off", "Το NFC είναι κλειστό"),
                    t("Turn on NFC to read cards.", "Άνοιξε το NFC για να διαβάζεις κάρτες."));
            View b = button(t("Turn on NFC", "Άνοιγμα NFC"), true, v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(20);
            content.addView(b, lp);
            return;
        }
        if (pendingWrite != null) {
            title.setText(t("Write to tag", "Εγγραφή σε ετικέτα"));
            emptyState(t("Hold the blank tag near the phone", "Φέρε την κενή ετικέτα κοντά στο κινητό"),
                    t("The content will be copied onto it.\nPress back to cancel.", "Το περιεχόμενο θα αντιγραφεί πάνω της.\nΠάτα πίσω για ακύρωση."));
            return;
        }
        if (current == null) {
            emptyState(t("Hold a card near the phone", "Φέρε μια κάρτα κοντά στο κινητό"),
                    t("Touch the card to the back of the phone, near the camera, and keep it still for a second.",
                            "Ακούμπησε την κάρτα στο πίσω μέρος του κινητού, κοντά στην κάμερα, και κράτα τη σταθερή για ένα δευτερόλεπτο."));
            return;
        }
        renderScan(current);
    }

    private void emptyState(String head, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(16), dp(72), dp(16), 0);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_nfc);
        box.addView(img, new LinearLayout.LayoutParams(dp(140), dp(140)));
        TextView h = text(head, 20, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(24), 0, dp(8));
        box.addView(h);
        TextView s = text(sub, 15, DIM);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.2f);
        box.addView(s);
        content.addView(box);
    }

    private void renderScan(Scan s) {
        // UID card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(round(CARD_HI, 18));
        TextView label = text("UID", 13, ACCENT_SOFT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.1f);
        card.addView(label);
        TextView uid = text(s.uid.isEmpty() ? "—" : s.uid, 24, TEXT);
        uid.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        uid.setTextIsSelectable(true);
        uid.setPadding(0, dp(6), 0, dp(4));
        uid.setOnClickListener(v -> copy(s.uid.replace(":", ""), "UID"));
        card.addView(uid);
        card.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(s.time)), 13, DIM));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(14), 0, 0);
        row.addView(button(t("Copy UID", "Αντιγραφή UID"), true, v -> copy(s.uid.replace(":", ""), "UID")), weight());
        row.addView(space(10));
        row.addView(button(t("Copy all", "Αντιγραφή όλων"), false, v -> copy(s.fullText(), t("All data", "Όλα τα δεδομένα"))), weight());
        card.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(10), 0, 0);
        row2.addView(button(t("Share", "Κοινοποίηση"), false, v -> share(s)), weight());
        if (s.ndef != null) {
            row2.addView(space(10));
            row2.addView(button(t("Write to tag", "Εγγραφή σε ετικέτα"), false, v -> {
                pendingWrite = s.ndef;
                render();
            }), weight());
        }
        card.addView(row2);
        content.addView(card);

        for (Scan.Section sec : s.sections) addSection(sec);

        TextView hint = text(t("Hold another card near the phone to read it.", "Φέρε άλλη κάρτα κοντά στο κινητό για να τη διαβάσεις."), 13, DIM);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(20), 0, 0);
        content.addView(hint);
    }

    private void addSection(Scan.Section sec) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(22), 0, dp(8));
        TextView h = text(sec.title.toUpperCase(), 13, ACCENT_SOFT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setLetterSpacing(0.06f);
        head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(textButton(t("Copy", "Αντιγραφή"), v -> copy(sec.body, sec.title)));
        content.addView(head);

        TextView body = text(sec.body, 13, TEXT);
        body.setTypeface(Typeface.MONOSPACE);
        body.setLineSpacing(0, 1.15f);
        body.setTextIsSelectable(true);
        body.setPadding(dp(14), dp(12), dp(14), dp(12));
        body.setBackground(round(CARD, 14));
        if (sec.body.length() > 400) {
            // long memory dumps scroll sideways instead of wrapping
            body.setHorizontallyScrolling(true);
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setBackground(round(CARD, 14));
            body.setBackground(null);
            hs.addView(body);
            content.addView(hs);
        } else {
            content.addView(body);
        }
    }

    private void renderHistory() {
        title.setText(t("History", "Ιστορικό"));
        backButton.setVisibility(View.VISIBLE);
        topButton.setText(t("Clear", "Διαγραφή"));
        topButton.setVisibility(history.items.isEmpty() ? View.GONE : View.VISIBLE);
        topButton.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(t("Delete all scans?", "Διαγραφή όλων των σαρώσεων;"))
                .setPositiveButton(t("Delete", "Διαγραφή"), (d, w) -> {
                    history.clear();
                    render();
                })
                .setNegativeButton(t("Cancel", "Άκυρο"), null)
                .show());

        if (history.items.isEmpty()) {
            emptyState(t("No scans yet", "Καμία σάρωση ακόμα"), t("Cards you read appear here.", "Οι κάρτες που διαβάζεις εμφανίζονται εδώ."));
            return;
        }
        TextView tip = text(t("Tap to open · long-press to delete", "Πάτα για άνοιγμα · κράτα πατημένο για διαγραφή"), 13, DIM);
        tip.setPadding(dp(4), 0, 0, dp(10));
        content.addView(tip);
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (Scan s : history.items) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(16), dp(12), dp(16), dp(12));
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(CARD, 14), null));
            TextView u = text(s.uid, 16, TEXT);
            u.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            u.setSingleLine(true);
            u.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(u);
            item.addView(text(s.type + " · " + fmt.format(new Date(s.time)), 13, DIM));
            item.setOnClickListener(v -> {
                current = s;
                showingHistory = false;
                render();
                scroll.scrollTo(0, 0);
            });
            item.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage(t("Delete this scan?", "Διαγραφή αυτής της σάρωσης;"))
                        .setPositiveButton(t("Delete", "Διαγραφή"), (d, w) -> {
                            history.remove(s);
                            if (current == s) current = null;
                            render();
                        })
                        .setNegativeButton(t("Cancel", "Άκυρο"), null)
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            content.addView(item, lp);
        }
    }

    // ---------------------------------------------------------------- actions

    private void copy(String text, String what) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(what, text));
        // Android 13+ shows its own "copied" popup
        if (Build.VERSION.SDK_INT < 33) toast(t("Copied: ", "Αντιγράφηκε: ") + what);
    }

    private void share(Scan s) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "NFC " + s.uid);
        i.putExtra(Intent.EXTRA_TEXT, s.fullText());
        startActivity(Intent.createChooser(i, t("Share", "Κοινοποίηση")));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- view helpers

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    private TextView textButton(String s, View.OnClickListener l) {
        TextView v = text(s, 15, ACCENT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0xFFFFFFFF)));
        v.setOnClickListener(l);
        return v;
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView v = text(s, 15, primary ? BG : ACCENT_SOFT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(dp(16), dp(12), dp(16), dp(12));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(primary ? ACCENT : ACCENT_BG, 12), null));
        v.setOnClickListener(l);
        return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private View space(int widthDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return v;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
