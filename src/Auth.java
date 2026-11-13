import com.sun.net.httpserver.HttpExchange;                                              // gives access to the browser's cookies
import java.io.IOException;                                                              // file failures
import java.nio.charset.StandardCharsets;                                                // UTF-8 bytes for comparisons
import java.nio.file.Files;                                                              // reading and writing the account files
import java.nio.file.Path;                                                               // file location
import java.nio.file.StandardOpenOption;                                                 // create/append options
import java.security.GeneralSecurityException;                                           // hashing failures
import java.security.MessageDigest;                                                      // safe comparison of hashes and codes
import java.security.SecureRandom;                                                       // unpredictable salts, tokens and reset codes
import java.util.Base64;                                                                 // bytes to text
import java.util.List;                                                                   // list type
import java.util.Map;                                                                    // map type
import java.util.Set;                                                                    // set type
import java.util.concurrent.ConcurrentHashMap;                                           // maps many requests can use at once
import java.util.concurrent.ConcurrentSkipListMap;                                       // sorted map many requests can use at once
import java.util.stream.Stream;                                                          // reads a file line by line
import javax.crypto.SecretKeyFactory;                                                    // password hashing engine
import javax.crypto.spec.PBEKeySpec;                                                     // password hashing settings

// Accounts and logins: email + password for students and administrators, staff IDs, and password reset codes
public class Auth {                                                                      // everything about accounts and signing in
    public record User(String email, String name, String role, String salt, String hash, String staffId) {} // role is "admin" or "applicant"
    record Session(String key, long expires) {}                                          // which account is signed in and until when
    record Reset(String code, long expires, int tries) {}                                // a reset code, its deadline and wrong guesses so far

    static final Path FILE = Path.of("users.csv");                                       // where accounts are stored
    static final Map<String, User> users = new ConcurrentHashMap<>();                    // accounts by role and email, so one email can be both student and staff
    static final Map<String, User> admins = new ConcurrentSkipListMap<>();               // administrators only, in email order
    static final Map<String, User> finance = new ConcurrentSkipListMap<>();              // Finance office staff only, in email order
    static final Map<String, Session> sessions = new ConcurrentHashMap<>();              // login token -> session
    static final Map<String, Reset> resets = new ConcurrentHashMap<>();                  // email -> pending reset code
    static final Set<String> usedIds = ConcurrentHashMap.newKeySet();                    // staff IDs that already have an account
    static final SecureRandom rnd = new SecureRandom();                                  // source of randomness
    static volatile String systemEmail = "";                                             // email of the system administrator, who creates the others
    static final long LIFE = 12 * 60 * 60 * 1000L, CODE_LIFE = 10 * 60 * 1000L;          // a login lasts 12 hours, a reset code 10 minutes

    static void load(String adminEmail, String adminPass) throws IOException {           // reads accounts at start-up
        if (Files.exists(FILE)) try (Stream<String> lines = Files.lines(FILE)) {         // one line at a time
            lines.map(l -> l.split(",")).filter(c -> c.length == 5 || c.length == 6)     // email,name,role,salt,hash[,staffId]; skip damaged lines
                .forEach(c -> keep(new User(c[0], c[1], c[2], c[3], c[4], c.length == 6 ? c[5] : ""))); // later lines replace earlier ones
        }
        systemEmail = adminEmail;                                                        // this account is the system administrator
        if (admins.isEmpty()) {                                                          // first run: no administrator yet
            add(adminEmail, "Bursary Office", "admin", adminPass);                       // create the first administrator
            System.out.println("Administrator account created: " + adminEmail);          // tell the operator which email to use
        }
    }

    static boolean isSystem(User u) { return u != null && u.role().equals("admin") && u.email().equals(systemEmail); } // is this the system administrator?

    static String key(String role, String email) { return role + ":" + email; }          // how an account is looked up

    static void keep(User u) {                                                           // puts an account in memory
        users.put(key(u.role(), u.email()), u);                                          // findable by role and email
        if (u.role().equals("admin")) admins.put(u.email(), u);                          // administrators are also listed separately
        if (u.role().equals("finance")) finance.put(u.email(), u);                       // and so are Finance office staff
        if (!u.staffId().isEmpty()) usedIds.add(u.staffId());                            // its staff ID cannot be used again
    }

    static void write(User u) throws IOException {                                       // appends one account line to users.csv
        Files.writeString(FILE, String.join(",", u.email(), u.name(), u.role(), u.salt(), u.hash(), u.staffId()) + "\n", // the six columns
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);                       // added at the end of the file
    }

