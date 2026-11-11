package com.omiros.habits;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FriendsTest extends Base {
    /** A one-connection fake SMTP server; {@code passwordReply} answers the password line. */
    static List<String> serve(ServerSocket server, String passwordReply) {
        return serve(server, passwordReply, "250 ok");
    }

    static List<String> serve(ServerSocket server, String passwordReply, String rcptReply) {
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = s.getOutputStream();
                out.write("220 fake\r\n".getBytes());
                int auth = 0;
                boolean data = false;
                String line;
                while ((line = in.readLine()) != null) {
                    got.add(line);
                    String reply;
                    if (data) {
                        if (!line.equals(".")) continue;
                        data = false;
                        reply = "250 queued";
                    } else if (line.startsWith("EHLO")) {
                        reply = "250-fake\r\n250 AUTH LOGIN";
                    } else if (line.equals("AUTH LOGIN")) {
                        auth = 1;
                        reply = "334 VXNlcm5hbWU6";
                    } else if (auth == 1) {
                        auth = 2;
                        reply = "334 UGFzc3dvcmQ6";
                    } else if (auth == 2) {
                        auth = 3;
                        reply = passwordReply;
                    } else if (line.equals("DATA")) {
                        data = true;
                        reply = "354 go on";
                    } else if (line.startsWith("RCPT")) {
                        reply = rcptReply;
                    } else if (line.equals("QUIT")) {
                        out.write("221 bye\r\n".getBytes());
                        break;
                    } else {
                        reply = "250 ok";
                    }
                    out.write((reply + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        });
        t.start();
        return got;
    }

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void sendsOneUtf8MessageToEveryFriend() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        List<String> got = serve(server, "235 ok");
        Friends.smtp("127.0.0.1", server.getLocalPort(), false, "me@gmail.com", "abcdabcdabcdabcd",
                Arrays.asList("a@example.gr", "b@example.gr"), "Habits: πάσο", "Χρησιμοποίησα πάσο σήμερα.");
        Thread.sleep(200);
        assertTrue(got.contains(b64("me@gmail.com")));
        assertTrue(got.contains(b64("abcdabcdabcdabcd")));
        assertTrue(got.contains("MAIL FROM:<me@gmail.com>"));
        assertTrue(got.contains("RCPT TO:<a@example.gr>"));
        assertTrue(got.contains("RCPT TO:<b@example.gr>"));
        assertTrue(got.contains("Subject: =?UTF-8?B?" + b64("Habits: πάσο") + "?="));
        assertTrue(got.contains("To: <a@example.gr>, <b@example.gr>"));
        int blank = got.indexOf("");
        StringBuilder body = new StringBuilder();
        for (int i = blank + 1; i < got.size() && !got.get(i).equals("."); i++) body.append(got.get(i));
        assertEquals("Χρησιμοποίησα πάσο σήμερα.", new String(Base64.getMimeDecoder().decode(body.toString()), StandardCharsets.UTF_8));
        server.close();
    }

    @Test
    public void wrongPasswordIsAnError() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        serve(server, "535 5.7.8 Username and Password not accepted");
        try {
            Friends.smtp("127.0.0.1", server.getLocalPort(), false, "me@gmail.com", "wrong",
                    Collections.singletonList("a@example.gr"), "s", "t");
            fail("expected an error");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("535"));
            // A login problem keeps the mail waiting; only a refusal of the mail itself drops it.
            assertFalse(e instanceof Friends.Refused);
        }
        server.close();
    }

    @Test
    public void aRefusedAddressIsFinalNotRetriedForever() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        serve(server, "235 ok", "553 5.1.3 The recipient address is not a valid RFC 5321 address");
        try {
            Friends.smtp("127.0.0.1", server.getLocalPort(), false, "me@gmail.com", "abcd",
                    Collections.singletonList("nikos..p@gmail.com"), "s", "t");
            fail("expected a refusal");
        } catch (Friends.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains("553"));
        }
        server.close();
    }

    @Test
    public void anEmojiIsNeverCutInHalf() {
        String text = new String(new char[66]).replace('\0', 'x') + "💪 ok";
        List<String> parts = Friends.parts(text);
        assertEquals(66, parts.get(0).length());
        assertEquals("💪 ok", parts.get(1));
        assertEquals(text, String.join("", parts));
    }

    @Test
    public void greekSmsSplitsIntoSafeParts() {
        String text = "Habits: Σάββατο 17/10 δεν έκανα: Διάβασμα 20' (ως 20:00), Γυμναστική (ως 21:00).";
        List<String> parts = Friends.parts(text);
        assertEquals(2, parts.size());
        assertEquals(67, parts.get(0).length());
        assertEquals(text, String.join("", parts));
    }

    @Test
    public void emailsWaitInTheOutboxWithoutALogin() throws Exception {
        Rules.Doc d = new Rules.Doc();
        d.emails.add("a@example.gr");
        Friends.send(ctx(), d, "s", "t");
        assertEquals("[]", Engine.state(ctx()).getString("outbox", "[]"));
    }
}
