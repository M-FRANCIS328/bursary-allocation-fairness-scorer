import java.io.BufferedWriter;                                                           // fast appending to the data file
import java.io.IOException;                                                              // file failures
import java.nio.file.Files;                                                              // reading and writing applicants.csv
import java.nio.file.Path;                                                               // file location
import java.nio.file.StandardOpenOption;                                                 // create/append options
import java.util.Comparator;                                                             // ranking order
import java.util.List;                                                                   // list type
import java.util.Map;                                                                    // map type
import java.util.Set;                                                                    // set type
import java.util.concurrent.ConcurrentHashMap;                                           // maps many requests can use at once
import java.util.concurrent.atomic.AtomicInteger;                                        // counter many requests can use at once
import java.util.function.UnaryOperator;                                                 // "old application in, new application out"
import java.util.stream.Stream;                                                          // reads the file line by line

// Thread-safe storage of applications: instant lookups, append-only saving, cached ranking
public class Store {                                                                     // everything about keeping applications and courses
    static final Path FILE = Path.of("applicants.csv");                                  // where applications are stored
    static final Map<Integer, Applicant> byId = new ConcurrentHashMap<>();               // application by reference number
    static final Map<String, Integer> idByEmail = new ConcurrentHashMap<>();             // reference number by owner email
    static final AtomicInteger lastId = new AtomicInteger();                             // highest reference number issued
    static volatile List<Scorer.Result> ranking;                                         // cached ranking, null when out of date
    static BufferedWriter out;                                                           // open data file, new lines go at the end
    static final Path COURSES = Path.of("courses.csv");                                  // where courses and their limits are stored
    static final Map<String, Integer> courses = new java.util.concurrent.ConcurrentSkipListMap<>(); // course name -> students accepted at most, in name order
    static volatile Set<Integer> waitlist = Set.of();                                    // reference numbers of students waiting because their course is full
    static final Map<String, Integer> durations = new ConcurrentHashMap<>();             // course name -> length in years, when the office has set it
    static volatile Map<String, Integer> taken = Map.of();                               // course name -> places filled by eligible students
    static volatile Map<Integer, List<String>> flags = Map.of();                         // reference number -> warnings about possible forgery
    static final Map<String, String> hashes = new ConcurrentHashMap<>();                 // "id/document" -> fingerprint of the uploaded file
    static boolean built;                                                                // a ranking has been worked out at least once

    static final Path ACTIVITY = Path.of("activity.log");                                // staff actions that are not about one application
    static final java.util.Deque<String> activity = new java.util.concurrent.ConcurrentLinkedDeque<>(); // those actions in memory, oldest first

    static final Path CONTACTS = Path.of("contacts.properties");                         // the offices' phone numbers, emails and opening hours
    static final java.util.Properties contacts = new java.util.Properties();             // those contacts in memory

    static void load() throws IOException {                                              // reads saved applications at start-up
        loadCourses();                                                                   // courses first: the ranking needs their limits
        if (Files.exists(CONTACTS)) try (java.io.Reader r = Files.newBufferedReader(CONTACTS)) { contacts.load(r); } // the office contacts
        if (Files.exists(ACTIVITY)) activity.addAll(Files.readAllLines(ACTIVITY));       // earlier staff actions, for the auditors
        if (Files.exists(FILE)) try (Stream<String> lines = Files.lines(FILE)) {         // one line at a time, never the whole file in memory
            lines.map(Applicant::fromCsv).filter(a -> a != null).forEach(Store::keep);   // later lines replace earlier ones for the same id
        }
        Files.write(FILE, byId.values().stream().map(Applicant::toCsv).toList());        // tidy the file: one line per application
        out = Files.newBufferedWriter(FILE, StandardOpenOption.APPEND);                  // keep it open for fast appending
    }

    static void keep(Applicant a) {                                                      // puts an application in memory
        byId.put(a.id(), a);                                                             // findable by reference number
        idByEmail.put(a.email(), a.id());                                                // findable by owner
        lastId.accumulateAndGet(a.id(), Math::max);                                      // never reissue a used number
    }

