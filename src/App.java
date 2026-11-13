import com.sun.net.httpserver.HttpExchange;                                              // one browser request and its reply
import com.sun.net.httpserver.HttpServer;                                                // web server built into the JDK
import java.io.BufferedWriter;                                                           // efficient streaming of the audit file
import java.io.IOException;                                                              // input/output failures
import java.io.OutputStreamWriter;                                                       // text out to the browser
import java.io.Writer;                                                                   // text output type
import java.math.BigDecimal;                                                             // plain number text without exponents
import java.net.InetSocketAddress;                                                       // address and port to listen on
import java.net.URLDecoder;                                                              // decodes form text sent by the browser
import java.nio.charset.StandardCharsets;                                                // UTF-8 everywhere
import java.nio.file.Files;                                                              // reading and writing files
import java.nio.file.Path;                                                               // file locations
import java.util.HashMap;                                                                // form field storage
import java.util.List;                                                                   // list type
import java.util.Locale;                                                                 // fixed number format
import java.util.Map;                                                                    // map type
import java.util.concurrent.ConcurrentHashMap;                                           // map many requests can use at once
import java.util.concurrent.Executors;                                                   // creates the request workers
import java.util.concurrent.Semaphore;                                                   // limits simultaneous uploads
import java.util.regex.Matcher;                                                          // finds field names in uploads
import java.util.regex.Pattern;                                                          // patterns for those names

// Web application: applicants apply at the front end, administrators rank and verify at the back end
public class App {                                                                       // the whole web application lives in this class
    static final int PER_PAGE = 50;                                                      // students shown per table per page
    static final String VERSION = Long.toString(System.currentTimeMillis(), 36);         // changes at every start so browsers drop the old design file
    static final Semaphore UPLOADS = new Semaphore(8);                                   // at most 8 uploads in memory at the same moment
    static final Map<String, byte[]> ASSETS = new ConcurrentHashMap<>();                 // logo and stylesheet kept in memory
    static final String[] FAMILY = {"Both parents alive", "Single parent or guardian", "Orphan"}; // family status choices
    static final String EMAIL = "[a-z0-9][a-z0-9._+-]*@[a-z0-9-]+(\\.[a-z0-9-]+)+";      // shape of an acceptable email
    static final String[] DOCS = {"admission", "slip", "academic", "recommendation"};    // the four required documents (field names)
    static final String[] LABELS = {"Admission letter", "Tuition payment slip", "Academic documents", "LC3, LC5 or Mayor recommendation"}; // their titles
    static final String[] REASONS = {"Returning after a deferment or dead year", "My tuition share is now paid in full", // why a student asks for reinstatement
        "An academic case (retake or missing results) is now cleared", "A disciplinary case is now cleared", // continued
        "My details were wrong or misspelt and are now corrected", "Other reason (explained below)"}; // continued
    static final String[][] CONTACTS = {{"bursary.phone", "Bursary office phone"}, {"bursary.whatsapp", "Bursary office WhatsApp"}, // the contact fields and their labels
        {"bursary.email", "Bursary office email"}, {"bursary.office", "Bursary office location"}, {"bursary.hours", "Opening hours"}, // continued
        {"finance.phone", "Finance office phone"}, {"finance.email", "Finance office email"}, {"finance.office", "Finance office location"}}; // continued
    static final Map<String, String> TYPES = Map.of("pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png"); // allowed uploads

    interface Handler { void run(HttpExchange x, Auth.User u) throws IOException; }      // a page or action for a known user

