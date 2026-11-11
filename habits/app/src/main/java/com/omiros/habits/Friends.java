package com.omiros.habits;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tells friends when a locked habit was missed or a pass was spent: an SMS from the phone itself
 * and an email sent through the person's own Gmail account. Emails that fail (no network at
 * midnight, for instance) wait in an outbox and are retried later.
 */
final class Friends {
    static final String SMTP_HOST = "smtp.gmail.com";
    static final int SMTP_PORT = 465;

    private Friends() {
    }

    /** The Gmail login lives in its own preferences file, left out of backups. */
    static SharedPreferences secret(Context c) {
        return c.getSharedPreferences("secret", Context.MODE_PRIVATE);
    }

    static boolean canSms(Context c) {
        return c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canEmail(Context c) {
        SharedPreferences s = secret(c);
        return !s.getString("gmail", "").isEmpty() && !s.getString("password", "").isEmpty();
    }

    @SuppressWarnings("deprecation")
    static void send(Context c, Rules.Doc doc, String subject, String text) {
        if (!doc.phones.isEmpty() && canSms(c)) {
            SmsManager sms = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
            for (String phone : doc.phones) {
                try {
                    sms.sendMultipartTextMessage(phone, null, parts(text), null, null);
                } catch (RuntimeException ignored) {
                    // A bad number, or a phone without SMS, must not stop the others.
                }
            }
        }
        if (!doc.emails.isEmpty() && canEmail(c)) {
            queue(c, new ArrayList<>(doc.emails), subject, text);
            retry(c);
        }
    }

    /** 67 characters fit one part of a multi-part SMS even in Greek (UCS-2), the tightest encoding. */
    static ArrayList<String> parts(String text) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 67) out.add(text.substring(i, Math.min(text.length(), i + 67)));
        return out;
    }

    private static synchronized void queue(Context c, List<String> to, String subject, String text) {
        try {
            JSONArray box = outbox(c);
            box.put(new JSONObject().put("to", new JSONArray(to)).put("subject", subject).put("text", text));
            Engine.state(c).edit().putString("outbox", box.toString()).commit();
        } catch (JSONException ignored) {
        }
    }

    private static JSONArray outbox(Context c) {
        try {
            return new JSONArray(Engine.state(c).getString("outbox", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static boolean sending;

    /** Sends whatever waits in the outbox, off the main thread; what fails stays for next time. */
    static void retry(final Context c) {
        final Context app = c.getApplicationContext();
        synchronized (Friends.class) {
            if (sending || outbox(app).length() == 0 || !canEmail(app)) return;
            sending = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    flush(app);
                } finally {
                    synchronized (Friends.class) {
                        sending = false;
                    }
                }
            }
        }, "friends-mail").start();
    }

    private static void flush(Context c) {
        SharedPreferences s = secret(c);
        String user = s.getString("gmail", "");
        String password = s.getString("password", "").replace(" ", "");
        while (true) {
            JSONObject mail;
            synchronized (Friends.class) {
                JSONArray box = outbox(c);
                if (box.length() == 0) return;
                mail = box.optJSONObject(0);
            }
            if (mail != null) {
                List<String> to = new ArrayList<>();
                JSONArray a = mail.optJSONArray("to");
                for (int i = 0; a != null && i < a.length(); i++) to.add(a.optString(i));
                try {
                    smtp(SMTP_HOST, SMTP_PORT, true, user, password, to, mail.optString("subject"), mail.optString("text"));
                } catch (IOException e) {
                    return;
                }
            }
            synchronized (Friends.class) {
                JSONArray box = outbox(c);
                JSONArray rest = new JSONArray();
                for (int i = 1; i < box.length(); i++) rest.put(box.opt(i));
                Engine.state(c).edit().putString("outbox", rest.toString()).commit();
            }
        }
    }

    /**
     * A minimal SMTP client: AUTH LOGIN over implicit TLS (port 465), one plain-text UTF-8 message.
     * {@code tls} is false only for tests against a local fake server.
     */
    static void smtp(String host, int port, boolean tls, String user, String password, List<String> to,
                     String subject, String text) throws IOException {
        Socket socket;
        if (tls) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
            ssl.startHandshake();
            // SSLSocket does not check the name on the certificate by itself.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new IOException("certificate does not match " + host);
            }
            socket = ssl;
        } else {
            socket = new Socket(host, port);
        }
        try {
            socket.setSoTimeout(20_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            Base64.Encoder b64 = Base64.getEncoder();
            expect(in, 220);
            command(out, in, "EHLO habits", 250);
            command(out, in, "AUTH LOGIN", 334);
            command(out, in, b64.encodeToString(user.getBytes(StandardCharsets.UTF_8)), 334);
            command(out, in, b64.encodeToString(password.getBytes(StandardCharsets.UTF_8)), 235);
            command(out, in, "MAIL FROM:<" + user + ">", 250);
            for (String t : to) command(out, in, "RCPT TO:<" + t + ">", 250);
            command(out, in, "DATA", 354);
            String body = Base64.getMimeEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            String message = "From: <" + user + ">\r\n"
                    + "To: " + join(to) + "\r\n"
                    + "Subject: =?UTF-8?B?" + b64.encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=\r\n"
                    + "MIME-Version: 1.0\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n\r\n"
                    + body + "\r\n.";
            command(out, in, message, 250);
            out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } finally {
            socket.close();
        }
    }

    private static String join(List<String> to) {
        StringBuilder s = new StringBuilder();
        for (String t : to) s.append(s.length() > 0 ? ", " : "").append('<').append(t).append('>');
        return s.toString();
    }

    private static void command(OutputStream out, BufferedReader in, String line, int code) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        expect(in, code);
    }

    /** Reads one reply (possibly several "250-" lines) and checks its code. */
    private static void expect(BufferedReader in, int code) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) throw new IOException("connection closed");
        } while (line.length() > 3 && line.charAt(3) == '-');
        if (!line.startsWith(String.valueOf(code))) throw new IOException("SMTP: " + line);
    }
}
