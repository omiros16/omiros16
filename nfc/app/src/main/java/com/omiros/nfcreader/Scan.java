package com.omiros.nfcreader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Everything read from one card. */
final class Scan {
    static final class Section {
        final String title;
        final String body;

        Section(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    long time;
    String uid = "";
    String type = "";
    final List<Section> sections = new ArrayList<>();
    /** Raw NDEF message, if the card held one (used for "write to another tag"). */
    byte[] ndef;

    String fullText() {
        StringBuilder s = new StringBuilder();
        s.append(type).append('\n').append("UID: ").append(uid).append('\n');
        for (Section sec : sections) s.append('\n').append("== ").append(sec.title).append(" ==\n").append(sec.body).append('\n');
        return s.toString().trim();
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("time", time).put("uid", uid).put("type", type);
        if (ndef != null) o.put("ndef", Hex.plain(ndef));
        JSONArray a = new JSONArray();
        for (Section sec : sections) a.put(new JSONArray().put(sec.title).put(sec.body));
        o.put("sections", a);
        return o;
    }

    static Scan fromJson(JSONObject o) throws JSONException {
        Scan s = new Scan();
        s.time = o.getLong("time");
        s.uid = o.getString("uid");
        s.type = o.optString("type");
        String n = o.optString("ndef", "");
        if (!n.isEmpty()) s.ndef = Hex.parse(n);
        JSONArray a = o.getJSONArray("sections");
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.getJSONArray(i);
            s.sections.add(new Section(p.getString(0), p.getString(1)));
        }
        return s;
    }
}
