package com.omiros.gallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One photo or video from MediaStore, plus the queries the screens need. */
final class Media {
    // what a screen shows
    static final int ALL = 0;
    static final int ALBUM = 1;
    static final int FAVORITES = 2;
    static final int TRASH = 3;

    final long id;
    final Uri uri;
    final boolean video;
    final String mime;
    final long date;      // millis: when taken, else when last modified
    final long duration;  // millis, videos only
    final int width;
    final int height;
    final long size;
    final String name;
    final long bucketId;
    final String bucket;
    final String path;
    final long expires;   // seconds, items in the trash only
    boolean favorite;

    private Media(long id, Uri uri, boolean video, String mime, long date, long duration, int width, int height,
                  long size, String name, long bucketId, String bucket, String path, long expires, boolean favorite) {
        this.id = id;
        this.uri = uri;
        this.video = video;
        this.mime = mime;
        this.date = date;
        this.duration = duration;
        this.width = width;
        this.height = height;
        this.size = size;
        this.name = name;
        this.bucketId = bucketId;
        this.bucket = bucket;
        this.path = path;
        this.expires = expires;
        this.favorite = favorite;
    }

    /** A file another app asked us to open (not necessarily in MediaStore). */
    static Media external(Uri uri, String mime) {
        boolean video = mime != null && mime.startsWith("video/");
        return new Media(-1, uri, video, mime, 0, 0, 0, 0, 0, null, 0, null, null, 0, false);
    }

    boolean inMediaStore() {
        return id >= 0;
    }

    private static final String[] COLS = {
            FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.MIME_TYPE, FileColumns.DATE_TAKEN,
            FileColumns.DATE_MODIFIED, FileColumns.DURATION, FileColumns.WIDTH, FileColumns.HEIGHT,
            FileColumns.ORIENTATION, FileColumns.SIZE, FileColumns.DISPLAY_NAME, FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME, FileColumns.RELATIVE_PATH, FileColumns.DATE_EXPIRES,
            FileColumns.IS_FAVORITE,
    };

    static final Comparator<Media> NEWEST_FIRST = new Comparator<Media>() {
        @Override
        public int compare(Media a, Media b) {
            if (a.date != b.date) return a.date > b.date ? -1 : 1;
            return Long.compare(b.id, a.id);
        }
    };

    /** Every photo and video the app may see, newest first: either the trash or everything else. */
    static List<Media> load(ContentResolver cr, boolean trash) {
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, FileColumns.MEDIA_TYPE + " IN ("
                + FileColumns.MEDIA_TYPE_IMAGE + "," + FileColumns.MEDIA_TYPE_VIDEO + ")");
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, trash ? MediaStore.MATCH_ONLY : MediaStore.MATCH_EXCLUDE);
        List<Media> out = new ArrayList<>();
        Uri images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = cr.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), COLS, args, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                boolean video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO;
                long date = c.getLong(3);
                if (date <= 0) date = c.getLong(4) * 1000;
                int w = c.getInt(6);
                int h = c.getInt(7);
                int orientation = c.getInt(8);
                if (orientation == 90 || orientation == 270) {
                    int t = w;
                    w = h;
                    h = t;
                }
                out.add(new Media(id, ContentUris.withAppendedId(video ? videos : images, id), video, c.getString(2),
                        date, c.getLong(5), w, h, c.getLong(9), c.getString(10), c.getLong(11),
                        albumName(c.getString(12)), c.getString(13), c.getLong(14), c.getInt(15) != 0));
            }
        }
        Collections.sort(out, NEWEST_FIRST);
        return out;
    }

    static List<Media> filter(List<Media> all, int mode, long bucket) {
        if (mode == ALL || mode == TRASH) return all;
        List<Media> out = new ArrayList<>();
        for (Media m : all) {
            if (mode == ALBUM ? m.bucketId == bucket : m.favorite) out.add(m);
        }
        return out;
    }

    static String albumName(String bucket) {
        if (bucket == null) return "Χωρίς φάκελο";
        switch (bucket) {
            case "Camera": return "Κάμερα";
            case "Screenshots": return "Στιγμιότυπα οθόνης";
            case "Screen recordings": return "Εγγραφές οθόνης";
            case "Download": return "Λήψεις";
            case "Pictures": return "Εικόνες";
            case "Movies": return "Ταινίες";
            default: return bucket;
        }
    }

    static final class Album {
        final long id;
        final String name;
        final Media cover;
        int count;

        Album(long id, String name, Media cover) {
            this.id = id;
            this.name = name;
            this.cover = cover;
        }
    }

    /** One album per folder, the camera first, then the most recently updated. */
    static List<Album> albums(List<Media> all) {
        Map<Long, Album> map = new LinkedHashMap<>(); // newest-first input keeps folders in recency order
        for (Media m : all) {
            Album a = map.get(m.bucketId);
            if (a == null) map.put(m.bucketId, a = new Album(m.bucketId, m.bucket, m));
            a.count++;
        }
        List<Album> out = new ArrayList<>(map.values());
        for (int i = 0; i < out.size(); i++) {
            if ("DCIM/Camera/".equals(out.get(i).cover.path)) {
                out.add(0, out.remove(i));
                break;
            }
        }
        return out;
    }
}