    static Applicant get(int id) { return byId.get(id); }                                // instant lookup by reference number

    static Applicant byEmail(String email) {                                             // instant lookup of a student's own application
        Integer id = idByEmail.get(email);                                               // their reference number, if any
        return id == null ? null : byId.get(id);                                         // null when they have not applied
    }

    static int idFor(String email) {                                                     // one reference number per account, even under double clicks
        return idByEmail.computeIfAbsent(email, e -> lastId.incrementAndGet());          // issue the next number only once
    }

    static synchronized void put(Applicant a) throws IOException {                       // saves a new or changed application
        out.write(a.toCsv());                                                            // append one line instead of rewriting the file
        out.newLine();                                                                   // end the line
        out.flush();                                                                     // hand it to the disk before confirming
        keep(a);                                                                         // then update memory
        ranking = null;                                                                  // the cached ranking is now out of date
    }

    static synchronized void change(int id, UnaryOperator<Applicant> edit) throws IOException { // safe read-change-save of one application
        Applicant a = byId.get(id);                                                      // current version
        if (a != null) put(edit.apply(a));                                               // save the edited copy, ignore unknown ids
    }

    static List<Scorer.Result> ranked() {                                                // every application scored, best first
        List<Scorer.Result> r = ranking;                                                 // use the cached ranking when it is fresh
        if (r == null) synchronized (Store.class) {                                      // otherwise rebuild it once, not once per request
            r = byId.values().parallelStream().map(Scorer::score)                        // score on all processor cores
                .sorted(Comparator.comparingDouble(Scorer.Result::total).reversed()      // highest total first
                    .thenComparingLong(x -> x.a().submitted())                           // ties: first come, first served
                    .thenComparingInt(x -> x.a().id())).toList();                        // then by reference number, so the order never wobbles
            Map<String, Integer> taken = new java.util.HashMap<>();                      // places used so far in each course
            Set<Integer> over = new java.util.HashSet<>();                               // students who qualify but found their course full
            for (Scorer.Result x : r) if (x.a().eligible()                               // walk down the ranking; only eligible students take places
                && taken.merge(x.a().course(), 1, Integer::sum) > courses.getOrDefault(x.a().course(), Integer.MAX_VALUE)) // one more for this course: past its limit?
                over.add(x.a().id());                                                    // yes: this student goes on the waiting list
            if (built) for (Integer id : over) if (!waitlist.contains(id)) {             // students who have only now been pushed onto a waiting list
                Applicant a = byId.get(id);                                              // tell each of them straight away
                Mail.send(a.email(), "KIU bursary: your course has reached its limit", "Dear " + a.name() + ",\n\n" // email subject and greeting
                    + a.course() + " has reached its limit of " + courses.get(a.course()) + " bursary places, so your application #" + id // what happened
                    + " is now on the waiting list. You keep your place in the queue and move up if a place becomes free.\n\nKIU Bursary Office"); // what it means
            }
            built = true;                                                                // from now on, new arrivals on a waiting list are emailed
            taken.replaceAll((c, n) -> Math.min(n, courses.getOrDefault(c, n)));         // places filled cannot exceed the limit
            Store.taken = taken;                                                         // publish how full each course is
            flags = flags();                                                             // and the forgery warnings
            waitlist = over;                                                             // publish the waiting list first,
            ranking = r;                                                                 // then the ranking it belongs to
        }
        return r;                                                                        // hand back the ranking
    }

