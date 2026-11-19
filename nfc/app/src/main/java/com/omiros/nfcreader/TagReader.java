package com.omiros.nfcreader;

import static com.omiros.nfcreader.L.t;

import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads everything a phone can read from a tag: UID, chip info, NDEF content and the memory. */
final class TagReader {
    /** Well-known public default keys for MIFARE Classic sectors. */
    private static final String[] CLASSIC_KEYS = {
            "FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000",
            "B0B1B2B3B4B5", "4D3A99C351DD", "1A982C7E459A", "AABBCCDDEEFF",
    };

    static Scan read(Tag tag) {
        Scan s = new Scan();
        s.time = System.currentTimeMillis();
        byte[] id = tag.getId();
        s.uid = Hex.sep(id, ':');

        StringBuilder info = new StringBuilder();
        line(info, "UID (hex)", Hex.plain(id));
        if (id.length > 0 && id.length <= 10) {
            line(info, "UID (dec)", new BigInteger(1, id).toString());
            line(info, t("UID (dec, reversed)", "UID (dec, αντίστροφα)"), new BigInteger(1, Hex.reversed(id)).toString());
        }
        line(info, t("UID length", "Μήκος UID"), id.length + " bytes");
        if (id.length == 4 && (id[0] & 0xFF) == 0x08)
            info.append(t("⚠ Random UID: it changes on every tap.\n", "⚠ Τυχαίο UID: αλλάζει σε κάθε άγγιγμα.\n"));

        StringBuilder techs = new StringBuilder();
        for (String tech : tag.getTechList()) {
            if (techs.length() > 0) techs.append(", ");
            techs.append(tech.substring(tech.lastIndexOf('.') + 1));
        }
        line(info, t("Technologies", "Τεχνολογίες"), techs.toString());

        String type = null;
        NfcA a = NfcA.get(tag);
        if (a != null) {
            line(info, "ATQA", Hex.plain(Hex.reversed(a.getAtqa())));
            line(info, "SAK", String.format("%02X", a.getSak()));
            type = typeFromSak(a.getSak());
        }
        NfcB b = NfcB.get(tag);
        if (b != null) {
            line(info, t("Application data", "Δεδομένα εφαρμογής"), Hex.plain(b.getApplicationData()));
            line(info, t("Protocol info", "Πληροφορίες πρωτοκόλλου"), Hex.plain(b.getProtocolInfo()));
            type = "ISO 14443-B";
        }
        NfcF f = NfcF.get(tag);
        if (f != null) {
            line(info, t("System code", "Κωδικός συστήματος"), Hex.plain(f.getSystemCode()));
            line(info, "PMm", Hex.plain(f.getManufacturer()));
            type = "FeliCa (NFC-F)";
        }
        NfcV v = NfcV.get(tag);
        if (v != null) {
            line(info, "DSFID", String.format("%02X", v.getDsfId()));
            type = "ISO 15693 (NFC-V)";
        }
        IsoDep iso = IsoDep.get(tag);
        if (iso != null) {
            if (iso.getHistoricalBytes() != null) line(info, t("Historical bytes", "Historical bytes"), Hex.plain(iso.getHistoricalBytes()));
            if (iso.getHiLayerResponse() != null) line(info, "Hi-layer response", Hex.plain(iso.getHiLayerResponse()));
        }

        String memory = null;
        String memoryTitle = t("Memory", "Μνήμη");
        MifareUltralight ul = MifareUltralight.get(tag);
        MifareClassic mc = null;
        try {
            mc = MifareClassic.get(tag);
        } catch (RuntimeException ignored) {
            // some phones list the tech but cannot drive it
        }

        // NDEF first: Android caches it from discovery, so it is read even if the card moves away later.
        readNdef(tag, s, info);

        if (ul != null) {
            String[] r = dumpUltralight(ul);
            if (r[0] != null) type = r[0];
            memory = r[1];
            memoryTitle = t("Memory (pages of 4 bytes)", "Μνήμη (σελίδες των 4 bytes)");
        } else if (mc != null) {
            type = classicName(mc);
            memory = dumpClassic(mc);
            memoryTitle = t("Memory (sectors / blocks)", "Μνήμη (τομείς / blocks)");
        } else if (v != null) {
            memory = dumpV(v, id);
            memoryTitle = t("Memory (blocks)", "Μνήμη (blocks)");
        } else if (a != null && (a.getSak() & 0x18) == 0x08) {
            // MIFARE Classic, but this phone's NFC chip cannot speak it (common on non-NXP chips).
            memory = t("This phone's NFC chip cannot read MIFARE Classic sectors.\nThe UID above is still correct.",
                    "Το NFC αυτού του κινητού δεν μπορεί να διαβάσει τους τομείς MIFARE Classic.\nΤο UID παραπάνω είναι σωστό.");
        } else if (iso != null) {
            if (type == null || type.startsWith("ISO 14443")) type = t("Smart card (ISO 14443-4)", "Έξυπνη κάρτα (ISO 14443-4)");
            memory = t("Smart card: its data is protected by the card itself.\nOnly the UID and the info above can be read.",
                    "Έξυπνη κάρτα: τα δεδομένα της προστατεύονται από την ίδια την κάρτα.\nΔιαβάζονται μόνο το UID και οι πληροφορίες παραπάνω.");
        }

        s.type = type != null ? type : t("NFC tag", "Ετικέτα NFC");
        s.sections.add(0, new Scan.Section(t("Card info", "Στοιχεία κάρτας"), info.toString().trim()));
        if (memory != null) s.sections.add(new Scan.Section(memoryTitle, memory.trim()));
        return s;
    }

