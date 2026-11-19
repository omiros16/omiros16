package com.omiros.nfcreader;

final class Hex {
    private static final char[] D = "0123456789ABCDEF".toCharArray();

    /** "04A1B2C3" */
    static String plain(byte[] b) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(D[(x >> 4) & 15]).append(D[x & 15]);
        return s.toString();
    }

    /** "04:A1:B2:C3" (or another separator) */
    static String sep(byte[] b, char sep) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) s.append(sep);
            s.append(D[(b[i] >> 4) & 15]).append(D[b[i] & 15]);
        }
        return s.toString();
    }

    static byte[] parse(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String ascii(byte[] b, int from, int len) {
        StringBuilder s = new StringBuilder(len);
        for (int i = from; i < from + len && i < b.length; i++) {
            int c = b[i] & 0xFF;
            s.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return s.toString();
    }

    static byte[] reversed(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    private Hex() {
    }
}