    public static void main(String[] args) throws IOException {                          // program entry point
        Store.load();                                                                    // reload applications saved earlier
        for (Applicant a : Store.byId.values()) for (String k : DOCS) {                  // every document already uploaded
            Path p = docFile(a.id(), k);                                                 // where it is stored, if anywhere
            if (p != null) Store.fingerprint(a.id(), k, Files.readAllBytes(p));          // remember its fingerprint to spot copies
        }
        Mail.load();                                                                     // read the email settings
        Auth.load(args.length > 1 ? args[0] : "admin@kiu.ac.ug",                         // first administrator email (or from the command line)
                  args.length > 1 ? args[1] : "Admin@12345");                            // first administrator password (or from the command line)
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 4096); // this computer only, port 8080, long waiting line
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());                      // every request gets its own lightweight thread (Java 21)
        route(s, "/", "GET", null, App::home);                                           // login, applicant or admin page by role
        route(s, "/login", "POST", null, App::login);                                    // sign in
        route(s, "/register", "POST", null, App::register);                              // create an applicant account
        route(s, "/forgot", "POST", null, App::forgot);                                  // email a password reset code
        route(s, "/reset", "POST", null, App::reset);                                    // set a new password with that code
        route(s, "/logout", "POST", null, App::logout);                                  // sign out
        route(s, "/apply", "POST", "applicant", App::apply);                             // applicant submits an application
        route(s, "/verify", "POST", "admin", App::verify);                               // admin records documents seen
        route(s, "/doc", "GET", null, App::doc);                                         // opens an uploaded document (owner or admin)
        route(s, "/feedback", "POST", "admin", App::feedback);                           // admin sends a message to a student
        route(s, "/audit.csv", "GET", "admin,audit", App::audit);                              // admin downloads the sponsor audit
        route(s, "/set-password", "POST", "admin", App::setPassword);                   // only the system administrator sets another account's password
        route(s, "/admins", "POST", "admin", App::addAdmin);                             // only the system administrator creates administrators
        route(s, "/contacts", "POST", "admin", App::saveContacts);                       // admin updates the offices' phone numbers and emails
        route(s, "/courses", "POST", "admin", App::courses);                             // admin sets how many students a course accepts
        route(s, "/change-course", "POST", "applicant", App::changeCourse);              // student asks to move to another course
        route(s, "/course-decision", "POST", "admin", App::courseDecision);              // admin approves or declines that request
        route(s, "/withdraw", "POST", "admin", App::withdraw);                           // admin withdraws a student's bursary, with a reason
        route(s, "/reinstate", "POST", "applicant", App::reinstate);                     // student asks for a withdrawn bursary back
        route(s, "/reinstate-decision", "POST", "admin", App::reinstateDecision);        // admin approves or declines that request
        route(s, "/issue", "POST", "admin", App::issue);                                 // admin issues the award letter
        route(s, "/letter", "GET", null, App::letter);                                   // the printable award letter (owner or admin)
        route(s, "/payment", "POST", "finance", App::payment);                           // Finance office confirms or rejects a payment slip
        route(s, "/payments.csv", "GET", "finance,audit", App::payments);                      // Finance office downloads every slip and its status
        route(s, "/logo.png", "GET", null, (x, u) -> asset(x, "kiu-logo.png", "image/png")); // KIU logo image
        route(s, "/style.css", "GET", null, (x, u) -> asset(x, "style.css", "text/css")); // page design
        route(s, "/giraffe.png", "GET", null, (x, u) -> asset(x, "giraffe.png", "image/png")); // KIU giraffe used as the page watermark
        s.start();                                                                       // begin serving requests
        System.out.println("Bursary scorer running: open http://localhost:8080");        // tell the operator where to go
    }

    static void route(HttpServer s, String path, String method, String role, Handler h) { // registers one guarded address
        s.createContext(path, x -> {                                                     // runs for every request to this address
            Auth.User u = Auth.current(x);                                               // who is signed in, if anyone
            try {                                                                        // bad input lands in the catch below
                if (!x.getRequestURI().getPath().equals(path)) send(x, 404, "text/plain", "Not found"); // only the exact address, not ones that merely start the same
                else if (!x.getRequestMethod().equals(method)) send(x, 405, "text/plain", "Method not allowed"); // wrong kind of request
                else if (role != null && (u == null || !List.of(role.split(",")).contains(u.role()))) redirect(x, "/"); // not allowed: back to own page
                else h.run(x, u);                                                        // allowed: do the work
            } catch (RuntimeException e) {                                               // missing, non-numeric or out-of-range input
                send(x, 400, "text/plain", "Invalid input: go back, check every field and attach all four documents (PDF, JPG or PNG, 3 MB each at most)."); // explain without saving
            }
        });
    }

    // ---------- pages ----------

    static void home(HttpExchange x, Auth.User u) throws IOException {                   // chooses the page for this visitor
        if (!x.getRequestURI().getPath().equals("/")) { send(x, 404, "text/plain", "Not found"); return; } // unknown address
        Map<String, String> q = params(x.getRequestURI().getRawQuery());                 // options from the address bar
        if (u == null) loginPage(x, q);                                                  // visitor: sign in or register
        else if (u.role().equals("admin")) adminPage(x, u, q);                           // administrator: back end
        else if (u.role().equals("finance")) financePage(x, u, q);                       // Finance office: payment tracking
        else if (u.role().equals("audit")) auditPage(x, u, q);                           // Audit department: read-only oversight
        else applicantPage(x, u, q);                                                     // applicant: front end
    }

    static void loginPage(HttpExchange x, Map<String, String> q) throws IOException {    // sign-in and registration forms
        String e = q.getOrDefault("e", "");                                              // error code from a failed attempt
        String msg = e.equals("1") ? "Wrong email or password. Check that the right one of Administrator or Student, and the right office, is selected." // failed sign-in
            : e.equals("2") ? "That email already has a student account. Sign in instead." // duplicate registration
            : e.equals("3") ? "Enter your name, a valid email and a password of at least 8 characters." // bad details
            : e.equals("7") ? "If that email has an account, a 6-digit code has been sent to it. Enter it below within 10 minutes." // reset code sent
            : e.equals("8") ? "Wrong or expired code, or the new password is shorter than 8 characters. Try again or request a new code." // reset failed
            : e.equals("9") ? "Password changed. Sign in with your new password." : "";  // reset worked
        String banner = msg.isEmpty() ? "" : "<p class='msg " + (e.equals("7") || e.equals("9") ? "good" : "bad") + "'>" + msg + "</p>"; // green for good news
        String t = q.getOrDefault("t", "ai");                                            // open tab: a/s = administrator/student, i/u = sign in/up
        boolean admin = !t.startsWith("s"), in = !t.endsWith("u");                       // administrators and sign-in come first by default
        String hero = "<div class=welcome><section class=hero><h2>Bursary applications, decided fairly</h2>" // left side: what the system is
            + "<p>Students apply online and upload their documents. The bursary office reviews every application, " // short description,
            + "sends feedback and gives a stated reason for every score.</p>"            // continued
            + "<ul><li>Admission letter<li>Tuition payment slip<li>Academic documents<li>LC3, LC5 or Mayor recommendation</ul></section>" // what to prepare
            + "<section class='card auth'>" + banner;                                    // right side: one card, message on top
        if (t.equals("fp")) { page(x, null, hero + "<h2>Forgot your password?</h2>"      // step 1 of a reset: ask for the email
            + "<form class=stack method=post action=/forgot><label>Your account email<input name=email type=email required></label>" // which account
            + "<button>Email me a reset code</button><p class=hint>For students and administrators. <a href=/>Back to sign in</a></p></form></section></div>"); return; } // send button, note and way back
        if (t.equals("rp")) { page(x, null, hero + "<h2>Set a new password</h2>"         // step 2 of a reset: code and new password
            + "<form class=stack method=post action=/reset autocomplete=off><label>Your account email<input name=email type=email required value='" + esc(q.getOrDefault("email", "")) + "'></label>" // same email
            + "<label>6-digit code from the email<input name=code required inputmode=numeric pattern='[0-9]{6}' maxlength=6 autocomplete=one-time-code></label>" // the emailed code
            + "<label>New password (8 characters or more)<input name=password type=password minlength=8 required autocomplete=new-password></label>" // new password
            + "<button>Change password</button><p class=hint><a href='/?t=fp'>Send a new code</a> &middot; <a href=/>Back to sign in</a></p></form></section></div>"); return; } // change button and helper links
        page(x, null, hero                                                               // normal view: the four sign-in and sign-up forms
            + "<input type=radio name=who id=w-a" + (admin ? " checked" : "") + "><input type=radio name=who id=w-s" + (admin ? "" : " checked") + ">" // who is it
            + "<input type=radio name=mode id=m-i" + (in ? " checked" : "") + "><input type=radio name=mode id=m-u" + (in ? "" : " checked") + ">" // doing what
            + "<div class='seg who'><label for=w-a>Administrator</label><label for=w-s>Student</label></div>" // first switch, administrators first
            + "<div class='seg mode'><label for=m-i>Sign in</label><label for=m-u>Sign up</label></div>" // second switch
            + signIn("p-ai", "Staff sign in", "admin", "For Bursary office, Finance office and Audit department staff who already have an account.") // panel 1
            + "<div class='panel p-au'><p class='msg good'>Administrators do not sign up here. The system administrator creates your account in the back end and gives you your email, password and staff ID.</p></div>" // panel 2: a note, not a form
            + signIn("p-si", "Student sign in", "applicant", "For students who already have an account.") // panel 3
            + signUp("p-su", "Student sign up", "/register", "For new students applying for a bursary.", "") // panel 4
            + "</section></div>");                                                       // card and layout close
    }

    static String signIn(String panel, String title, String role, String hint) {         // sign-in form for one kind of user
        return "<form class='panel stack " + panel + "' method=post action=/login>"      // shown only when its two switches are chosen
            + (role.equals("admin") ? "<label>Office<select name=role><option value=admin>Bursary office (administrator)<option value=finance>Finance office<option value=audit>Audit department</select></label>" // staff choose their office
                : "<input type=hidden name=role value=" + role + ">")                    // tells the server which kind of user is signing in
            + "<label>Email<input name=email type=email required autocomplete=username></label>" // account email
            + "<label>Password<input name=password type=password required autocomplete=current-password></label>" // account password
            + "<button>" + title + "</button><p class=hint>" + hint + " <a href='/?t=fp'>Forgot password?</a></p></form>"; // button, note and reset link
    }

    static String signUp(String panel, String title, String action, String hint, String extra) { // sign-up form for one kind of user
        return "<form class='panel stack " + panel + "' method=post action=" + action + " autocomplete=off>" // shown only when chosen
            + "<label>Full name<input name=name required maxlength=60></label>"          // the new user's name
            + "<label>Email<input name=email type=email required maxlength=100 autocomplete=off></label>" // becomes the login
            + "<label>Password (8 characters or more)<input name=password type=password minlength=8 required autocomplete=new-password></label>" // chosen password
            + extra + "<button>" + title + "</button><p class=hint>" + hint + "</p></form>"; // extra box if any, button and note
    }

    static void applicantPage(HttpExchange x, Auth.User u, Map<String, String> q) throws IOException { // applicant front end
        Applicant a = Store.byEmail(u.email());                                          // this user's application, found instantly
        StringBuilder h = new StringBuilder(q.containsKey("ok") ? "<p class='msg good'>Application saved. The committee will review it.</p>" : ""); // success banner
        if (q.containsKey("ok") && a != null && Store.waiting(a)) h.append("<p class='msg bad'>") // the course filled up before this student
            .append(esc(a.course())).append(" has reached its limit of bursary places, so your application is on its waiting list.</p>"); // say so at once
        if (q.containsKey("cc")) h.append("<p class='msg good'>Change of course request sent. The bursary office will approve or decline it.</p>"); // course change sent
        if (q.containsKey("rs")) h.append("<p class='msg good'>Reinstatement request sent. The bursary office will review it and reply here.</p>"); // reinstatement sent
        h.append("<section class=card><h2>Bursary requirements</h2><ol class=req>")      // requirements card opens
         .append("<li><b>Admission letter</b>")                                          // requirement 1
         .append("<li><b>Tuition payment slip</b>: pay <b>60%</b> of tuition if your course takes 4 years or more (the university removes 40%), ") // requirement 2, long courses
         .append("or <b>50%</b> if it takes 3 years or less (the university removes 50%)") // requirement 2, short courses
         .append("<li><b>Academic documents</b>")                                        // requirement 3
         .append("<li><b>Recommendation</b> from the LC3 chairperson, the LC5 chairperson or the Mayor, dated within the last 6 months</ol>") // requirement 4
         .append("<p class=hint>Upload every document here first. The bursary office reviews it and sends you feedback on this page; ") // online first
         .append("after that, bring the originals to the office. Among qualified applicants, first come is first served.</p>") // then physically
         .append("<p class=hint><b>Pay only your part of tuition.</b> Do not pay the bursary part: the university removes it once your award letter is issued on this page. ") // stops early full payments
         .append("Your name must be spelled the same on every document and match your registration number.</p></section>") // stops misspelt awards
         .append(contacts());                                                            // how to reach the offices
        if (a != null) {                                                                 // feedback and status only after applying
            boolean[] seen = {a.admission(), a.slip(), a.academic(), a.recommendation()}; // which originals were seen
            if (!a.feedback().isEmpty()) h.append("<section class='card note'><h2>Feedback from the bursary office</h2><p>") // feedback card
             .append(esc(a.feedback()).replace("\n", "<br>")).append("</p></section>");   // the administrator's message, line breaks kept
            h.append("<section class=card><h2>My application status</h2><div class=tiles>") // status card opens
             .append(tile("#" + a.id(), "Reference number &middot; Reg. no. " + esc(a.regNo()))).append(tile(Scorer.when(a.submitted()), "Applied on")) // reference and place in the queue
             .append(tile(a.year(), esc(a.course()) + " &middot; " + Applicant.INTAKES[a.intake()] + " intake &middot; " + Applicant.CAMPUSES[a.campus()])) // year, course, intake and campus
             .append(tile(Scorer.money(a.share()), "Your part of tuition (" + Math.round(100 - a.rate() * 100) + "%)")) // what the student pays
             .append(tile(Scorer.money(a.award()), "Bursary if awarded (" + Math.round(a.rate() * 100) + "%): do not pay this part")) // what the university removes
             .append(tile(!a.active() ? Applicant.STANDINGS[a.standing()] : !a.qualifies() ? "Does not qualify" : Store.waiting(a) ? "Waiting list" : "Qualifies", // which group the system put them in
                 !a.active() ? "See Bursary reinstatement below"                         // withdrawn: what to do next
                 : Store.waiting(a) ? "Your course has reached its limit of " + Store.courses.get(a.course()) + " students" // course full
                 : a.qualifies() ? "Your part of tuition is paid"                        // accepted so far
                 : "Slip is short by " + Scorer.money(a.share() - a.paid())))            // or not, with the amount still owed
             .append(tile(a.letter() > 0 ? "Issued" : "Not yet issued", a.letter() > 0   // the award letter
                 ? Scorer.when(a.letter()) + " &middot; <a target=_blank href='/letter?id=" + a.id() + "'>open award letter</a>" // when, and a link to print it
                 : "Wait for it before paying anything beyond your part"))               // the rule that prevents early payments
             .append("</div><div class=tiles>");                                         // second row: the four documents and the name check
            for (int i = 0; i < 4; i++) h.append(tile(i == 3 && !a.recCurrent() ? "Outdated" : seen[i] ? "Verified" : "Bring original", // original seen, awaited or too old
                LABELS[i] + (i == 3 ? " dated " + esc(a.recDate()) : "") + " &middot; <a target=_blank href='/doc?id=" + a.id() + "&f=" + DOCS[i] + "'>view upload</a>")); // link to the uploaded copy
            h.append(tile(a.namesMatch() ? "Confirmed" : "Not yet checked", "Name spelled the same on every document")) // misspelling check
             .append(tile(Applicant.PAYMENTS[a.payStatus()], "Slip " + esc(a.receipt()) + (a.payStatus() == 1 ? ": " + Scorer.money(a.payAmount()) + " received" : "") // the Finance office's check
                 + (a.payNote().isEmpty() ? "" : " &middot; " + esc(a.payNote()))))     // with its note
             .append("</div></section>");                                                // status card closes
            h.append("<section class=card><h2>Change of course</h2>")                    // course change card
             .append(a.newCourse().isEmpty() ? "" : "<p class='msg good'>You asked to move to <b>" + esc(a.newCourse()) + "</b>. Waiting for the bursary office.</p>") // pending request
             .append("<form class=row method=post action=/change-course><label>Move to<select class=step name=course required><option value=''>Choose the new course"); // request form
            for (String c : Store.courses.keySet()) if (!c.equals(a.course())) h.append("<option>").append(esc(c)); // every other listed course
            h.append("</select></label><button>Request change of course</button></form>")  // send button
             .append("<p class=hint>The office approves or declines the change. Once approved, your course, bursary rate and place in that course are worked out again and a new award letter is issued.</p></section>"); // what happens next
            if (a.standing() != 0) {                                                     // only students whose bursary was withdrawn see reinstatement
                h.append("<section class='card note'><h2>Bursary reinstatement</h2><p><b>").append(Applicant.STANDINGS[a.standing()]).append("</b>") // current standing
                 .append(a.standingNote().isEmpty() ? "" : ": " + esc(a.standingNote()).replace("\n", "<br>")).append("</p>"); // with the latest note
                if (a.standing() == 1 || a.standing() == 4) {                            // withdrawn or declined: the student may ask
                    h.append("<form method=post action=/reinstate enctype=multipart/form-data><div class=grid>") // reinstatement form, file allowed
                     .append(select("Why should your bursary be reinstated?", "reason", REASONS, 0)) // the reason category
                     .append("<label>Supporting document (PDF, JPG or PNG)<input type=file name=reinstate accept='.pdf,.jpg,.jpeg,.png' required></label></div>") // the evidence
                     .append("<label>Explain your case<textarea name=text rows=4 maxlength=500 required></textarea></label>") // the explanation
                     .append("<div class=row><button>Request reinstatement</button></div></form>"); // send button
                }
                h.append("</section>");                                                  // reinstatement card closes
            }
            if (!a.history().isEmpty()) h.append("<section class=card><h2>My bursary record</h2><p class=hint>") // record of events
                .append(esc(a.history()).replace("\n", "<br>")).append("</p></section>");  // one dated line per event
        }
        h.append("<section class=card><h2>").append(a == null ? "Bursary application" : "Update my application") // form card heading
         .append("</h2><form method=post action=/apply enctype=multipart/form-data>")    // application form opens, files allowed
         .append("<h3>Academic year, intake and campus</h3><div class=grid>")            // enrolment group
         .append(field("Academic year", "name=year pattern='20[0-9]{2}/20[0-9]{2}' placeholder='e.g. 2026/2027'", a == null ? "" : a.year())) // which year
         .append(select("Intake", "intake", Applicant.INTAKES, a == null ? 0 : a.intake())) // which intake
         .append(select("Campus", "campus", Applicant.CAMPUSES, a == null ? 0 : a.campus())) // main or western
         .append(field("Registration number", "name=regNo maxlength=40 placeholder='as on your admission letter'", a == null ? "" : a.regNo())) // the key that ties every record together
         .append("<label>Course<select class=step name=course required><option value=''>Choose your course");  // course list opens
        for (String c : Store.courses.keySet()) h.append("<option value='").append(esc(c)).append("'") // courses the office listed
            .append(a != null && a.course().equals(c) ? " selected>" : ">").append(esc(c)) // keep the saved one chosen
            .append(Store.full(c) && (a == null || !a.course().equals(c)) ? " (full: waiting list only)" : ""); // say when it has reached its limit
        h.append("</select></label>")                                                    // course list closes
         .append("</div><h3>Student and next of kin contacts (next of kin optional)</h3><div class=grid>") // contacts group
         .append(field("Student phone", "name=phone " + TEL, a == null ? "" : a.phone())) // how to reach the student
         .append(optional("Next of kin full name (optional)", "name=kinName maxlength=60", a == null ? "" : a.kinName())) // who to reach otherwise
         .append(optional("Next of kin phone (optional)", "name=kinPhone " + TEL, a == null ? "" : a.kinPhone())) // and their number
         .append("</div><h3>Parent or guardian details</h3><div class=grid>")            // parent group
         .append(field("Parent or guardian full name", "name=parentName maxlength=60", a == null ? "" : a.parentName())) // parent's name
         .append(field("Parent or guardian phone", "name=parentPhone " + TEL, a == null ? "" : a.parentPhone())) // parent's number
         .append(select("Parent or guardian occupation", "parentJob", Scorer.JOBS, a == null ? 0 : a.parentJob())) // occupation list
         .append(select("Family status", "guardian", FAMILY, a == null ? 0 : a.guardian())) // family status list
         .append("</div><h3>Tuition</h3><div class=grid>")          // tuition group
         .append(field("Tuition fee", "name=fees type=number min=1 step=any", a == null ? "" : plain(a.fees()))) // full tuition
         .append(field("Course duration (years)", "name=years type=number min=1 max=8", a == null ? "" : "" + a.years())) // decides 60% or 50%
         .append(field("Amount paid on your slip", "name=paid type=number min=0 step=any", a == null ? "" : plain(a.paid()))) // tuition already paid
         .append(field("Receipt number on your slip", "name=receipt maxlength=40", a == null ? "" : a.receipt())) // checked against every other slip
         .append(field("Date on your recommendation letter", "name=recDate type=date", a == null ? "" : a.recDate())) // must be within six months
         .append("</div><p class=hint>Where the office has set a course's length, that length is used instead of the one typed here, so the 40% or 50% rate is always right.</p>") // course length rule
         .append("<h3>Documents</h3><div class=grid>");                                  // uploads group
        for (int i = 0; i < 4; i++) h.append("<label>").append(LABELS[i]).append(" (PDF, JPG or PNG)<input type=file name=") // one upload box per document
            .append(DOCS[i]).append(" accept='.pdf,.jpg,.jpeg,.png'").append(a == null ? " required" : "").append("></label>"); // required on first submission
        h.append("</div><div class=row><button>").append(a == null ? "Submit application" : "Save changes").append("</button></div>") // submit button
         .append(a == null ? "" : "<p class=hint>Leave an upload empty to keep the file already sent. Saving changes resets verification and the award letter, because the office checked the earlier details.</p>") // integrity rule
         .append("</form></section>");                                                   // application form closes
        page(x, u, h.toString());                                                        // deliver the page
    }

    static void adminPage(HttpExchange x, Auth.User u, Map<String, String> q) throws IOException { // administrator back end
        double budget = 0;                                                               // sponsor budget, 0 means not set
        try { budget = Math.max(0, Double.parseDouble(q.getOrDefault("budget", "0"))); } catch (NumberFormatException e) {} // ignore a bad budget
        double left = budget;                                                            // money still unallocated
        boolean names = q.containsKey("names");                                          // names stay hidden unless asked for
        String back = java.util.Objects.toString(x.getRequestURI().getRawQuery(), "").replaceAll("[^\\w=&.%-]", ""); // current view, to return to after saving
        int yes = 0, no = 0, funded = 0;                                                 // students who qualify, who do not, and awards made
        int pg = 1;                                                                      // which page of each table to show
        try { pg = Math.max(1, Integer.parseInt(q.getOrDefault("page", "1"))); } catch (NumberFormatException e) {} // ignore a bad page number
        long from = (pg - 1L) * PER_PAGE;                                                // rows to skip before this page
        StringBuilder pass = new StringBuilder(), fail = new StringBuilder();            // table rows for the two groups
        int waiting = 0;                                                                 // students who qualify but whose course is full
        Map<String, int[]> use = new HashMap<>();                                        // per course: [accepted, waiting]
        for (Scorer.Result r : Store.ranked()) {                                         // walk the cached ranking, best first
            Applicant a = r.a();                                                         // the application being considered
            boolean ok = a.eligible(), wait = ok && Store.waitlist.contains(a.id());     // sorted into a group; past the course limit or not
            boolean fund = ok && !wait && a.verified() && budget > 0 && a.award() <= left; // accepted, originals seen and affordable
            int pos = ok ? ++yes : ++no;                                                 // rank inside its group
            if (ok) use.computeIfAbsent(a.course(), k -> new int[2])[wait ? 1 : 0]++;    // count it against its course
            if (wait) waiting++;                                                         // and in the waiting total
            if (fund) { left -= a.award(); funded++; }                                   // award it and reduce the budget
            if (pos <= from || pos > from + PER_PAGE) continue;                          // only this page's rows are turned into HTML
            boolean[] seen = {a.admission(), a.slip(), a.academic(), a.recommendation()}; // which originals were seen
            StringBuilder rows = ok ? pass : fail;                                       // the table this row belongs to
            List<String> warn = Store.flags.getOrDefault(a.id(), List.of());             // possible forgery: the same key, receipt or file elsewhere
            String decision = !a.active() ? "<span class='tag no'>" + Applicant.STANDINGS[a.standing()] + "</span>" // withdrawn, or asking to come back
                : !ok ? "<span class='tag no'>Does not qualify</span><br><small>tuition part short by " // slip does not cover the part
                    + Scorer.money(a.share() - a.received()) + (a.payStatus() == 2 ? " (slip rejected by Finance)" : "") + "</small>" // say by how much
                : wait ? "<span class='tag wait'>Waiting list</span><br><small>course full: " + Store.courses.get(a.course()) + " places</small>" // course limit reached
                : !a.verified() ? "<span class='tag wait'>Qualifies</span><br><small>awaiting " + (a.originals() ? "Finance confirmation" : "originals") + "</small>" // originals or payment not yet checked
                : budget <= 0 ? "<span class='tag ok'>Confirmed</span><br><small>set a budget</small>" // ready, no budget entered
                : fund ? "<span class='tag ok'>FUNDED</span>"                            // affordable: awarded
                : "<span class='tag no'>Not funded</span><br><small>only " + Scorer.money(left) + " left</small>"; // not affordable: say why
            if (a.letter() > 0) decision += "<br><small>letter issued " + Scorer.when(a.letter()) + " &middot; <a target=_blank href='/letter?id=" + a.id() + "'>view</a></small>"; // award letter already out
            else if (ok && !wait && a.verified()) decision += "<form method=post action=/issue><input type=hidden name=id value=" + a.id() // confirmed: the letter can go out
                + "><input type=hidden name=q value='" + back + "'><button class=small>Issue award letter</button></form>"; // one click, then the student sees it
            if (!a.newCourse().isEmpty()) decision += "<br><small>asks to move to " + esc(a.newCourse()) + "</small>"; // pending change of course
            for (String w : warn) decision += "<br><span class='tag no'>&#9888; " + esc(w) + "</span>"; // forgery warnings in red
            rows.append("<tr><td class=n>").append(pos).append("<td><b>#").append(a.id()).append("</b> <small>").append(esc(a.regNo())).append("</small>") // rank in its group, reference and registration number
                .append(!names ? "" : " " + esc(a.name()) + "<br><small>" + reach(a.phone(), a.email()) // identity only when requested, with one-click contacts
                    + "<br>Parent: " + esc(a.parentName()) + " &middot; " + reach(a.parentPhone(), "") // parent or guardian contact
                    + "<br>Next of kin: " + (a.kinName().isEmpty() && a.kinPhone().isEmpty() ? "not given" : esc(a.kinName()) + " &middot; " + esc(a.kinPhone())) + "</small>") // next of kin contact, when given
                .append("<br><small><b>").append(esc(a.course())).append("</b><br>")     // course applied for
                .append(Applicant.CAMPUSES[a.campus()]).append(" &middot; ").append(esc(a.year())) // campus and academic year
                .append(" &middot; ").append(Applicant.INTAKES[a.intake()]).append(" intake")  // intake
                .append("<br>applied ").append(Scorer.when(a.submitted())).append("</small>") // place in the first-come queue
                .append("<br><small>slip <b>").append(esc(a.receipt())).append("</b>: <span class=").append(a.payStatus() == 1 ? "good" : "bad").append(">") // the Finance office's check
                .append(Applicant.PAYMENTS[a.payStatus()]).append(a.payStatus() == 1 ? " " + Scorer.money(a.payAmount()) : "").append("</span></small>") // with the amount received
                .append("<td class=n>").append(Scorer.f(r.need()))                       // need score
                .append("<td class=n>").append(Scorer.f(r.verification()))               // verification score
                .append("<td class=n><span class=total>").append(Scorer.f(r.total())).append("</span>") // total as a green pill
                .append("<td class=n>").append(Scorer.money(a.award())).append("<br><small>").append(Math.round(a.rate() * 100)) // bursary amount
                .append("% of ").append(Scorer.money(a.fees())).append("</small><td>").append(decision) // its basis, then the decision
                .append("<td><form class=checks method=post action=/verify><input type=hidden name=id value=").append(a.id()) // verification form
                .append("><input type=hidden name=q value='").append(back).append("'>"); // remembers the current view
            for (int i = 0; i < 4; i++) rows.append("<span>").append(check(DOCS[i], LABELS[i], seen[i])) // tick when the original is seen
                .append(" <a target=_blank href='/doc?id=").append(a.id()).append("&f=").append(DOCS[i]).append("'>view</a>") // open the uploaded copy
                .append(i == 3 && !a.recCurrent() ? "<br><small class=bad>dated " + esc(a.recDate().isEmpty() ? "unknown" : a.recDate()) + ": outdated</small>" : "").append("</span>"); // an old recommendation
            rows.append("<span>").append(check("namesok", "Name same on all documents", a.namesMatch())).append("</span>") // misspelling check
                .append("<button class=small>Save</button></form>")                      // saves the five ticks
                .append("<td><form class=checks method=post action=/feedback><input type=hidden name=id value=").append(a.id()) // feedback form
                .append("><input type=hidden name=q value='").append(back).append("'>")  // remembers the current view
                .append("<textarea name=text rows=4 maxlength=500 placeholder='Message the student sees'>").append(esc(a.feedback())) // current message
                .append("</textarea><button class=small>Send to student</button></form>") // sends it to the student's page
                .append("<td class=why>").append(esc(r.reason()).replace(" | ", "<br>")); // stated reason, one part per line
        }
        String head = "<div class=scroll><table><tr><th>Rank<th>Applicant<th>Need /60<th>Verified /40" // table header, part scores
            + "<th>Total /100<th>Bursary<th>Decision<th>Originals seen<th>Feedback<th>Reason for score"; // table header, outcome columns
        String none = "<tr><td colspan=10 class=empty>No students in this group on this page."; // shown when a table has no rows
        long pages = Math.max(1, (Math.max(yes, no) + PER_PAGE - 1) / PER_PAGE);         // pages needed by the longer table
        String link = "/?" + (budget > 0 ? "budget=" + plain(budget) + "&" : "") + (names ? "names=on&" : "") + "page="; // keeps budget and names
        String pager = "<p class=hint>Page " + pg + " of " + pages + " (" + PER_PAGE + " students per table per page)" // where the admin is
            + (pg > 1 ? " &middot; <a href='" + link + (pg - 1) + "'>Previous</a>" : "") // back one page
            + (pg < pages ? " &middot; <a href='" + link + (pg + 1) + "'>Next</a>" : "") + "</p>"; // forward one page
        String m = q.getOrDefault("m", "");                                              // outcome of creating an administrator
        String msg = m.equals("0") ? "<p class='msg good'>Staff account created. Staff ID: <b>" // success banner
                + q.getOrDefault("id", "").replaceAll("[^A-Z0-9]", "") + "</b>. Give them the email and password you set.</p>" // with the new staff ID
            : m.equals("2") ? "<p class='msg bad'>That email already has an account in that office.</p>" // duplicate email banner
            : m.equals("3") ? "<p class='msg bad'>Enter a name, a valid email and a password of at least 8 characters.</p>" // bad details banner
            : m.equals("8") ? "<p class='msg good'>Course saved. Waiting lists have been worked out again.</p>" // course limit changed
            : m.equals("10") ? "<p class='msg good'>Done. The student has been emailed and sees the outcome on their page.</p>" // a decision was recorded
            : m.equals("11") ? "<p class='msg bad'>No application has that reference number.</p>" // withdrawal of an unknown number
            : m.equals("12") ? "<p class='msg good'>Password changed. Give the new password to the account holder; they have been signed out everywhere.</p>" // password set
            : m.equals("13") ? "<p class='msg bad'>Password not changed: it must be at least 8 characters.</p>" // password too short
            : m.equals("14") ? "<p class='msg good'>Contacts saved. Students now see them on their pages.</p>" : ""; // contacts updated
        StringBuilder list = new StringBuilder();                                        // one suggestion per course
        for (Map.Entry<String, Integer> c : Store.courses.entrySet()) {                  // courses in name order
            int[] n = use.getOrDefault(c.getKey(), new int[2]);                          // accepted and waiting counts for this course
            list.append("<option value='").append(esc(c.getKey())).append("' data-p=").append(c.getValue()) // the suggestion carries the name and limit
                .append(" label='limit ").append(c.getValue()).append(", accepted ").append(n[0]).append(", waiting ").append(n[1]).append("'>"); // and shows the figures
        }
        String courseCard = "<section class=card><h2>Courses and places</h2>"            // where the limit per course is set
            + "<form class=row method=post action=/courses>"                             // one form both changes a limit and adds a course
            + "<label>Course name (" + Store.courses.size() + " listed)<input class=step name=name list=courselist required maxlength=90 autocomplete=off " // type a name, or step with the arrows
            + "placeholder='Type a course name, or use the arrows'></label><datalist id=courselist>" + list + "</datalist>" // the listed courses appear as you type
            + "<label>Students to accept<input name=places type=number min=0 max=1000000 required></label>" // its limit
            + "<label>Length in years<input name=years type=number min=0 max=8 placeholder='leave empty to keep'></label><button>Save course</button></form>" // its length
            + "<p class=hint>Use the arrows to step through the listed courses, or type a new name to add a course. " // how to use the box
            + "Places go to the highest-ranked qualifying students in each course; the rest go on that course's waiting list and are emailed. " // how it works
            + "A course's length decides the 40% or 50% rate for every student in it, whatever the student typed.</p></section>"; // why the length matters
        StringBuilder alert = new StringBuilder();                                       // courses that have reached their limit
        use.forEach((c, n) -> { if (n[1] > 0 || n[0] >= Store.courses.getOrDefault(c, Integer.MAX_VALUE)) // full, or already turning students away
            alert.append("<li><b>").append(esc(c)).append("</b>: all ").append(Store.courses.get(c)).append(" places filled, ").append(n[1]).append(" on the waiting list"); }); // one line per full course
        String full = alert.length() == 0 ? "" : "<section class='card note'><h2>Courses that have reached their limit</h2><ul class=hint>" + alert // shown only when a course is full
            + "</ul><p class=hint>Students pushed onto a waiting list are emailed automatically. Raise a limit in Courses and places to admit more.</p></section>"; // what the office can do
        StringBuilder changes = new StringBuilder(), comeback = new StringBuilder();     // the two queues of requests
        int moves = 0, returns = 0;                                                      // how many requests wait in each
        for (Applicant a : Store.byId.values()) {                                        // every application
            String who = "<b>#" + a.id() + "</b> " + esc(a.regNo()) + (names ? " " + esc(a.name()) : ""); // reference, and the name only when shown
            if (!a.newCourse().isEmpty()) moves++;                                       // count the course changes
            if (a.standing() == 2) returns++;                                            // and the reinstatement requests
            if (!a.newCourse().isEmpty()) changes.append("<tr><td>").append(who).append("<td>").append(esc(a.course())) // a course change waiting
                .append("<td>").append(esc(a.newCourse())).append(Store.full(a.newCourse()) ? " <span class='tag wait'>full</span>" : "") // where to, and whether it is full
                .append("<td>").append(decide("/course-decision", a.id(), back, false));  // approve or decline
            if (a.standing() == 2) comeback.append("<tr><td>").append(who).append("<td>").append(esc(a.standingNote()).replace("\n", "<br>")) // a reinstatement waiting, with the student's case
                .append("<br><a target=_blank href='/doc?id=").append(a.id()).append("&f=reinstate'>supporting document</a>") // and the evidence
                .append("<td>").append(decide("/reinstate-decision", a.id(), back, true)); // approve or decline with a note
        }
        String requests = "<section class=card><h2>Change of course requests (" + moves + ")</h2>" // course changes
            + (changes.length() == 0 ? "<p class=hint>No requests.</p>" : "<div class=scroll><table><tr><th>Student<th>From<th>To<th>Decision" + changes + "</table></div>") // the queue
            + "<p class=hint>Approving moves the student, uses the new course's length for the rate, and cancels any award letter so a correct one is issued.</p></section>" // effect
            + "<section class=card><h2>Bursary reinstatement requests (" + returns + ")</h2>" // reinstatement card
            + (comeback.length() == 0 ? "<p class=hint>No reinstatement requests.</p>" : "<div class=scroll><table><tr><th>Student<th>Case<th>Decision" + comeback + "</table></div>") // the queue
            + "<h3>Withdraw a bursary</h3><form class=row method=post action=/withdraw><input type=hidden name=q value='" + back + "'>" // withdrawal form
            + "<label>Reference number<input name=id type=number min=1 required></label>" // which student
            + "<label>Reason the student will see<input name=text required maxlength=300 placeholder='e.g. tuition share not paid by the deadline'></label>" // why
            + "<button>Withdraw bursary</button></form><p class=hint>The student is emailed and can then ask for reinstatement with a supporting document. " // what follows
            + "A reinstated student is ranked again with everyone else.</p></section>";  // and its effect
        String find = q.getOrDefault("acct", "").trim().toLowerCase();                   // the system administrator's account search
        List<Auth.User> accounts = Auth.users.values().stream()                          // every account of every kind
            .filter(a -> find.isEmpty() || a.email().contains(find) || a.name().toLowerCase().contains(find) || a.staffId().toLowerCase().contains(find)) // matching the search
            .sorted(java.util.Comparator.comparing((Auth.User a) -> !Auth.isSystem(a)).thenComparing(a -> a.role().equals("applicant")) // system administrator, then staff,
                .thenComparing(Auth.User::role).thenComparing(Auth.User::email)).toList(); // then students, each in email order
        StringBuilder admins = new StringBuilder();                                      // one table row per account
        for (Auth.User a : accounts.subList(0, Math.min(100, accounts.size())))          // at most 100 rows at a time
            admins.append("<tr><td>").append(esc(a.name())).append("<td><b>").append(esc(a.email())).append("</b><td>") // name and login email
                .append(Auth.isSystem(a) ? "System administrator" : a.role().equals("admin") ? "Bursary office administrator" : office(a)) // account type
                .append("<td>").append(esc(a.staffId()))                                  // staff ID (students have none)
                .append("<td><form class=row method=post action=/set-password autocomplete=off><input type=hidden name=role value=").append(a.role()) // which account
                .append("><input type=hidden name=email value='").append(esc(a.email())).append("'>") // by its login email
                .append("<label class=pwset><input name=password type=password minlength=8 required placeholder='New password' autocomplete=new-password></label><button class=small>Set password</button></form>"); // the new password
        String staff = !Auth.isSystem(u) ? "" : "<section class=card><h2>All accounts (" + Auth.users.size() + ")</h2>" // only the system administrator sees this card
            + "<form class=row><label>Find an account<input name=acct value='" + esc(find) + "' placeholder='email, name or staff ID'></label><button>Search</button></form>" // search box
            + "<div class=scroll><table><tr><th>Name<th>Login email<th>Account type<th>Staff ID<th>Set a new password" // table header
            + (admins.length() == 0 ? "<tr><td colspan=5 class=empty>No accounts found." : admins) + "</table></div>" // the rows, or a note
            + (accounts.size() > 100 ? "<p class=hint>Showing the first 100 of " + accounts.size() + ". Search to find others.</p>" : "") // when there are more
            + "<p class=hint>Passwords are stored only as one-way hashes, so nobody, including the system, can read an existing password. " // why passwords are not shown
            + "To give someone access, set a new password here and tell them; they are signed out everywhere.</p>"
            + "<h3>Sign up a new staff member</h3><form method=post action=/admins autocomplete=off><div class=grid>" // create-account form opens
            + "<label>Office<select name=office><option value=admin>Bursary office (administrator)<option value=finance>Finance office (tracks payments)<option value=audit>Audit department (read-only oversight)</select></label>" // which kind of staff account
            + "<label>Full name<input name=name required maxlength=60></label>"          // new administrator's name
            + "<label>Email<input name=email type=email required maxlength=100 autocomplete=off></label>" // new administrator's login
            + "<label>Password (8 characters or more)<input name=password type=password minlength=8 required autocomplete=new-password></label></div>" // their password
            + "<div class=row><button>Create staff account</button></div>"               // create button
            + "<p class=hint>The staff ID is made automatically from the first three letters of the name.</p></form></section>"; // how the ID is chosen
        page(x, u, msg + full + "<section class=card><h2>Allocation</h2><form class=row>" // full-course alert, then allocation section and toolbar
            + "<label>Sponsor budget<input name=budget type=number min=0 step=any value='" + (budget > 0 ? plain(budget) : "") + "'></label>" // budget box
            + "<label class=tick><input type=checkbox name=names" + (names ? " checked" : "") + "> Show names</label>" // blind review switch
            + "<button>Apply</button><a class='btn alt' href=/audit.csv>Download audit CSV</a></form><div class=tiles>" // apply button and audit link
            + tile("" + (yes + no), "Applications") + tile("" + yes, "Qualify") + tile("" + no, "Do not qualify") // how the system sorted them
            + tile("" + waiting, "On a course waiting list")                             // qualify, but the course is full
            + tile(budget > 0 ? "" + funded : "-", "Funded") + tile(budget > 0 ? fmt("%,.0f", left) : "-", "Unallocated") // awards and money left
            + tile("" + moves, "Change of course requests") + tile("" + returns, "Reinstatement requests") // requests waiting for a decision
            + tile("" + Store.flags.size(), "Possible forgeries flagged")                // applications sharing a key, receipt or file
            + "</div>" + pager + "</section><section class=card><h2>Students who qualify (" + yes + ")</h2>" // page links, then the first group
            + head + (pass.length() == 0 ? none : pass) + "</table></div></section>"     // its table
            + "<section class=card><h2>Students who do not qualify or whose bursary is withdrawn (" + no + ")</h2>" // second group
            + head + (fail.length() == 0 ? none : fail) + "</table></div></section>" + requests + contactCard() + courseCard + staff); // its table, the requests, contacts, then courses and accounts
    }

    // ---------- actions ----------

    static void login(HttpExchange x, Auth.User u) throws IOException {                  // handles the sign-in form
        Map<String, String> f = form(x);                                                 // read the form
        String email = f.getOrDefault("email", "").trim().toLowerCase();                 // the email typed in
        String role = List.of("admin", "finance", "audit").contains(f.get("role")) ? f.get("role") : "applicant"; // which kind of account: Bursary office, Finance office or Student
        redirect(x, signIn(x, email, f.getOrDefault("password", ""), role) ? "/"         // right details: on to that kind of page
            : "/?e=1&t=" + (role.equals("applicant") ? "si" : "ai"));                    // wrong details: back to the same form
    }

    static void register(HttpExchange x, Auth.User u) throws IOException {               // handles the create-account form
        Map<String, String> f = form(x);                                                 // read the form
        int e = account(f, "applicant");                                                 // students can only ever become applicants
        if (e != 0) redirect(x, "/?e=" + e + "&t=su");                                             // failed: show why on the sign-in page
        else { signIn(x, f.get("email").trim().toLowerCase(), f.get("password"), "applicant"); redirect(x, "/"); } // success: signed in straight away
    }

    static void courses(HttpExchange x, Auth.User u) throws IOException {                // an administrator adds a course or changes its limit
        Map<String, String> f = form(x);                                                 // read the form
        String name = clean(f.get("name"));                                              // keep only safe name characters
        int places = Integer.parseInt(f.get("places").trim());                           // how many qualifying students to accept
        String y = f.getOrDefault("years", "").trim();                                   // its length, which may be left empty
        int years = y.isEmpty() ? 0 : Integer.parseInt(y);                               // 0 keeps the length already known
        if (name.isEmpty() || name.length() > 90 || places < 0 || places > 1_000_000 || years < 0 || years > 8) throw new IllegalArgumentException(); // refuse nonsense
        Store.setCourse(name, places, years);                                            // save it and rework the waiting lists
        Store.log(u.staffId(), "set " + name + " to " + places + " places" + (years > 0 ? ", " + years + " years" : "")); // recorded for the auditors
        redirect(x, "/?m=8");                                                            // back to the back end with a confirmation
    }

    static void setPassword(HttpExchange x, Auth.User u) throws IOException {            // the system administrator sets a new password for any account
        if (!Auth.isSystem(u)) { redirect(x, "/"); return; }                             // ordinary administrators may not
        Map<String, String> f = form(x);                                                 // read the form
        boolean ok = Auth.setPassword(f.getOrDefault("role", ""), f.getOrDefault("email", "").trim().toLowerCase(), f.getOrDefault("password", "")); // change it
        if (ok) Store.log(u.staffId(), "set a new password for " + f.get("role") + " account " + f.get("email").trim().toLowerCase()); // recorded for the auditors
        redirect(x, "/?m=" + (ok ? "12" : "13"));                                        // back with the outcome
    }

    static void addAdmin(HttpExchange x, Auth.User u) throws IOException {               // the system administrator signs up a new administrator
        if (!Auth.isSystem(u)) { redirect(x, "/"); return; }                             // ordinary administrators may not create accounts
        Map<String, String> f = form(x);                                                 // read the form
        String role = List.of("finance", "audit").contains(f.get("office")) ? f.get("office") : "admin"; // Bursary office, Finance office or Audit department
        int e = account(f, role);                                                        // the staff ID is created automatically from the name
        Auth.User made = e == 0 ? Auth.users.get(Auth.key(role, f.get("email").trim().toLowerCase())) : null; // the account just created
        if (made != null) Store.log(u.staffId(), "created " + office(made) + " account for " + made.email() + " (staff ID " + made.staffId() + ")"); // recorded for the auditors
        redirect(x, "/?m=" + e + (made == null ? "" : "&id=" + made.staffId()));         // back to the back end with the outcome and the new staff ID
    }

    static void forgot(HttpExchange x, Auth.User u) throws IOException {                 // step 1 of a reset: email a code
        String email = form(x).getOrDefault("email", "").trim().toLowerCase();           // the account to reset
        String code = email.matches(EMAIL) ? Auth.startReset(email) : null;              // null when there is no such account or one was just sent
        if (code != null) Mail.send(email, "Your KIU bursary password reset code",       // email it in the background
            "Your password reset code is " + code + "\n\nIt expires in 10 minutes. If you did not ask for it, ignore this email."); // the message
        redirect(x, "/?e=7&t=rp&email=" + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8)); // same answer either way, so nobody can test which emails exist
    }

    static void reset(HttpExchange x, Auth.User u) throws IOException {                  // step 2 of a reset: check the code, set the password
        Map<String, String> f = form(x);                                                 // read the form
        String email = f.getOrDefault("email", "").trim().toLowerCase(), pass = f.getOrDefault("password", ""); // account and new password
        boolean ok = pass.length() >= 8 && Auth.finishReset(email, f.getOrDefault("code", "").trim(), pass); // right code within 10 minutes
        redirect(x, ok ? "/?e=9" : "/?e=8&t=rp&email=" + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8)); // sign in, or try again
    }

    static int account(Map<String, String> f, String role) throws IOException { // creates an account: 0 ok, 2 duplicate, 3 bad details
        String email = f.getOrDefault("email", "").trim().toLowerCase(), pass = f.getOrDefault("password", ""); // login details
        String name = clean(f.get("name"));                                              // keep only safe name characters
        if (name.isEmpty() || name.length() > 60 || email.length() > 100 || !email.matches(EMAIL) || pass.length() < 8) return 3; // bad details
        return Auth.add(email, name, role, pass) ? 0 : 2;                       // saved, or the email is already registered
    }

    static boolean signIn(HttpExchange x, String email, String pass, String role) {      // checks a password and starts a session
        String token = Auth.login(email, pass, role);                                    // null when the details are wrong
        if (token != null) x.getResponseHeaders().add("Set-Cookie", "sid=" + token + "; Path=/; HttpOnly; SameSite=Strict"); // session cookie
        return token != null;                                                            // true when signed in
    }

    static void logout(HttpExchange x, Auth.User u) throws IOException {                 // handles the sign-out button
        Auth.logout(x);                                                                  // forget the session on the server
        x.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"); // delete the cookie
        redirect(x, "/");                                                                // back to the sign-in page
    }

    static void apply(HttpExchange x, Auth.User u) throws IOException {                  // saves the applicant's own application
        UPLOADS.acquireUninterruptibly();                                                // wait for a free upload slot so memory cannot run out
        try { applyNow(x, u); } finally { UPLOADS.release(); }                           // do the work, then always free the slot
    }

    static void applyNow(HttpExchange x, Auth.User u) throws IOException {               // reads, checks and stores one application
        Map<String, byte[]> files = new HashMap<>();                                     // uploaded documents by field name
        Map<String, String> f = upload(x, files);                                        // read the form and its files
        Applicant old = Store.byEmail(u.email());                                        // earlier application, found instantly
        int id = Store.idFor(u.email());                                                 // one reference number per account
        long time = old != null ? old.submitted() : System.currentTimeMillis();          // keep the original place in the queue
        String course = Store.courses.containsKey(f.get("course")) ? f.get("course") : ""; // course must be one the office listed (empty is refused)
        int years = Store.durations.getOrDefault(course, Integer.parseInt(f.get("years"))); // the office's course length wins over the student's
        Applicant a = new Applicant(id, u.name(), u.email(), phone(f.get("phone")),      // name and email come from the account
            clean(f.get("parentName")), phone(f.get("parentPhone")), Integer.parseInt(f.get("parentJob")), // parent or guardian details
            clean(f.get("kinName")), phone(f.get("kinPhone")), Integer.parseInt(f.get("guardian")), // next of kin and family status
            Double.parseDouble(f.get("fees")), years, Double.parseDouble(f.get("paid")), // tuition, course length, slip amount
            false, false, false, false, time,                                            // only admins tick originals as seen
            f.getOrDefault("year", "").trim(), Integer.parseInt(f.get("intake")), Integer.parseInt(f.get("campus")), course, // academic year, intake, campus, course
            old != null ? old.feedback() : "",                                           // earlier feedback is kept
            code(f.get("regNo")), code(f.get("receipt")), f.getOrDefault("recDate", "").trim(), // registration number, receipt number, recommendation date
            false, 0,                                                                    // names and award letter must be checked again
            old != null ? old.standing() : 0, old != null ? old.standingNote() : "",     // standing is kept
            old != null ? old.newCourse() : "", old != null ? old.history() : "",        // as are the course request and the record
            0, 0, "");                                                                   // Finance has not checked this slip yet
        boolean same = old != null && old.receipt().equals(a.receipt()) && old.paid() == a.paid(); // slip number and amount unchanged?
        if (same) a = a.withPayment(old.payStatus(), old.payAmount(), old.payNote(), "Application updated: verification and award letter reset"); // keep Finance's check
        else a = a.withCourseRequest(a.newCourse(), old == null ? "Application submitted"  // a new slip waits for Finance
            : "Application updated: verification, award letter and Finance check reset"); // one line in the record
        if (!a.valid()) throw new IllegalArgumentException();                            // refuse out-of-range values
        for (String k : DOCS) {                                                          // check every document before saving anything
            byte[] b = files.get(k);                                                     // the new upload, if any
            if (b == null ? (old == null || docFile(id, k) == null)                      // none sent: allowed only when one is already stored
                : (b.length > 3_000_000 || !TYPES.containsKey(f.get(k + ".ext")))) throw new IllegalArgumentException(); // too big or wrong type
        }
        Path dir = Files.createDirectories(folder(id));                                  // this application's upload folder
        for (String k : files.keySet()) if (List.of(DOCS).contains(k)) {                 // each new document
            for (String e : TYPES.keySet()) Files.deleteIfExists(dir.resolve(k + "." + e)); // remove the copy it replaces
            Files.write(dir.resolve(k + "." + f.get(k + ".ext")), files.get(k));         // store under a fixed safe name
            Store.fingerprint(id, k, files.get(k));                                      // remember it so a copy in another application is spotted
        }
        Store.put(a);                                                                    // save: one appended line, no full rewrite
        redirect(x, "/?ok=1");                                                           // back to the applicant page
    }

    static void verify(HttpExchange x, Auth.User u) throws IOException {                 // admin records which originals were seen
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id"));                                          // which application
        Store.change(id, a -> a.withChecks(f.containsKey(DOCS[0]), f.containsKey(DOCS[1]), // ticked boxes are present;
            f.containsKey(DOCS[2]), f.containsKey(DOCS[3]), f.containsKey("namesok")));  // only this application is touched and saved
        redirect(x, "/?" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));       // back to the same ranking view
    }

    static void feedback(HttpExchange x, Auth.User u) throws IOException {               // admin sends feedback to one student
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id"));                                          // which application
        String t = f.getOrDefault("text", "").strip();                                   // the message, without stray spaces
        String text = t.substring(0, Math.min(500, t.length()));                         // at most 500 characters
        Store.change(id, a -> a.withFeedback(text));                                     // attach it to that application only and save
        redirect(x, "/?" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));       // back to the same view
    }

    static void changeCourse(HttpExchange x, Auth.User u) throws IOException {           // a student asks to move to another course
        String c = form(x).getOrDefault("course", "");                                   // the course wanted
        Applicant a = Store.byEmail(u.email());                                          // the student's own application
        if (a == null || !Store.courses.containsKey(c) || c.equals(a.course())) throw new IllegalArgumentException(); // only a real, different, listed course
        Store.change(a.id(), o -> o.withCourseRequest(c, "Asked to change course from " + o.course() + " to " + c)); // saved and recorded
        redirect(x, "/?cc=1");                                                           // back with a confirmation
    }

    static void courseDecision(HttpExchange x, Auth.User u) throws IOException {         // the office approves or declines a course change
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id"));                                          // which application
        boolean yes = "1".equals(f.get("ok"));                                           // approve or decline
        Store.change(id, a -> a.newCourse().isEmpty() ? a : yes                          // nothing to decide when no request waits
            ? a.withCourse(a.newCourse(), Store.durations.getOrDefault(a.newCourse(), a.years()), // move, using the new course's length
                "Change of course from " + a.course() + " to " + a.newCourse() + " approved by " + u.staffId()) // and record who approved it
            : a.withCourseRequest("", "Change of course to " + a.newCourse() + " declined by " + u.staffId())); // or record the refusal
        tell(id, "KIU bursary: change of course " + (yes ? "approved" : "declined"),     // email the student
            "Your request to change course has been " + (yes ? "approved. A new award letter will be issued for your new course." : "declined. You remain on your current course.")); // with what it means
        redirect(x, "/?m=10&" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));  // back to the same view
    }

    static void withdraw(HttpExchange x, Auth.User u) throws IOException {               // the office withdraws a bursary
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id").trim());                                   // which application
        String why = f.getOrDefault("text", "").strip();                                 // the reason the student will see
        if (why.isEmpty() || why.length() > 300) throw new IllegalArgumentException();   // a reason is required
        if (Store.get(id) == null) { redirect(x, "/?m=11"); return; }                    // unknown reference number
        Store.change(id, a -> a.withStanding(1, why, "Bursary withdrawn by " + u.staffId() + ": " + why)); // withdrawn, letter cancelled, recorded
        tell(id, "KIU bursary: your bursary has been withdrawn", "Reason: " + why        // email the student
            + "\n\nIf the reason no longer applies, sign in and request reinstatement with a supporting document."); // and what they can do
        redirect(x, "/?m=10&" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));  // back to the same view
    }

    static void reinstate(HttpExchange x, Auth.User u) throws IOException {              // a student asks for a withdrawn bursary back
        UPLOADS.acquireUninterruptibly();                                                // the request carries a file, so take an upload slot
        try {                                                                            // always free the slot below
            Map<String, byte[]> files = new HashMap<>();                                 // the supporting document
            Map<String, String> f = upload(x, files);                                    // read the form and its file
            Applicant a = Store.byEmail(u.email());                                      // the student's own application
            byte[] b = files.get("reinstate");                                           // the evidence
            String text = f.getOrDefault("text", "").strip(), ext = f.get("reinstate.ext"); // the explanation and the file type
            int r = Integer.parseInt(f.get("reason"));                                   // the reason category
            if (a == null || (a.standing() != 1 && a.standing() != 4) || b == null || b.length > 3_000_000 // only a withdrawn or declined bursary, with evidence
                || !TYPES.containsKey(ext) || text.isEmpty() || text.length() > 500 || r < 0 || r >= REASONS.length) throw new IllegalArgumentException(); // checked
            Path dir = Files.createDirectories(folder(a.id()));                          // this application's upload folder
            for (String e : TYPES.keySet()) Files.deleteIfExists(dir.resolve("reinstate." + e)); // remove any earlier evidence
            Files.write(dir.resolve("reinstate." + ext), b);                             // store under a fixed safe name
            String note = REASONS[r] + ": " + text;                                      // the case as the office will read it
            Store.change(a.id(), o -> o.withStanding(2, note, "Reinstatement requested: " + REASONS[r])); // waiting for a decision, recorded
            redirect(x, "/?rs=1");                                                       // back with a confirmation
        } finally { UPLOADS.release(); }                                                 // free the slot
    }

    static void reinstateDecision(HttpExchange x, Auth.User u) throws IOException {      // the office approves or declines a reinstatement
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id"));                                          // which application
        boolean yes = "1".equals(f.get("ok"));                                           // approve or decline
        String note = f.getOrDefault("text", "").strip();                                // the office's note to the student
        String said = note.substring(0, Math.min(300, note.length()));                   // at most 300 characters
        Store.change(id, a -> a.standing() != 2 ? a : a.withStanding(yes ? 3 : 4, said,  // only a waiting request is decided
            "Reinstatement " + (yes ? "approved" : "declined") + " by " + u.staffId() + (said.isEmpty() ? "" : ": " + said))); // recorded with the note
        tell(id, "KIU bursary: reinstatement " + (yes ? "approved" : "declined"),        // email the student
            "Your request to reinstate your bursary has been " + (yes ? "approved. You are ranked again with other applicants and an award letter follows once your originals are confirmed." : "declined.") // the outcome
            + (said.isEmpty() ? "" : "\n\nNote from the bursary office: " + said));      // with the office's note
        redirect(x, "/?m=10&" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));  // back to the same view
    }

    static void issue(HttpExchange x, Auth.User u) throws IOException {                  // the office issues an award letter
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id"));                                          // which application
        Applicant a = Store.get(id);                                                     // the application
        if (a == null || !a.confirmed() || Store.waiting(a)) throw new IllegalArgumentException(); // only an eligible, verified student with a place
        Store.change(id, o -> o.withLetter(System.currentTimeMillis(), "Award letter issued by " + u.staffId() // dated and recorded
            + ": " + Math.round(o.rate() * 100) + "% of " + Scorer.money(o.fees())));     // with the rate it states
        tell(id, "KIU bursary: your award letter is ready", "Your bursary award letter has been issued. Sign in to open and print it.\n\n" // email the student
            + "Pay only your part of tuition (" + Scorer.money(a.share()) + "). The bursary part (" + Scorer.money(a.award()) + ") is removed by the university and must not be paid."); // and the payment rule
        redirect(x, "/?m=10&" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));  // back to the same view
    }

    static void letter(HttpExchange x, Auth.User u) throws IOException {                 // the printable award letter
        int id = Integer.parseInt(params(x.getRequestURI().getRawQuery()).get("id"));    // which application
        Applicant a = Store.get(id);                                                     // the application
        boolean allowed = u != null && a != null && (List.of("admin", "audit").contains(u.role()) || a.email().equals(u.email())); // admins, auditors, or the owner
        if (!allowed || a.letter() == 0) { send(x, 404, "text/plain", "Award letter not found"); return; } // nothing to show
        int uni = (int) Math.round(a.rate() * 100);                                      // 40 or 50
        page(x, u, "<section class='card letter'><p class=hint>Ref. KIU/BUR/" + a.id() + " &middot; " + Scorer.when(a.letter()) + "</p>" // reference and date
            + "<h2>Bursary award letter</h2><p>Dear <b>" + esc(a.name()) + "</b> (registration number <b>" + esc(a.regNo()) + "</b>),</p>" // addressed by name and key
            + "<p>Kampala International University is pleased to award you a bursary for the " + esc(a.year()) + " academic year, " // the award
            + Applicant.INTAKES[a.intake()] + " intake, " + Applicant.CAMPUSES[a.campus()] + ", on the course <b>" + esc(a.course()) + "</b>.</p>" // for which course
            + "<table><tr><td>Tuition<td class=n>" + Scorer.money(a.fees())              // the tuition
            + "<tr><td>Course length<td class=n>" + a.years() + " years"                  // the length that sets the rate
            + "<tr><td>Bursary: removed by the university (" + uni + "%)<td class=n><b>" + Scorer.money(a.award()) + "</b>" // the bursary
            + "<tr><td>Your part of tuition (" + (100 - uni) + "%)<td class=n><b>" + Scorer.money(a.share()) + "</b></table>" // the student's part
            + "<p><b>Pay only your part of tuition. Do not pay the bursary amount.</b></p>" // the rule that stops early payments
            + "<p class=hint>Check that your name and registration number above are spelled exactly as on your admission letter. " // the misspelling check
            + "If anything is wrong, tell the bursary office before making any payment.</p>" // what to do
            + (office("bursary").isEmpty() ? "" : "<p class=hint><b>Bursary office:</b> " + office("bursary").replace("<p>", " ").replace("</p>", " ") + "</p>") // how to tell them
            + "<p>Bursary and Sponsorship Committee<br>Kampala International University</p>" // signed off
            + "<div class=row><button type=button onclick='print()'>Print this letter</button></div></section>"); // print button
    }

    static String decide(String action, int id, String back, boolean note) {             // approve and decline buttons for one request
        return "<form class=checks method=post action=" + action + "><input type=hidden name=id value=" + id // which application
            + "><input type=hidden name=q value='" + back + "'>"                         // remembers the current view
            + (note ? "<textarea name=text rows=2 maxlength=300 placeholder='Note the student sees'></textarea>" : "") // optional note
            + "<span><button class=small name=ok value=1>Approve</button> <button class='small alt' name=ok value=0>Decline</button></span></form>"; // the two choices
    }

    static void tell(int id, String subject, String text) {                              // emails a student about their application
        Applicant a = Store.get(id);                                                     // the application, with its email
        if (a != null) Mail.send(a.email(), subject, "Dear " + a.name() + ",\n\n" + text + "\n\nApplication #" + id + "\nKIU Bursary Office"); // in the background
    }

    static String contact(String key) { return Store.contacts.getProperty(key, "").trim(); } // one saved contact field, or ""

    static String office(String o) {                                                     // one office's contacts as clickable lines
        String phone = contact(o + ".phone"), wa = contact(o + ".whatsapp"), mail = contact(o + ".email"); // how to reach it
        String where = contact(o + ".office"), hours = contact(o + ".hours");            // where it is and when it is open
        return (phone.isEmpty() ? "" : "<p>Call <a href='tel:" + dial(phone) + "'>" + esc(phone) + "</a></p>") // tap to call
            + (wa.isEmpty() ? "" : "<p>WhatsApp <a target=_blank href='https://wa.me/" + whatsapp(wa) + "'>" + esc(wa) + "</a></p>") // tap to chat
            + (mail.isEmpty() ? "" : "<p>Email <a href='mailto:" + esc(mail) + "'>" + esc(mail) + "</a></p>") // tap to write
            + (where.isEmpty() ? "" : "<p>Office: " + esc(where) + "</p>")               // where to go in person
            + (hours.isEmpty() ? "" : "<p>Open: " + esc(hours) + "</p>");                // and when
    }

    static String contacts() {                                                           // the "Contact us" card for students and visitors
        String b = office("bursary"), f = office("finance");                             // both offices
        if (b.isEmpty() && f.isEmpty()) return "";                                       // nothing entered yet: no card
        return "<section class='card contacts'><h2>Contact us</h2><div class=cols2>"     // card opens
            + (b.isEmpty() ? "" : "<div><h3>Bursary office</h3>" + b + "</div>")         // bursaries, documents, feedback
            + (f.isEmpty() ? "" : "<div><h3>Finance office</h3>" + f + "</div>")         // payment slips and receipts
            + "</div><p class=hint>Always quote your reference number or registration number. Ask the Bursary office about applications, documents and award letters, " // what to say
            + "and the Finance office about payment slips and receipts.</p></section>";  // who to ask what
    }

    static String contactCard() {                                                        // the form administrators use to set the contacts
        StringBuilder h = new StringBuilder("<section class=card><h2>Office contacts shown to students</h2><form method=post action=/contacts><div class=grid>"); // card and form open
        for (String[] c : CONTACTS) h.append(optional(c[1], "name=" + c[0].replace('.', '_') + " maxlength=120", contact(c[0]))); // one box per field
        return h.append("</div><div class=row><button>Save contacts</button></div><p class=hint>Students see these on the sign-in page, on their own page and on the award letter, ") // save button
            .append("as tap-to-call, WhatsApp and email links. Leave a box empty to hide that line.</p></form></section>").toString(); // where they appear
    }

    static String reach(String phone, String email) {                                   // one-click links to reach a student or parent
        return (phone.isEmpty() ? "" : "<a href='tel:" + dial(phone) + "'>" + esc(phone) + "</a> &middot; <a target=_blank href='https://wa.me/" + whatsapp(phone) + "'>WhatsApp</a>") // call or chat
            + (email.isEmpty() ? "" : (phone.isEmpty() ? "" : " &middot; ") + "<a href='mailto:" + esc(email) + "'>" + esc(email) + "</a>"); // or write
    }

    static String dial(String phone) { return phone.replaceAll("[^0-9+]", ""); }          // a number a phone can dial

    static String whatsapp(String phone) {                                               // a number in the international form WhatsApp needs
        String d = phone.replaceAll("[^0-9]", "");                                       // digits only
        return d.startsWith("0") ? "256" + d.substring(1) : d;                           // a local Ugandan number gets the 256 country code
    }

    static void saveContacts(HttpExchange x, Auth.User u) throws IOException {           // an administrator saves the office contacts
        Map<String, String> f = form(x), values = new java.util.LinkedHashMap<>();       // read the form; the cleaned values
        for (String[] c : CONTACTS) {                                                    // each field
            String v = f.getOrDefault(c[0].replace('.', '_'), "").replaceAll("[\\r\\n<>]", " ").trim(); // one line, no markup
            boolean bad = v.length() > 120 || !v.isEmpty() && (c[0].endsWith("phone") || c[0].endsWith("whatsapp") ? !v.matches("\\+?[0-9 ()-]{9,20}") // a phone number
                : c[0].endsWith("email") && !v.toLowerCase().matches(EMAIL));            // or an email address
            if (bad) throw new IllegalArgumentException();                               // refuse what cannot be a contact
            values.put(c[0], v);                                                         // keep it
        }
        Store.saveContacts(values);                                                      // save them all
        Store.log(u.staffId(), "updated the office contacts");                           // recorded for the auditors
        redirect(x, "/?m=14");                                                           // back with a confirmation
    }

    static String office(Auth.User u) {                                                  // the name of a user's office, in words
        return switch (u.role()) { case "admin" -> "Administrator"; case "finance" -> "Finance office"; case "audit" -> "Audit department"; default -> "Student"; }; // one per role
    }

    static String code(String s) {                                                       // tidies a registration or receipt number
        return s == null ? "" : s.trim().toUpperCase(Locale.US).replaceAll("\\s+", "");   // capitals, no spaces; Applicant.valid checks the rest
    }

    static void financePage(HttpExchange x, Auth.User u, Map<String, String> q) throws IOException { // Finance office: track payments by slip number
        Store.ranked();                                                                  // make sure the warnings about repeated slips are up to date
        String slip = code(q.get("slip"));                                               // the slip number searched for, tidied like the stored ones
        String st = q.getOrDefault("st", slip.isEmpty() ? "0" : "all").replaceAll("[^0-9a-z]", ""); // which status to list: awaiting check by default
        int[] n = new int[3];                                                            // slips awaiting check, confirmed and rejected
        double total = 0;                                                                // money confirmed as received
        List<Applicant> list = new java.util.ArrayList<>();                              // the slips to show
        for (Applicant a : Store.byId.values()) {                                        // every application
            n[a.payStatus()]++;                                                          // count it by status
            if (a.payStatus() == 1) total += a.payAmount();                              // add up what was received
            if (slip.isEmpty() ? st.equals("all") || st.equals("" + a.payStatus())       // listing by status,
                : a.receipt().contains(slip) || a.regNo().contains(slip)) list.add(a);  // or searching by slip (or registration) number
        }
        list.sort(java.util.Comparator.comparingLong(Applicant::submitted));             // oldest slips first
        String back = "slip=" + java.net.URLEncoder.encode(slip, StandardCharsets.UTF_8) + "&st=" + st; // this view, to return to after saving
        StringBuilder rows = new StringBuilder();                                        // one table row per slip
        for (Applicant a : list.subList(0, Math.min(100, list.size()))) {               // at most 100 rows at a time
            String dup = Store.flags.getOrDefault(a.id(), List.of()).stream().filter(w -> w.startsWith("Receipt")) // a slip number used twice
                .map(w -> "<br><span class='tag no'>&#9888; " + esc(w) + "</span>").collect(java.util.stream.Collectors.joining()); // shown in red
            rows.append("<tr><td><b>").append(esc(a.receipt())).append("</b>").append(dup) // the slip number and any warning
                .append("<br><a target=_blank href='/doc?id=").append(a.id()).append("&f=slip'>view slip</a>") // the uploaded slip
                .append("<td><b>#").append(a.id()).append("</b> ").append(esc(a.regNo())).append("<br>").append(esc(a.name())).append("<br><small>").append(reach(a.phone(), a.email())).append("</small>") // who paid, and how to reach them
                .append("<br><small>").append(esc(a.course())).append("<br>").append(esc(a.year())).append(" &middot; ") // for which course and year
                .append(Applicant.INTAKES[a.intake()]).append(" intake &middot; ").append(Applicant.CAMPUSES[a.campus()]).append("</small>") // intake and campus
                .append("<td class=n>").append(Scorer.money(a.paid()))                   // amount the student says the slip shows
                .append("<td class=n>").append(Scorer.money(a.share())).append("<br><small>").append(Math.round(100 - a.rate() * 100)).append("% of ") // the part the student must pay
                .append(Scorer.money(a.fees())).append("</small><td><span class='tag ").append(a.payStatus() == 1 ? "ok" : a.payStatus() == 2 ? "no" : "wait") // status label
                .append("'>").append(Applicant.PAYMENTS[a.payStatus()]).append("</span>")  // in words
                .append(a.payStatus() == 1 ? "<br><small>" + Scorer.money(a.payAmount()) + " received" + (a.payAmount() < a.share() ? ", short by " + Scorer.money(a.share() - a.payAmount()) : "") + "</small>" : "") // amount and any shortfall
                .append(a.payNote().isEmpty() ? "" : "<br><small>" + esc(a.payNote()) + "</small>") // the Finance note
                .append("<td><form class=checks method=post action=/payment><input type=hidden name=id value=").append(a.id()) // record the check
                .append("><input type=hidden name=q value='").append(back).append("'>")  // remembers the current view
                .append("<label>Amount received<input name=amount type=number min=0 step=any required value='").append(plain(a.payStatus() == 1 ? a.payAmount() : a.paid())).append("'></label>") // what reached the account
                .append("<label>Note<input name=text maxlength=200 placeholder='e.g. bank reference, or why rejected' value='").append(esc(a.payNote())).append("'></label>") // optional note
                .append("<span><button class=small name=ok value=1>Confirm payment</button> <button class='small alt' name=ok value=0 formnovalidate>Reject slip</button></span></form>"); // the two outcomes
        }
        String[] names = {"Awaiting check", "Confirmed", "Rejected"};                    // status filter labels
        StringBuilder filter = new StringBuilder();                                      // links that list slips by status
        for (int i = 0; i < 3; i++) filter.append(" &middot; <a href='/?st=").append(i).append("'>").append(names[i]).append(" (").append(n[i]).append(")</a>"); // one per status
        page(x, u, (q.containsKey("m") ? "<p class='msg good'>Payment check saved. The student has been emailed and sees it on their page.</p>" : "") // confirmation banner
            + "<section class=card><h2>Payment tracking</h2><form class=row>"           // search card
            + "<label>Slip or receipt number<input name=slip value='" + esc(slip) + "' placeholder='e.g. SB-55120' autofocus></label>" // search by slip number
            + "<button>Find slip</button><a class='btn alt' href=/payments.csv>Download payments CSV</a></form><div class=tiles>" // search and download
            + tile("" + n[0], "Slips awaiting check") + tile("" + n[1], "Confirmed") + tile("" + n[2], "Rejected") // counts
            + tile(Scorer.money(total), "Total confirmed as received") + "</div>"        // money received
            + "<p class=hint>Show: <a href='/?st=all'>All slips (" + (n[0] + n[1] + n[2]) + ")</a>" + filter + "</p>" // status links
            + "<p class=hint>Check each slip against the bank statement or Finance records, then confirm the amount actually received or reject a slip that cannot be found. " // what to do
            + "A confirmed amount replaces the student's figure; a rejected slip means the student does not qualify. The bursary office cannot issue an award letter until the payment is confirmed.</p></section>" // its effect
            + "<section class=card><h2>" + (slip.isEmpty() ? "Slips: " + (st.equals("all") ? "all" : names[Integer.parseInt(st)].toLowerCase()) : "Search results for " + esc(slip)) // list heading
            + " (" + list.size() + ")</h2><div class=scroll><table><tr><th>Slip<th>Student<th>Amount on slip<th>Student's part<th>Status<th>Record the check" // table header
            + (rows.length() == 0 ? "<tr><td colspan=6 class=empty>No slips found." : rows) + "</table></div>" // the rows, or a note
            + (list.size() > 100 ? "<p class=hint>Showing the first 100. Search by slip number to find others.</p>" : "") + "</section>"); // when there are more
    }

    static void payment(HttpExchange x, Auth.User u) throws IOException {                // the Finance office records its check of one slip
        Map<String, String> f = form(x);                                                 // read the form
        int id = Integer.parseInt(f.get("id"));                                          // which application
        boolean yes = "1".equals(f.get("ok"));                                           // confirmed or rejected
        double amount = yes ? Double.parseDouble(f.get("amount").trim()) : 0;            // amount received (nothing for a rejected slip)
        String t = f.getOrDefault("text", "").strip();                                   // the note, without stray spaces
        String note = t.substring(0, Math.min(200, t.length()));                         // at most 200 characters
        Applicant a = Store.get(id);                                                     // the application
        if (a == null || amount < 0 || amount >= 1e12) throw new IllegalArgumentException(); // refuse nonsense
        Store.change(id, o -> o.withPayment(yes ? 1 : 2, amount, note, "Payment slip " + o.receipt() // saved and recorded
            + (yes ? " confirmed by Finance (" + u.staffId() + "): " + Scorer.money(amount) + " received" : " rejected by Finance (" + u.staffId() + ")") // what was decided, by whom
            + (note.isEmpty() ? "" : ": " + note)));                                     // with the note
        tell(id, "KIU bursary: payment slip " + (yes ? "confirmed" : "rejected"), yes    // email the student
            ? "The Finance office has confirmed your payment slip " + a.receipt() + ": " + Scorer.money(amount) + " received." // confirmed
                + (amount < a.share() ? " This is less than your part of tuition (" + Scorer.money(a.share()) + "); please pay the balance of " + Scorer.money(a.share() - amount) + "." : "") // and any balance
            : "The Finance office could not confirm your payment slip " + a.receipt() + "." + (note.isEmpty() ? "" : " Note: " + note) // rejected
                + " Please bring your original receipt to the Finance office.");          // what to do
        redirect(x, "/?m=1&" + f.getOrDefault("q", "").replaceAll("[^\\w=&.%-]", ""));   // back to the same view
    }

    static void payments(HttpExchange x, Auth.User u) throws IOException {               // every slip and its status, for the Finance office
        x.getResponseHeaders().add("Content-Disposition", "attachment; filename=payments.csv"); // make the browser download it
        x.getResponseHeaders().add("Content-Type", "text/csv; charset=utf-8");           // it is a spreadsheet file
        x.sendResponseHeaders(200, 0);                                                   // length unknown: stream it row by row
        try (Writer c = new BufferedWriter(new OutputStreamWriter(x.getResponseBody(), StandardCharsets.UTF_8))) { // never holds the whole file in memory
            Store.ranked();                                                              // make sure the warnings are up to date
            c.append("slip_no,reference,registration_no,name,email,course,academic_year,intake,campus,amount_on_slip,student_share,status,amount_received,finance_note,warnings\n"); // header
            for (Applicant a : Store.byId.values())                                      // one line per application
                c.append(a.receipt()).append(',').append("" + a.id()).append(',').append(a.regNo()).append(',').append(a.name()).append(',').append(a.email()) // slip and student
                 .append(',').append(a.course()).append(',').append(a.year()).append(',').append(Applicant.INTAKES[a.intake()]).append(',').append(Applicant.CAMPUSES[a.campus()]) // course and intake
                 .append(',').append(plain(a.paid())).append(',').append(plain(a.share())).append(',').append(Applicant.PAYMENTS[a.payStatus()]) // amounts and status
                 .append(',').append(a.payStatus() == 1 ? plain(a.payAmount()) : "").append(",\"").append(quote(a.payNote())) // amount received and note
                 .append("\",\"").append(quote(String.join("; ", Store.flags.getOrDefault(a.id(), List.of())))).append("\"\n"); // warnings
        }
    }

    static void auditPage(HttpExchange x, Auth.User u, Map<String, String> q) throws IOException { // Audit department: sees everything, changes nothing
        String find = q.getOrDefault("find", "").trim();                                 // search: reference, registration or slip number, name, email or staff ID
        String key = find.toLowerCase(Locale.US).replace("#", "");                       // the same text for matching
        List<Scorer.Result> all = Store.ranked();                                        // every application, best first, with warnings up to date
        int letters = 0, confirmed = 0, rejected = 0, withdrawn = 0, waiting = 0, eligible = 0; // summary counts
        double received = 0;                                                             // money Finance confirmed as received
        StringBuilder rows = new StringBuilder();                                        // one table row per matching application
        int rank = 0, shown = 0, matches = 0;                                            // position in the ranking, rows shown, rows matching
        for (Scorer.Result r : all) {                                                    // walk the ranking
            Applicant a = r.a();                                                         // the application
            rank++;                                                                      // its overall position
            boolean wait = Store.waitlist.contains(a.id());                              // on a course waiting list?
            if (a.letter() > 0) letters++;                                               // count the award letters,
            if (a.payStatus() == 1) { confirmed++; received += a.payAmount(); }          // the confirmed payments,
            if (a.payStatus() == 2) rejected++;                                          // the rejected slips,
            if (!a.active()) withdrawn++;                                                // the withdrawn bursaries,
            if (wait) waiting++;                                                         // the waiting lists
            else if (a.eligible()) eligible++;                                           // and the students holding a place
            if (!key.isEmpty() && !(String.valueOf(a.id()).equals(key) || (a.regNo() + " " + a.receipt() + " " + a.name() + " " + a.email()).toLowerCase(Locale.US).contains(key) // not this application
                || a.history().toLowerCase(Locale.US).contains(key))) continue;          // (a staff ID matches the events it appears in)
            if (++matches > 100) continue;                                               // at most 100 rows at a time
            shown++;                                                                     // one more row
            String group = !a.active() ? Applicant.STANDINGS[a.standing()] : !a.qualifies() ? "Does not qualify" // which group the system put it in
                : wait ? "Waiting list" : a.verified() ? "Confirmed" : "Qualifies, checks pending"; // in words
            StringBuilder docs = new StringBuilder();                                    // links to every uploaded document
            for (int i = 0; i < 4; i++) docs.append("<a target=_blank href='/doc?id=").append(a.id()).append("&f=").append(DOCS[i]).append("'>").append(LABELS[i]).append("</a><br>"); // the four documents
            if (docFile(a.id(), "reinstate") != null) docs.append("<a target=_blank href='/doc?id=").append(a.id()).append("&f=reinstate'>Reinstatement evidence</a>"); // and any reinstatement evidence
            rows.append("<tr><td class=n>").append(rank)                                 // overall rank
                .append("<td><b>#").append(a.id()).append("</b> ").append(esc(a.regNo())).append("<br>").append(esc(a.name())) // who
                .append("<br><small>").append(esc(a.course())).append("<br>").append(esc(a.year())).append(" &middot; ").append(Applicant.INTAKES[a.intake()]).append(" intake</small>") // course and intake
                .append("<td class=n><span class=total>").append(Scorer.f(r.total())).append("</span>") // score
                .append("<td>").append(group)                                            // decision group
                .append(a.verified() ? "" : "<br><small>originals " + (a.originals() ? "checked" : "not all checked") + "</small>") // what is still missing
                .append("<td>").append(esc(a.receipt())).append("<br><small>").append(Applicant.PAYMENTS[a.payStatus()]) // slip and Finance check
                .append(a.payStatus() == 1 ? ": " + Scorer.money(a.payAmount()) : "").append(" of ").append(Scorer.money(a.share())).append(" due</small>") // amounts
                .append("<td>").append(a.letter() > 0 ? "<a target=_blank href='/letter?id=" + a.id() + "'>Issued " + Scorer.when(a.letter()) + "</a>" : "Not issued") // award letter
                .append("<br><small>").append(Math.round(a.rate() * 100)).append("% = ").append(Scorer.money(a.award())).append("</small>") // its value
                .append("<td>");                                                          // warnings
            for (String w : Store.flags.getOrDefault(a.id(), List.of())) rows.append("<span class='tag no'>&#9888; ").append(esc(w)).append("</span><br>"); // each in red
            rows.append("<td><small>").append(docs).append("</small>")                    // documents
                .append("<td class=why><details><summary>").append(a.history().isEmpty() ? 0 : a.history().split("\n").length).append(" events</summary>") // the record, folded
                .append(esc(a.history()).replace("\n", "<br>")).append("</details>");    // one dated line per event
        }
        List<long[]> order = new java.util.ArrayList<>();                                // [time, index] of every event, for sorting
        List<String> text = new java.util.ArrayList<>();                                 // the events in words
        java.time.format.DateTimeFormatter read = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.US); // how record lines are dated
        for (Applicant a : Store.byId.values()) for (String line : a.history().split("\n")) { // every line of every application's record
            if (line.length() < 19) continue;                                            // empty record
            try {                                                                        // a line that cannot be read is skipped
                long t = java.time.LocalDateTime.parse(line.substring(0, 17), read).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); // its time
                order.add(new long[]{t, text.size()}); text.add("#" + a.id() + " " + esc(a.regNo()) + ": " + esc(line.substring(19))); // and what happened
            } catch (RuntimeException e) { }                                             // not a dated line
        }
        for (String line : Store.activity) {                                             // staff actions not tied to one application
            int bar = line.indexOf('|');                                                 // "time|who: what"
            if (bar < 0) continue;                                                       // damaged line
            order.add(new long[]{Long.parseLong(line.substring(0, bar)), text.size()}); text.add("Staff " + esc(line.substring(bar + 1))); // its time and words
        }
        order.sort((m, n) -> m[0] != n[0] ? Long.compare(n[0], m[0]) : Long.compare(n[1], m[1])); // newest first; within the same minute, the later entry first
        StringBuilder trail = new StringBuilder();                                       // the activity trail rows
        int n = 0;                                                                       // events shown
        for (long[] e : order) {                                                         // each event
            String words = text.get((int) e[1]);                                         // its words
            if (!key.isEmpty() && !words.toLowerCase(Locale.US).contains(key)) continue; // only those matching the search
            if (++n > 200) break;                                                        // at most 200
            trail.append("<tr><td>").append(Scorer.when(e[0])).append("<td>").append(words); // when and what
        }
        page(x, u, "<section class=card><h2>Audit overview</h2><form class=row>"        // overview card
            + "<label>Search<input name=find value='" + esc(find) + "' placeholder='reference, reg. no., slip no., name, email or staff ID'></label>" // search box
            + "<button>Search</button><a class='btn alt' href=/audit.csv>Download audit CSV</a><a class='btn alt' href=/payments.csv>Download payments CSV</a></form><div class=tiles>" // downloads
            + tile("" + all.size(), "Applications") + tile("" + eligible, "Holding a course place") + tile("" + waiting, "On a waiting list") // how they were sorted
            + tile("" + letters, "Award letters issued") + tile("" + confirmed, "Payments confirmed by Finance") + tile(Scorer.money(received), "Confirmed as received") // money
            + tile("" + rejected, "Slips rejected by Finance") + tile("" + Store.flags.size(), "Possible forgeries flagged") + tile("" + withdrawn, "Withdrawn or awaiting reinstatement") + "</div>" // risks
            + "<p class=hint>The Audit department can see every application, document, decision and staff action, but cannot change anything.</p></section>" // read only
            + "<section class=card><h2>Applications" + (find.isEmpty() ? "" : " matching " + esc(find)) + " (" + matches + ")</h2><div class=scroll><table>" // applications card
            + "<tr><th>Rank<th>Student<th>Score<th>Decision<th>Finance check<th>Award letter<th>Warnings<th>Documents<th>Record" // header
            + (shown == 0 ? "<tr><td colspan=9 class=empty>No applications found." : rows) + "</table></div>" // the rows
            + (matches > 100 ? "<p class=hint>Showing the first 100. Search to narrow the list.</p>" : "") + "</section>" // when there are more
            + "<section class=card><h2>Activity trail" + (find.isEmpty() ? "" : " matching " + esc(find)) + "</h2>" // every staff action
            + "<p class=hint>Every decision, check and change, newest first, with the staff ID of whoever made it. Search a staff ID to see one person's actions.</p>" // how to use it
            + "<div class=scroll><table><tr><th>When<th>What happened" + (trail.length() == 0 ? "<tr><td colspan=2 class=empty>No activity yet." : trail) // the rows
            + "</table></div>" + (n > 200 ? "<p class=hint>Showing the latest 200 events.</p>" : "") + "</section>"); // when there are more
    }

    static void doc(HttpExchange x, Auth.User u) throws IOException {                    // opens one uploaded document
        Map<String, String> q = params(x.getRequestURI().getRawQuery());                 // which application and which document
        int id = Integer.parseInt(q.get("id"));                                          // application reference
        Applicant a = Store.get(id);                                                     // the application it belongs to, found instantly
        Path p = List.of(DOCS).contains(q.get("f")) || "reinstate".equals(q.get("f")) ? docFile(id, q.get("f")) : null; // only the four documents and the reinstatement evidence
        boolean allowed = u != null && a != null && (u.role().equals("admin") || a.email().equals(u.email()) // admins, or the owner,
            || u.role().equals("audit")                                                  // or the Audit department (read only),
            || u.role().equals("finance") && "slip".equals(q.get("f")));                 // or the Finance office for payment slips only
        if (!allowed || p == null) { send(x, 404, "text/plain", "Document not found"); return; } // nothing to show
        String name = p.getFileName().toString();                                        // stored file name
        x.getResponseHeaders().add("X-Content-Type-Options", "nosniff");                 // browser must trust our file type only
        x.getResponseHeaders().add("Cache-Control", "no-store");                         // private documents are never cached
        send(x, 200, TYPES.get(name.substring(name.lastIndexOf('.') + 1)), Files.readAllBytes(p)); // deliver the file
    }

    static Path docFile(int id, String key) {                                            // finds a stored document, or null
        for (String e : TYPES.keySet()) {                                                // try each allowed file type
            Path p = folder(id).resolve(key + "." + e);                                  // where it would be
            if (Files.exists(p)) return p;                                               // found it
        }
        return null;                                                                     // not uploaded
    }

    static Path folder(int id) {                                                         // upload folder of one application
        return Path.of("uploads", "" + id / 1000, "" + id);                              // grouped by thousands so no folder grows huge
    }

    static Map<String, String> upload(HttpExchange x, Map<String, byte[]> files) throws IOException { // reads a form that carries files
        String type = x.getRequestHeaders().getFirst("Content-Type");                    // "multipart/form-data; boundary=..."
        String sep = "--" + type.substring(type.indexOf("boundary=") + 9);               // the line that separates the parts
        String body = new String(x.getRequestBody().readNBytes(14_000_000), StandardCharsets.ISO_8859_1); // 1 byte = 1 character, 14 MB at most
        Map<String, String> f = new HashMap<>();                                         // ordinary text fields
        for (String part : body.split(Pattern.quote(sep))) {                             // each field or file
            int cut = part.indexOf("\r\n\r\n");                                          // blank line between headers and content
            if (cut < 0 || part.length() < cut + 6) continue;                            // not a real part
            String head = part.substring(0, cut), data = part.substring(cut + 4, part.length() - 2); // headers, and content without its line end
            Matcher n = Pattern.compile("[; ]name=\"([^\"]*)\"").matcher(head);          // the field name
            Matcher fn = Pattern.compile("filename=\"([^\"]*)\"").matcher(head);         // present only for files
            if (!n.find()) continue;                                                     // unnamed part: ignore
            if (!fn.find()) f.put(n.group(1), new String(data.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)); // text field
            else if (!data.isEmpty()) {                                                  // a file was actually chosen
                files.put(n.group(1), data.getBytes(StandardCharsets.ISO_8859_1));       // exact original bytes
                f.put(n.group(1) + ".ext", fn.group(1).substring(fn.group(1).lastIndexOf('.') + 1).toLowerCase()); // its file type
            }
        }
        return f;                                                                        // hand back the text fields
    }

    static void audit(HttpExchange x, Auth.User u) throws IOException {                  // full record for sponsors
        x.getResponseHeaders().add("Content-Disposition", "attachment; filename=audit.csv"); // make the browser download it
        x.getResponseHeaders().add("Content-Type", "text/csv; charset=utf-8");           // it is a spreadsheet file
        x.sendResponseHeaders(200, 0);                                                   // length unknown: stream it row by row
        try (Writer c = new BufferedWriter(new OutputStreamWriter(x.getResponseBody(), StandardCharsets.UTF_8))) { // never holds the whole file in memory
            c.append("rank,id,name,email,phone,parent_name,parent_phone,parent_occupation,kin_name,kin_phone,family,") // header, contacts
             .append("tuition,course_years,paid,admission_seen,slip_seen,academic_seen,recommendation_seen,submitted_ms,") // header, inputs and checks
             .append("academic_year,intake,campus,course,feedback,registration_no,receipt_no,recommendation_date,recommendation_current,names_match,") // header, keys and checks
             .append("award_letter,standing,course_change_requested,payment_status,amount_received,finance_note,warnings,qualifies,need,verification,total,reason,record\n"); // header, record and outcome columns
            int rank = 0;                                                                // position in the ranking
            for (Scorer.Result r : Store.ranked()) {                                     // one line per application
                Applicant a = r.a();                                                     // the application on this line
                c.append("" + ++rank).append(',').append(a.head())                       // rank and raw inputs
                 .append(",\"").append(quote(a.feedback())).append("\",")                // feedback as readable quoted text
                 .append(a.regNo()).append(',').append(a.receipt()).append(',').append(a.recDate()) // keys and recommendation date
                 .append(',').append("" + a.recCurrent()).append(',').append("" + a.namesMatch()) // recommendation current, names agree
                 .append(',').append(a.letter() > 0 ? Scorer.when(a.letter()) : "").append(',').append(Applicant.STANDINGS[a.standing()]) // letter date and standing
                 .append(',').append(a.newCourse()).append(',').append(Applicant.PAYMENTS[a.payStatus()]).append(',').append(a.payStatus() == 1 ? plain(a.payAmount()) : "") // course request, Finance check
                 .append(",\"").append(quote(a.payNote())).append("\"")                   // and the Finance note
                 .append(",\"").append(quote(String.join("; ", Store.flags.getOrDefault(a.id(), List.of())))) // warnings
                 .append("\",").append(!a.active() ? "withdrawn" : !a.qualifies() ? "no" : Store.waitlist.contains(a.id()) ? "waiting list" : "yes").append(',') // which group the system sorted the student into
                 .append(Scorer.f(r.need())).append(',')                                 // need score
                 .append(Scorer.f(r.verification())).append(',').append(Scorer.f(r.total())) // verification and total
                 .append(",\"").append(quote(r.reason())).append("\",\"")                // quoted reason because it holds commas
                 .append(quote(a.history().replace("\n", " / "))).append("\"\n");        // the dated record of events on one line
            }
        }
    }

    static String quote(String s) { return s.replace("\"", "\"\"").replaceAll("\\s+", " "); } // text made safe inside a quoted CSV cell

    static void asset(HttpExchange x, String file, String type) throws IOException {     // serves the logo or the stylesheet
        byte[] b = ASSETS.computeIfAbsent(file, k -> {                                   // read from disk once, then serve from memory
            try { return Files.readAllBytes(Path.of("web", k)); } catch (IOException e) { return new byte[0]; } // empty when missing
        });
        x.getResponseHeaders().add("Cache-Control", "public, max-age=3600");             // browsers keep it for an hour instead of asking again
        if (b.length > 0) send(x, 200, type, b);                                         // found: deliver it
        else send(x, 404, "text/plain", "Missing " + file);                              // not found: say which file
    }

    // ---------- helpers ----------

    static void page(HttpExchange x, Auth.User u, String body) throws IOException {      // wraps content in the KIU layout
        String who = u == null ? "" : "<form class=user method=post action=/logout><span>" + esc(u.name()) // signed-in user's name
            + " &middot; " + (Auth.isSystem(u) ? "System administrator" : u.role().equals("admin") ? "Administrator " + esc(u.staffId()) : !u.role().equals("applicant") ? office(u) + " " + esc(u.staffId()) : "Student") + "</span><button class=small>Sign out</button></form>"; // role and sign-out
        x.getResponseHeaders().add("Cache-Control", "no-store");                         // never show a private page from cache
        send(x, 200, "text/html; charset=utf-8", "<!doctype html><html lang=en><meta charset=utf-8>" // page start
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"        // fits phones and tablets
            + "<title>KIU Bursary Fairness Scorer</title><link rel=stylesheet href=/style.css?v=" + VERSION + ">" // tab title; design file refetched after each restart
            + "<header><img src=/logo.png alt='Kampala International University'>"      // KIU logo in the top bar
            + "<div><h1>KIU Bursary and Sponsorship Fairness Scorer</h1>"                // application title
            + "<p class=motto>Exploring the Heights</p></div>" + who + "</header>"       // KIU motto and user box
            + "<main>" + body + (u != null ? "" : "<div class=about>" + contacts() + "</div><section class=about><h2>About Kampala International University</h2>" // visitors also see the contacts and a strip about KIU
            + "<p><b>The leading private university in Uganda.</b> A not-for-profit institution committed to accessible, career-focused learning, from Kampala to Western Uganda.</p><div class=tiles>" // one-line description
            + tile("2001", "Established") + tile("2009", "Chartered")                    // key dates
            + tile("3 intakes", "January, April and August")                             // when students join
            + tile("Main campus", "Ggaba Road, Kansanga, Kampala") + tile("Western campus", "Ishaka, Bushenyi") // where it is
            + "</div></section>") + "</main>"                                            // then the page's own content closes
            + "<div id=loading role=status aria-live=polite><div class=spin></div><b>Loading page...</b></div>" // overlay shown while the next page loads
            + "<footer>Kampala International University &middot; Bursary and Sponsorship Committee</footer>" // page footer
            + "<script>var eye='<svg viewBox=\"0 0 24 24\" width=\"19\" height=\"19\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" // eye icon: drawing settings
            + "<path d=\"M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z\"/><circle cx=\"12\" cy=\"12\" r=\"3\"/></svg>';" // the eye outline and its pupil
            + "var eyeOff=eye.replace('</svg>','<line x1=\"3\" y1=\"3\" x2=\"21\" y2=\"21\"/></svg>');" // the same eye with a line through it
            + "document.querySelectorAll('input[type=password]').forEach(function(p){"   // for every password box on the page:
            + "var b=document.createElement('button');b.type='button';b.className='peek';b.innerHTML=eye;b.title='Show password';b.setAttribute('aria-label','Show password');" // make a small eye button
            + "b.onclick=function(){var hidden=p.type==='password';p.type=hidden?'text':'password';b.innerHTML=hidden?eyeOff:eye;" // click swaps dots and letters, and the icon
            + "b.title=hidden?'Hide password':'Show password';b.setAttribute('aria-label',b.title);};" // keep the tooltip and screen-reader name in step
            + "p.classList.add('pw');p.parentNode.classList.add('pwbox');p.parentNode.appendChild(b);});" // place it inside the box's right edge
            + "document.querySelectorAll('.step').forEach(function(s){var w=document.createElement('span');w.className='stepper';" // for every course box: a pair of arrows
            + "var opts=s.list?s.list.options:s.options;"                                // the courses it can step through (typed box or pick list)
            + "function fill(){if(s.list&&s.form.places)for(var k=0;k<opts.length;k++)if(opts[k].value===s.value)s.form.places.value=opts[k].dataset.p;}" // show a listed course's limit
            + "s.addEventListener('change',fill);s.addEventListener('input',fill);"      // whenever the name changes, by arrow, typing or picking
            + "[-1,1].forEach(function(d){var b=document.createElement('button');b.type='button';b.innerHTML=d<0?'&#9650;':'&#9660;';" // one up arrow, one down arrow
            + "b.title=d<0?'Previous course':'Next course';b.setAttribute('aria-label',b.title);" // tooltip and screen-reader name
            + "b.onclick=function(){var i=-1;for(var k=0;k<opts.length;k++)if(opts[k].value===s.value)i=k;" // where the box is now in the list
            + "i=Math.max(0,Math.min(opts.length-1,i+d));if(opts.length){s.value=opts[i].value;s.dispatchEvent(new Event('change'));}};" // step to the neighbouring course
            + "w.appendChild(b);});s.parentNode.classList.add('pwbox');s.parentNode.appendChild(w);});" // arrows sit inside the box's right edge
            + "var load=document.getElementById('loading');function busy(){load.classList.add('on');}" // shows the "Loading page..." overlay
            + "document.addEventListener('submit',function(e){if(!e.defaultPrevented)busy();});" // a form was sent (only fires once the form is valid)
            + "document.addEventListener('click',function(e){var a=e.target.closest('a');if(!a||a.target||e.ctrlKey||e.metaKey||e.shiftKey" // a link was clicked, unless it opens elsewhere
            + "||a.origin!==location.origin||/\\.csv$/.test(a.pathname)||a.getAttribute('href').charAt(0)==='#')return;busy();});" // or is a download or a jump on the same page
            + "window.addEventListener('pageshow',function(){load.classList.remove('on');});</script></html>"); // hide it again when coming back with the Back button
    }

    static String tile(String big, String caption) {                                     // one summary figure
        return "<div class=tile><b>" + big + "</b><span>" + caption + "</span></div>";   // big value over a small caption
    }

    static final String TEL = "type=tel pattern='\\+?[0-9 -]{9,20}' placeholder='e.g. 0700123456'"; // attributes shared by phone boxes

    static String field(String label, String attrs, String value) {                      // one labelled, required input box
        return optional(label, attrs + " required", value);                             // the same box, but it must be filled in
    }

    static String optional(String label, String attrs, String value) {                   // one labelled input box that may stay empty
        return "<label>" + label + "<input " + attrs + " value='" + esc(value) + "'></label>"; // saved value shown again
    }

    static String select(String label, String name, String[] options, int chosen) {      // one labelled drop-down list
        StringBuilder s = new StringBuilder("<label>" + label + "<select name=" + name + ">"); // list opens
        for (int i = 0; i < options.length; i++) s.append("<option value=").append(i)    // one choice per option
            .append(i == chosen ? " selected>" : ">").append(options[i]);                // keep the saved choice selected
        return s.append("</select></label>").toString();                                 // list closes
    }

    static String clean(String s) {                                                      // keeps only safe name characters
        return s == null ? "" : s.replaceAll("[^\\p{L}\\p{N} .'-]", "").trim();          // letters, digits, space, dot, apostrophe, hyphen
    }

    static String phone(String s) {                                                      // tidies a phone number
        return s == null ? "" : s.replaceAll("[\\s-]", "");                              // drop spaces and hyphens; Applicant.valid checks the rest
    }

    static String check(String name, String text, boolean on) {                          // one labelled tick box
        return "<label class=tick><input type=checkbox name=" + name + (on ? " checked" : "") + "> " + text + "</label>"; // ticked when true
    }

    static Map<String, String> form(HttpExchange x) throws IOException {                 // reads a posted form, at most 10 KB
        return params(new String(x.getRequestBody().readNBytes(10_000), StandardCharsets.UTF_8)); // body text to fields
    }

    static Map<String, String> params(String s) {                                        // splits "a=1&b=2" into a map
        Map<String, String> m = new HashMap<>();                                         // result map
        if (s != null) for (String p : s.split("&")) {                                   // each key=value pair
            String[] kv = p.split("=", 2);                                               // split key from value
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),                      // decoded key
                kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");  // decoded value or empty text
        }
        return m;                                                                        // hand back the fields
    }

    static String fmt(String pattern, double v) { return String.format(Locale.US, pattern, v); } // number formatting helper

    static String plain(double v) { return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString(); } // 900000.0 -> "900000"

    static String esc(String s) {                                                        // makes text safe to place inside HTML
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); // neutralise special characters
    }

    static void redirect(HttpExchange x, String to) throws IOException {                 // sends the browser to another page
        x.getResponseHeaders().add("Location", to);                                      // where to go
        x.sendResponseHeaders(303, -1);                                                  // redirect with no body
        x.close();                                                                       // finish the reply
    }

    static void send(HttpExchange x, int code, String type, String body) throws IOException { // writes a text reply
        send(x, code, type, body.getBytes(StandardCharsets.UTF_8));                      // text to bytes, then the method below
    }

    static void send(HttpExchange x, int code, String type, byte[] b) throws IOException { // writes one reply
        x.getResponseHeaders().add("Content-Type", type);                                // tell the browser what it is
        x.sendResponseHeaders(code, b.length);                                           // status code and length
        x.getResponseBody().write(b);                                                    // the content itself
        x.close();                                                                       // finish the reply
    }
}