    static String random(int bytes) {                                                    // random text of the given strength
        byte[] b = new byte[bytes];                                                      // empty buffer
        rnd.nextBytes(b);                                                                // fill with random bytes
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);                // text safe for cookies and CSV
    }

    static String hash(String pass, String salt) {                                       // slow one-way hash, passwords are never stored
        try {                                                                            // hashing can only fail on a broken JDK
            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), 210_000, 256); // PBKDF2 settings
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); // do the hashing
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);            // hash as text
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }     // report as a server fault
    }

    static boolean add(String email, String name, String role, String pass) throws IOException { // creates an account
        if (users.containsKey(key(role, email))) return false;                           // email already has this kind of account: skip the slow hashing
        String salt = random(16), hash = hash(pass, salt);                               // hash outside the lock so sign-ups do not queue
        synchronized (Auth.class) {                                                      // one writer at a time for the short final step
            if (users.containsKey(key(role, email))) return false;                       // taken meanwhile
            User u = new User(email, name, role, salt, hash, role.equals("applicant") ? "" : staffId(name)); // staff get a staff ID automatically
            write(u);                                                                    // save to disk first
            keep(u);                                                                     // then keep in memory
        }
        return true;                                                                     // account created
    }

    static String staffId(String name) {                                                 // staff ID = first three letters of the name, e.g. AGA
        String letters = name.replaceAll("[^\\p{L}]", "").toUpperCase(java.util.Locale.ROOT); // the name's letters only, in capitals
        String base = (letters + "XXX").substring(0, 3), id = base;                      // first three letters (padded for very short names)
        for (int n = 2; usedIds.contains(id); n++) id = base + n;                        // already taken: AGA2, AGA3, ... so every ID stays unique
        return id;                                                                       // nobody has to type or approve it
    }

    static String login(String email, String pass, String role) {                        // returns a session token or null
        User u = users.get(key(role, email));                                            // look up the account of that kind
        if (u == null || !MessageDigest.isEqual(hash(pass, u.salt()).getBytes(StandardCharsets.UTF_8), // unknown email or
            u.hash().getBytes(StandardCharsets.UTF_8))) return null;                     // wrong password: refuse
        long now = System.currentTimeMillis();                                           // current time
        if (sessions.size() % 1000 == 999) sessions.values().removeIf(s -> s.expires() < now); // now and then, clear out expired logins
        String token = random(32);                                                       // unguessable session token
        sessions.put(token, new Session(key(role, email), now + LIFE));                  // remember who holds it and until when
        return token;                                                                    // goes into the browser cookie
    }

    static String startReset(String email) {                                             // makes a reset code, or null when none should be sent
        long now = System.currentTimeMillis();                                           // current time
        Reset old = resets.get(email);                                                   // a code issued earlier, if any
        if (Stream.of("admin", "finance", "audit", "applicant").noneMatch(k -> users.containsKey(key(k, email))) || (old != null && old.expires() - CODE_LIFE + 60_000 > now)) return null; // no account, or one sent under a minute ago
        String code = String.format("%06d", rnd.nextInt(1_000_000));                     // six random digits
        resets.put(email, new Reset(code, now + CODE_LIFE, 0));                          // valid for ten minutes
        return code;                                                                     // to be emailed, never shown on screen
    }

    static synchronized boolean finishReset(String email, String code, String pass) throws IOException { // checks the code and sets the new password
        Reset r = resets.get(email);                                                     // the pending code for this email
        List<User> mine = Stream.of("admin", "finance", "audit", "applicant").map(k -> users.get(key(k, email))).filter(u -> u != null).toList(); // every account under this email
        if (r == null || mine.isEmpty() || r.expires() < System.currentTimeMillis() || r.tries() >= 5) { resets.remove(email); return false; } // none, expired or too many guesses
        if (!MessageDigest.isEqual(r.code().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) { // wrong code
            resets.put(email, new Reset(r.code(), r.expires(), r.tries() + 1));          // count the wrong guess
            return false;                                                                // refuse
        }
        for (User u : mine) {                                                            // student and staff accounts of the same person change together
            String salt = random(16);                                                    // fresh salt for the new password
            User changed = new User(u.email(), u.name(), u.role(), salt, hash(pass, salt), u.staffId()); // same account, new password hash
            write(changed);                                                              // save: the newest line for an account wins at start-up
            keep(changed);                                                               // update memory
        }
        resets.remove(email);                                                            // the code cannot be used twice
        sessions.values().removeIf(s -> s.key().endsWith(":" + email));                  // sign those accounts out everywhere
        return true;                                                                     // password changed
    }

    static synchronized boolean setPassword(String role, String email, String pass) throws IOException { // the system administrator sets a new password
        User u = users.get(key(role, email));                                            // the account to change
        if (u == null || pass.length() < 8) return false;                                // unknown account or too short a password
        String salt = random(16);                                                        // fresh salt for the new password
        User changed = new User(u.email(), u.name(), u.role(), salt, hash(pass, salt), u.staffId()); // same account, new password hash
        write(changed);                                                                  // save: the newest line for an account wins at start-up
        keep(changed);                                                                   // update memory
        sessions.values().removeIf(s -> s.key().equals(key(role, email)));              // sign that account out everywhere
        return true;                                                                     // password changed
    }

    static String token(HttpExchange x) {                                                // reads the session cookie
        for (String h : x.getRequestHeaders().getOrDefault("Cookie", List.of()))         // every cookie header
            for (String c : h.split(";")) if (c.trim().startsWith("sid=")) return c.trim().substring(4); // find "sid"
        return "";                                                                       // no session cookie
    }

    static User current(HttpExchange x) {                                                // who is making this request
        Session s = sessions.get(token(x));                                              // token -> session
        return s == null || s.expires() < System.currentTimeMillis() ? null : users.get(s.key()); // null means not signed in or expired
    }

    static void logout(HttpExchange x) { sessions.remove(token(x)); }                    // forget the session
}
