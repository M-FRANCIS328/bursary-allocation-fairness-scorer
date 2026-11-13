import java.io.BufferedReader;                                                           // reads the mail server's replies
import java.io.IOException;                                                              // network and file failures
import java.io.InputStream;                                                              // reads the settings file
import java.io.InputStreamReader;                                                        // bytes to text
import java.io.Writer;                                                                   // sends commands to the mail server
import java.io.OutputStreamWriter;                                                       // text to bytes
import java.net.Socket;                                                                  // the connection to the mail server
import java.nio.charset.StandardCharsets;                                                // UTF-8 everywhere
import java.nio.file.Files;                                                              // reads settings, writes the outbox
import java.nio.file.Path;                                                               // file locations
import java.nio.file.StandardOpenOption;                                                 // create/append options
import java.util.Base64;                                                                 // encodes the mail login
import java.util.Properties;                                                             // key=value settings
import javax.net.ssl.SSLSocketFactory;                                                   // encrypted connection

// Sends emails (password reset codes) through the mail account configured in mail.properties
public class Mail {                                                                      // everything about sending email
    static final Properties cfg = new Properties();                                      // host, port, user, password, from

    static void load() throws IOException {                                              // reads mail.properties at start-up
        Path p = Path.of("mail.properties");                                             // the settings file
        if (Files.exists(p)) try (InputStream in = Files.newInputStream(p)) { cfg.load(in); } // read it when present
        if (!ready()) System.out.println("Email is NOT configured: reset codes go to outbox.txt. Fill in mail.properties to send real emails."); // warn
    }

    static boolean ready() {                                                             // true when every setting is filled in
        return !cfg.getProperty("host", "").isBlank() && !cfg.getProperty("user", "").isBlank() && !cfg.getProperty("password", "").isBlank(); // all three
    }

    static void send(String to, String subject, String text) {                           // sends in the background so the page never waits
        Thread.startVirtualThread(() -> {                                                // its own lightweight thread
            try {                                                                        // any failure is reported below
                if (ready()) smtp(to, subject, text);                                    // configured: send a real email
                else outbox(to, subject, text);                                          // not configured: keep it in a local file for testing
            } catch (IOException | RuntimeException e) {                                 // mail server unreachable or refused us
                System.err.println("Email to " + to + " failed: " + e.getMessage());     // say why in the server window
            }
        });
    }

    static void outbox(String to, String subject, String text) throws IOException {      // test mode: write the email to outbox.txt
        Files.writeString(Path.of("outbox.txt"), "TO: " + to + "\nSUBJECT: " + subject + "\n" + text + "\n----\n", // the whole message
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);                       // added at the end of the file
    }

    static void smtp(String to, String subject, String text) throws IOException {        // talks to the mail server over an encrypted line
        String host = cfg.getProperty("host"), user = cfg.getProperty("user");           // server and mail account
        String from = cfg.getProperty("from", user);                                     // sender address, the account itself by default
        try (Socket s = SSLSocketFactory.getDefault().createSocket(host, Integer.parseInt(cfg.getProperty("port", "465")))) { // connect securely
            s.setSoTimeout(15_000);                                                      // give up after 15 seconds of silence
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)); // server -> us
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8); // us -> server
            say(in, out, null, "220");                                                   // wait for the greeting
            say(in, out, "EHLO kiu-bursary", "250");                                     // introduce ourselves
            say(in, out, "AUTH LOGIN", "334");                                           // ask to log in
            say(in, out, b64(user), "334");                                              // account name
            say(in, out, b64(cfg.getProperty("password")), "235");                       // account password
            say(in, out, "MAIL FROM:<" + from + ">", "250");                             // who the email is from
            say(in, out, "RCPT TO:<" + to + ">", "250");                                 // who it goes to
            say(in, out, "DATA", "354");                                                 // start of the message
            say(in, out, "From: KIU Bursary Office <" + from + ">\r\nTo: <" + to + ">\r\nSubject: " + subject // headers
                + "\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + text.replace("\n", "\r\n") + "\r\n.", "250"); // body, ended by a lone dot
            say(in, out, "QUIT", "221");                                                 // goodbye
        }
    }

    static void say(BufferedReader in, Writer out, String command, String expect) throws IOException { // sends one command and checks the reply
        if (command != null) { out.write(command + "\r\n"); out.flush(); }               // send it (nothing to send for the greeting)
        String line;                                                                     // one line of the reply
        do { line = in.readLine(); } while (line != null && line.length() > 3 && line.charAt(3) == '-'); // skip to the last line of the reply
        if (line == null || !line.startsWith(expect)) throw new IOException("mail server said: " + line); // not the expected answer
    }

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); } // login text encoding
}