    static Map<Integer, List<String>> flags() {                                          // warnings for the same key, receipt or file used twice
        Map<String, List<Integer>> seen = new java.util.HashMap<>();                     // each value -> the applications that used it
        for (Applicant a : byId.values()) {                                              // every application
            if (!a.regNo().isEmpty()) seen.computeIfAbsent("Registration number " + a.regNo(), k -> new java.util.ArrayList<>()).add(a.id()); // its registration number
            if (!a.receipt().isEmpty()) seen.computeIfAbsent("Receipt number " + a.receipt(), k -> new java.util.ArrayList<>()).add(a.id()); // its receipt number
        }
        hashes.forEach((k, h) -> seen.computeIfAbsent("Identical " + k.substring(k.indexOf('/') + 1) + " file #" + h, x -> new java.util.ArrayList<>()) // each uploaded file by its fingerprint
            .add(Integer.parseInt(k.substring(0, k.indexOf('/')))));                     // and the application it belongs to
        Map<Integer, List<String>> out = new java.util.HashMap<>();                      // warnings by application
        seen.forEach((what, ids) -> { if (ids.size() > 1) for (int id : ids)             // a value shared by two or more applications
            out.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(what.replaceAll(" #[0-9a-f]+$", "") + " also used by #" // say what, without the fingerprint
                + ids.stream().filter(o -> o != id).map(String::valueOf).collect(java.util.stream.Collectors.joining(", #")));  // and by whom
        });
        return out;                                                                      // hand back the warnings
    }

    static void fingerprint(int id, String doc, byte[] b) {                              // remembers an uploaded file's fingerprint
        try {                                                                            // SHA-256 is always present in Java
            hashes.put(id + "/" + doc, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b))); // identical files share it
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } // cannot happen
        ranking = null;                                                                  // the warnings must be worked out again
    }

    static synchronized void saveContacts(Map<String, String> values) throws IOException { // an administrator updates the office contacts
        values.forEach(contacts::setProperty);                                           // remember every field, empty ones included
        try (java.io.Writer w = Files.newBufferedWriter(CONTACTS)) { contacts.store(w, "KIU bursary office contacts"); } // and save them
    }

    static synchronized void log(String who, String event) {                             // records a staff action for the audit department
        String line = System.currentTimeMillis() + "|" + who + ": " + event.replace("\n", " "); // time in milliseconds, who, and what
        try {                                                                            // a failure to log must not stop the action itself
            Files.writeString(ACTIVITY, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); // kept on disk
        } catch (IOException e) { System.err.println("Activity log failed: " + e.getMessage()); } // say why in the server window
        activity.add(line);                                                              // and in memory
    }

    static boolean full(String course) {                                                 // has this course reached its limit?
        ranked();                                                                        // make sure the counts are up to date
        return taken.getOrDefault(course, 0) >= courses.getOrDefault(course, Integer.MAX_VALUE); // places filled reach the limit
    }

    static boolean waiting(Applicant a) {                                                // is this student on a course waiting list?
        ranked();                                                                        // make sure the waiting list is up to date
        return waitlist.contains(a.id());                                                // qualifies, but the course limit was reached above them
    }

    static void loadCourses() throws IOException {                                       // reads the courses and their limits at start-up
        if (Files.exists(COURSES)) for (String l : Files.readAllLines(COURSES)) {        // one course per line: name,places
            String[] c = l.split(",");                                                   // course names never contain commas
            if (c.length >= 2) courses.put(c[0], Integer.parseInt(c[1].trim()));         // keep well-formed lines
            if (c.length == 3 && !c[2].isBlank()) durations.put(c[0], Integer.parseInt(c[2].trim())); // its length in years, when given
        }
    }

    static synchronized void setCourse(String name, int places, int years) throws IOException { // an administrator adds a course or changes its limit and length
        courses.put(name, places);                                                       // remember the limit
        if (years > 0) durations.put(name, years);                                       // and the length, when given (0 keeps the old one)
        Files.write(COURSES, courses.entrySet().stream().map(e -> e.getKey() + "," + e.getValue() // save the whole short list
            + (durations.containsKey(e.getKey()) ? "," + durations.get(e.getKey()) : "")).toList()); // with each known length
        ranking = null;                                                                  // limits changed: the waiting lists must be worked out again
    }
}
