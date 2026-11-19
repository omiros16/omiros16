package com.omiros.nfcreader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Past scans, newest first, kept in files/history.json. */
final class History {
    private static final int MAX = 200;
    private final File file;
    final List<Scan> items = new ArrayList<>();

    History(Context c) {
        file = new File(c.getFilesDir(), "history.json");
        try {
            if (file.exists()) {
                JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) items.add(Scan.fromJson(a.getJSONObject(i)));
            }
        } catch (IOException | JSONException e) {
            items.clear();
        }
    }

    void add(Scan s) {
        items.add(0, s);
        while (items.size() > MAX) items.remove(items.size() - 1);
        save();
    }

    void remove(Scan s) {
        items.remove(s);
        save();
    }

    void clear() {
        items.clear();
        save();
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Scan s : items) a.put(s.toJson());
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file);
        } catch (IOException | JSONException ignored) {
        }
    }
}
