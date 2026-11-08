package com.omiros.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Share, favourite, trash, restore, delete and info: the same actions from the grid and the viewer. */
final class Actions {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Sections.GREEK);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Sections.GREEK);

    private Actions() {
    }

    static void share(Activity a, List<Media> items) {
        if (items.isEmpty()) return;
        boolean images = true;
        boolean videos = true;
        ArrayList<Uri> uris = new ArrayList<>();
        for (Media m : items) {
            uris.add(m.uri);
            if (m.video) images = false;
            else videos = false;
        }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
            String mime = items.get(0).mime;
            i.setType(mime != null ? mime : a.getContentResolver().getType(uris.get(0)));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.setType(images ? "image/*" : videos ? "video/*" : "*/*");
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, null));
    }

    // Android asks the user to confirm each of these; the answer comes back in onActivityResult.

    static void trash(Activity a, int req, List<Media> items, boolean intoTrash) {
        send(a, req, MediaStore.createTrashRequest(a.getContentResolver(), uris(items), intoTrash));
    }

    static void favorite(Activity a, int req, List<Media> items, boolean value) {
        send(a, req, MediaStore.createFavoriteRequest(a.getContentResolver(), uris(items), value));
    }

    static void deleteForever(Activity a, int req, List<Media> items) {
        send(a, req, MediaStore.createDeleteRequest(a.getContentResolver(), uris(items)));
    }

    private static List<Uri> uris(List<Media> items) {
        List<Uri> out = new ArrayList<>();
        for (Media m : items) out.add(m.uri);
        return out;
    }

    private static void send(Activity a, int req, PendingIntent pi) {
        try {
            a.startIntentSenderForResult(pi.getIntentSender(), req, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            Toast.makeText(a, "Δεν έγινε: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void info(Activity a, Media m) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Theme.dp(a, 24);
        box.setPadding(p, Theme.dp(a, 8), p, 0);
        row(box, "Όνομα", m.name);
        if (m.date > 0) row(box, m.video ? "Τραβήχτηκε" : "Ημερομηνία", FULL.format(Instant.ofEpochMilli(m.date).atZone(ZoneId.systemDefault())));
        if (m.width > 0 && m.height > 0) {
            String mp = m.video ? "" : String.format(Locale.US, "  ·  %.1f MP", m.width * (double) m.height / 1e6);
            row(box, "Διαστάσεις", m.width + " × " + m.height + mp);
        }
        if (m.video) row(box, "Διάρκεια", duration(m.duration));
        if (m.size > 0) row(box, "Μέγεθος", size(m.size));
        row(box, "Φάκελος", m.path);
        if (m.expires > 0) row(box, "Στον κάδο", "Σβήνεται οριστικά σε " + daysLeft(m.expires) + " ημέρες");
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        new AlertDialog.Builder(a, Theme.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Πληροφορίες")
                .setView(scroll)
                .setPositiveButton("Οκ", null)
                .show();
    }

    private static void row(LinearLayout box, String label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView l = new TextView(box.getContext());
        l.setText(label);
        l.setTextColor(Theme.DIM);
        l.setTextSize(13);
        l.setPadding(0, Theme.dp(box.getContext(), 12), 0, 0);
        TextView v = new TextView(box.getContext());
        v.setText(value);
        v.setTextColor(Theme.TEXT);
        v.setTextSize(16);
        v.setTextIsSelectable(true);
        box.addView(l);
        box.addView(v);
    }

    static String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String fullDate(long millis) {
        return FULL.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String duration(long millis) {
        long s = (millis + 500) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Sections.GREEK, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Sections.GREEK, "%.1f MB", bytes / (double) (1L << 20));
        return Math.max(1, bytes >> 10) + " KB";
    }

    /** Whole days until a trashed item is deleted for good (at least 0). */
    static long daysLeft(long expiresSeconds) {
        long left = expiresSeconds - System.currentTimeMillis() / 1000;
        return Math.max(0, (left + 86399) / 86400);
    }
}