    // ---------------------------------------------------------------- NDEF

    private static void readNdef(Tag tag, Scan s, StringBuilder info) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return;
        line(info, "NDEF", ndef.getType() + ", " + ndef.getMaxSize() + " bytes, "
                + (ndef.isWritable() ? t("writable", "εγγράψιμο") : t("read-only", "μόνο ανάγνωση")));
        NdefMessage msg = ndef.getCachedNdefMessage();
        if (msg == null) {
            try {
                ndef.connect();
                msg = ndef.getNdefMessage();
            } catch (Exception ignored) {
            } finally {
                close(ndef);
            }
        }
        if (msg == null || msg.getRecords().length == 0) {
            s.sections.add(new Scan.Section(t("Content (NDEF)", "Περιεχόμενο (NDEF)"), t("(empty)", "(κενό)")));
            return;
        }
        s.ndef = msg.toByteArray();
        NdefRecord[] recs = msg.getRecords();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < recs.length; i++) {
            if (recs.length > 1) body.append(i + 1).append(". ");
            body.append(describe(recs[i])).append('\n');
        }
        s.sections.add(new Scan.Section(t("Content (NDEF)", "Περιεχόμενο (NDEF)"), body.toString().trim()));
    }

    private static String describe(NdefRecord r) {
        short tnf = r.getTnf();
        byte[] type = r.getType();
        byte[] p = r.getPayload();
        if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT) && p.length > 0) {
            int status = p[0] & 0xFF;
            int langLen = status & 0x3F;
            if (1 + langLen <= p.length) {
                String lang = new String(p, 1, langLen, StandardCharsets.US_ASCII);
                String text = new String(p, 1 + langLen, p.length - 1 - langLen,
                        (status & 0x80) != 0 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
                return t("Text", "Κείμενο") + (lang.isEmpty() ? "" : " (" + lang + ")") + ":\n" + text;
            }
        }
        Uri uri = null;
        try {
            uri = r.toUri();
        } catch (RuntimeException ignored) {
        }
        if (uri != null) return t("Link", "Σύνδεσμος") + ":\n" + uri;
        String typeStr = new String(type, StandardCharsets.US_ASCII);
        switch (tnf) {
            case NdefRecord.TNF_EMPTY:
                return t("Empty record", "Κενή εγγραφή");
            case NdefRecord.TNF_MIME_MEDIA:
                return t("Data", "Δεδομένα") + " (" + typeStr + "):\n" + textOrHex(p);
            case NdefRecord.TNF_EXTERNAL_TYPE:
                return t("External", "Εξωτερικό") + " (" + typeStr + "):\n" + textOrHex(p);
            default:
                return "TNF " + tnf + (typeStr.isEmpty() ? "" : ", " + typeStr) + ":\n" + textOrHex(p);
        }
    }

    private static String textOrHex(byte[] p) {
        String s = new String(p, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '�' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t'))
                return "HEX " + Hex.sep(p, ' ');
        }
        return s;
    }

    // ---------------------------------------------------------------- memory dumps

    /** @return {chip name or null, dump} */
    private static String[] dumpUltralight(MifareUltralight ul) {
        String name = null;
        int pages = 256;
        StringBuilder out = new StringBuilder();
        try {
            ul.connect();
            try {
                byte[] ver = ul.transceive(new byte[]{0x60}); // GET_VERSION (NTAG / Ultralight EV1)
                if (ver != null && ver.length >= 8) {
                    int size = ver[6] & 0xFF;
                    boolean ntag = (ver[2] & 0xFF) == 0x04;
                    switch (size) {
                        case 0x0B: name = ntag ? "NTAG210" : "MIFARE Ultralight EV1 (MF0UL11)"; pages = 20; break;
                        case 0x0E: name = ntag ? "NTAG212" : "MIFARE Ultralight EV1 (MF0UL21)"; pages = 41; break;
                        case 0x0F: name = ntag ? "NTAG213" : null; pages = 45; break;
                        case 0x11: name = ntag ? "NTAG215" : null; pages = 135; break;
                        case 0x13: name = ntag ? "NTAG216" : null; pages = 231; break;
                        default: break;
                    }
                }
            } catch (IOException e) {
                // older chip without GET_VERSION; it halts after the unknown command, so reconnect
                close(ul);
                ul.connect();
            }
            if (name == null) {
                if (ul.getType() == MifareUltralight.TYPE_ULTRALIGHT_C) {
                    name = "MIFARE Ultralight C";
                    pages = 44; // pages 44-47 hold the secret key and read back as nothing
                } else if (ul.getType() == MifareUltralight.TYPE_ULTRALIGHT && pages == 256) {
                    name = "MIFARE Ultralight";
                    pages = 16;
                } else {
                    name = "MIFARE Ultralight / NTAG";
                }
            }
            for (int p = 0; p < pages; p += 4) {
                byte[] d;
                try {
                    d = ul.readPages(p);
                } catch (IOException e) {
                    if (p > 0 && pages == 256) break; // unknown size: we reached the end
                    out.append(String.format("%03d: ", p)).append(t("read error (protected or card moved)\n", "σφάλμα ανάγνωσης (προστατευμένο ή μετακινήθηκε η κάρτα)\n"));
                    break;
                }
                for (int i = 0; i < 4 && p + i < pages; i++) {
                    out.append(String.format("%03d: ", p + i))
                            .append(Hex.sep(Arrays.copyOfRange(d, 4 * i, 4 * i + 4), ' '))
                            .append("  ").append(Hex.ascii(d, 4 * i, 4)).append('\n');
                }
            }
        } catch (IOException e) {
            out.append(t("Card moved away before the memory was read.", "Η κάρτα απομακρύνθηκε πριν διαβαστεί η μνήμη."));
        } finally {
            close(ul);
        }
        return new String[]{name, out.toString()};
    }

    private static String classicName(MifareClassic mc) {
        switch (mc.getType()) {
            case MifareClassic.TYPE_PLUS: return "MIFARE Plus";
            case MifareClassic.TYPE_PRO: return "MIFARE Classic (Pro)";
            default: break;
        }
        switch (mc.getSize()) {
            case MifareClassic.SIZE_MINI: return "MIFARE Classic Mini";
            case MifareClassic.SIZE_2K: return "MIFARE Classic 2K";
            case MifareClassic.SIZE_4K: return "MIFARE Classic 4K";
            default: return "MIFARE Classic 1K";
        }
    }

    private static String dumpClassic(MifareClassic mc) {
        StringBuilder out = new StringBuilder();
        int readable = 0;
        try {
            mc.connect();
            int sectors = mc.getSectorCount();
            for (int sec = 0; sec < sectors; sec++) {
                String key = null;
                for (int pass = 0; pass < 2 && key == null; pass++) {
                    for (String k : CLASSIC_KEYS) {
                        if (auth(mc, sec, Hex.parse(k), pass == 0)) {
                            key = (pass == 0 ? "A " : "B ") + k;
                            break;
                        }
                        if (!mc.isConnected()) throw new IOException("card gone");
                    }
                }
                out.append(t("Sector ", "Τομέας ")).append(sec);
                if (key == null) {
                    out.append(t(": locked (unknown key)\n", ": κλειδωμένος (άγνωστο κλειδί)\n"));
                    continue;
                }
                readable++;
                out.append(t("  (key ", "  (κλειδί ")).append(key).append(")\n");
                int first = mc.sectorToBlock(sec);
                for (int i = 0; i < mc.getBlockCountInSector(sec); i++) {
                    out.append(String.format("  %03d: ", first + i));
                    try {
                        byte[] d = mc.readBlock(first + i);
                        out.append(Hex.sep(d, ' ')).append("  ").append(Hex.ascii(d, 0, d.length)).append('\n');
                    } catch (IOException e) {
                        out.append(t("read error\n", "σφάλμα ανάγνωσης\n"));
                    }
                }
            }
        } catch (IOException e) {
            out.append(t("\nCard moved away before the whole memory was read.", "\nΗ κάρτα απομακρύνθηκε πριν διαβαστεί όλη η μνήμη."));
        } finally {
            close(mc);
        }
        if (readable == 0 && out.length() > 0)
            out.append(t("\nAll sectors use private keys, so only the UID can be read.",
                    "\nΌλοι οι τομείς έχουν ιδιωτικά κλειδιά, οπότε διαβάζεται μόνο το UID."));
        return out.toString();
    }

    private static boolean auth(MifareClassic mc, int sector, byte[] key, boolean keyA) {
        try {
            boolean ok = keyA ? mc.authenticateSectorWithKeyA(sector, key) : mc.authenticateSectorWithKeyB(sector, key);
            if (ok) return true;
        } catch (IOException ignored) {
        }
        // a failed authentication halts the card: wake it up again for the next try
        close(mc);
        try {
            mc.connect();
        } catch (IOException ignored) {
        }
        return false;
    }

    private static String dumpV(NfcV v, byte[] id) {
        StringBuilder out = new StringBuilder();
        try {
            v.connect();
            int blocks = 256;
            try {
                byte[] r = v.transceive(cmdV(0x2B, id, -1)); // GET SYSTEM INFO
                if (r.length > 10 && r[0] == 0) {
                    int flags = r[1] & 0xFF, i = 10;
                    if ((flags & 1) != 0) i++; // DSFID
                    if ((flags & 2) != 0) i++; // AFI
                    if ((flags & 4) != 0 && i + 1 < r.length) {
                        blocks = (r[i] & 0xFF) + 1;
                        out.append(blocks).append(" blocks × ").append((r[i + 1] & 0x1F) + 1).append(" bytes\n");
                    }
                }
            } catch (IOException ignored) {
            }
            for (int bl = 0; bl < blocks; bl++) {
                byte[] r;
                try {
                    r = v.transceive(cmdV(0x20, id, bl)); // READ SINGLE BLOCK
                } catch (IOException e) {
                    break;
                }
                if (r.length < 2 || r[0] != 0) break;
                byte[] d = Arrays.copyOfRange(r, 1, r.length);
                out.append(String.format("%03d: ", bl)).append(Hex.sep(d, ' ')).append("  ").append(Hex.ascii(d, 0, d.length)).append('\n');
            }
        } catch (IOException e) {
            out.append(t("Card moved away before the memory was read.", "Η κάρτα απομακρύνθηκε πριν διαβαστεί η μνήμη."));
        } finally {
            close(v);
        }
        return out.toString();
    }

    private static byte[] cmdV(int cmd, byte[] id, int block) {
        byte[] c = new byte[block < 0 ? 10 : 11];
        c[0] = 0x22; // addressed, high data rate
        c[1] = (byte) cmd;
        System.arraycopy(id, 0, c, 2, Math.min(8, id.length));
        if (block >= 0) c[10] = (byte) block;
        return c;
    }

    // ---------------------------------------------------------------- helpers

    private static String typeFromSak(int sak) {
        switch (sak) {
            case 0x00: return "MIFARE Ultralight / NTAG";
            case 0x09: return "MIFARE Classic Mini";
            case 0x08: case 0x88: return "MIFARE Classic 1K";
            case 0x18: case 0x38: return "MIFARE Classic 4K";
            case 0x10: case 0x11: return "MIFARE Plus";
            case 0x20: return "ISO 14443-4 (DESFire / smart card)";
            default: return "ISO 14443-A";
        }
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(k).append(": ").append(v).append('\n');
    }

    private static void close(TagTechnology t) {
        try {
            t.close();
        } catch (IOException ignored) {
        }
    }

    private TagReader() {
    }
}
