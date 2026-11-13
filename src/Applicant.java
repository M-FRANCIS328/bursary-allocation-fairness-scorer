import java.nio.charset.StandardCharsets;                                                // UTF-8 for the feedback text
import java.time.LocalDate;                                                              // dates such as the recommendation letter's
import java.util.Base64;                                                                 // keeps feedback safe inside one CSV column

// One bursary application: contacts, parent details, tuition details, document checks and its bursary record
public record Applicant(int id, String name, String email, String phone,                 // reference number, student name, login email and phone
                        String parentName, String parentPhone, int parentJob,            // parent or guardian: name, phone, occupation code 0-3
                        String kinName, String kinPhone,                                 // next of kin: name and phone
                        int guardian,                                                    // family status: 0 both parents, 1 single parent/guardian, 2 orphan
                        double fees, int years, double paid,                             // tuition fee, course length in years, amount on the payment slip
                        boolean admission, boolean slip, boolean academic, boolean recommendation, // originals an administrator has physically seen
                        long submitted,                                                  // time first submitted, for first come first served
                        String year, int intake, int campus, String course,              // academic year "2026/2027", intake code 0-2, campus code 0-1, course name
                        String feedback,                                                 // the office's message to the student
                        String regNo, String receipt, String recDate,                    // registration number (the key), receipt number on the slip, date on the recommendation
                        boolean namesMatch,                                              // an administrator saw the name spelled the same on every document
                        long letter,                                                     // time the award letter was issued, 0 when not issued
                        int standing, String standingNote,                               // bursary standing code 0-4 and the latest note about it
                        String newCourse, String history,                                // course the student asked to move to, and the dated record of events
                        int payStatus, double payAmount, String payNote) {               // Finance office check of the slip: status 0-2, amount received, note

    static final String PHONE = "\\+?[0-9]{9,15}";                                       // shape of an acceptable phone number
    static final String CODE = "[A-Z0-9/-]{3,40}";                                       // shape of a registration or receipt number
    static final String[] INTAKES = {"January", "April", "August"};                      // intake choices
    static final String[] CAMPUSES = {"Main campus", "Western campus"};                  // campus choices
    static final String[] STANDINGS = {"Active", "Bursary withdrawn", "Reinstatement requested", "Reinstated", "Reinstatement declined"}; // standing codes 0-4
    static final String[] PAYMENTS = {"Awaiting Finance check", "Confirmed by Finance", "Rejected by Finance"}; // payment status codes 0-2
    static final int REC_DAYS = 180;                                                     // a recommendation older than this is outdated

    boolean valid() {                                                                    // rejects impossible or missing values
        return !name.isEmpty() && !parentName.isEmpty()                                  // student and parent names present (next of kin is optional)
            && year.matches("20[0-9]{2}/20[0-9]{2}") && intake >= 0 && intake <= 2 && campus >= 0 && campus <= 1 && !course.isEmpty() // year, intake, campus and course chosen
            && phone.matches(PHONE) && parentPhone.matches(PHONE) && (kinPhone.isEmpty() || kinPhone.matches(PHONE)) // phone numbers well formed; next of kin may be blank
            && regNo.matches(CODE) && receipt.matches(CODE)                              // registration and receipt numbers well formed
            && date(recDate) != null && !date(recDate).isAfter(LocalDate.now())          // recommendation date real and not in the future
            && parentJob >= 0 && parentJob <= 3 && guardian >= 0 && guardian <= 2        // codes inside their ranges
            && fees > 0 && fees < 1e12 && paid >= 0 && paid < 1e12 && years >= 1 && years <= 8; // tuition, slip amount and course length sensible
    }

    static LocalDate date(String s) {                                                    // reads "2026-10-17", or null when it is not a date
        try { return LocalDate.parse(s); } catch (RuntimeException e) { return null; }   // empty or badly formed text gives null
    }

    double rate() { return years >= 4 ? 0.40 : 0.50; }                                   // part the university removes: 40% for 4+ years, 50% for 3 or less
    double award() { return fees * rate(); }                                             // bursary amount the university would remove
    double share() { return fees - award(); }                                            // part the student must pay: 60% or 50%
    double received() { return payStatus == 1 ? payAmount : payStatus == 2 ? 0 : paid; } // the amount that counts: Finance's figure once checked, nothing for a rejected slip
    boolean qualifies() { return received() >= share(); }                               // qualifies when the payment covers the student's part
    boolean active() { return standing == 0 || standing == 3; }                          // bursary not withdrawn (never withdrawn, or reinstated)
    boolean eligible() { return qualifies() && active(); }                               // can take a course place and be funded
    boolean recCurrent() {                                                               // is the recommendation letter recent enough?
        LocalDate d = date(recDate);                                                     // the date written on it
        return d != null && !d.isBefore(LocalDate.now().minusDays(REC_DAYS));            // within the last six months
    }
    boolean originals() { return admission && slip && academic && recommendation && namesMatch && recCurrent(); } // originals seen, names agree, recommendation current
    boolean verified() { return originals() && payStatus == 1; }                         // and Finance has confirmed the payment
    boolean confirmed() { return eligible() && verified(); }                             // qualifies and originals checked: ready for an award

    String log(String event) {                                                           // the record of events with one more dated line
        return (history.isEmpty() ? "" : history + "\n") + Scorer.when(System.currentTimeMillis()) + ": " + event; // newest at the bottom
    }

    Applicant withChecks(boolean a, boolean s, boolean c, boolean r, boolean n) {        // copy with new document and name checks
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, years, paid, a, s, c, r, submitted, year, intake, campus, course, feedback, // only the four document checks change
            regNo, receipt, recDate, n, letter, standing, standingNote, newCourse, history, payStatus, payAmount, payNote); // and the name check
    }

    Applicant withFeedback(String text) {                                                // copy with a new message from the office
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, years, paid, admission, slip, academic, recommendation, submitted, year, intake, campus, course, text, // only the feedback changes
            regNo, receipt, recDate, namesMatch, letter, standing, standingNote, newCourse, history, payStatus, payAmount, payNote); // record unchanged
    }

    Applicant withLetter(long when, String event) {                                      // copy with the award letter issued (or cancelled with 0)
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, years, paid, admission, slip, academic, recommendation, submitted, year, intake, campus, course, feedback, // application unchanged
            regNo, receipt, recDate, namesMatch, when, standing, standingNote, newCourse, log(event), payStatus, payAmount, payNote); // letter time and a line in the record
    }

    Applicant withStanding(int code, String note, String event) {                        // copy with a new bursary standing
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, years, paid, admission, slip, academic, recommendation, submitted, year, intake, campus, course, feedback, // application unchanged
            regNo, receipt, recDate, namesMatch, code == 3 ? letter : 0, code, note, newCourse, log(event), payStatus, payAmount, payNote); // a withdrawal cancels the letter
    }

    Applicant withCourseRequest(String wanted, String event) {                           // copy with a course change asked for ("" when settled)
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, years, paid, admission, slip, academic, recommendation, submitted, year, intake, campus, course, feedback, // application unchanged
            regNo, receipt, recDate, namesMatch, letter, standing, standingNote, wanted, log(event), payStatus, payAmount, payNote); // the request and a line in the record
    }

    Applicant withCourse(String c, int y, String event) {                                // copy moved to another course after approval
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, y, paid, admission, slip, academic, recommendation, submitted, year, intake, campus, c, feedback, // new course and its length
            regNo, receipt, recDate, namesMatch, 0, standing, standingNote, "", log(event), payStatus, payAmount, payNote); // request settled; the old letter no longer applies
    }

    Applicant withPayment(int status, double amount, String note, String event) {        // copy with the Finance office's check of the slip
        return new Applicant(id, name, email, phone, parentName, parentPhone, parentJob, kinName, kinPhone, guardian, // contacts unchanged
            fees, years, paid, admission, slip, academic, recommendation, submitted, year, intake, campus, course, feedback, // application unchanged
            regNo, receipt, recDate, namesMatch, status == 1 ? letter : 0, standing, standingNote, newCourse, log(event), // a rejected slip cancels the letter
            status, amount, note);                                                       // the check itself
    }

    String head() {                                                                      // the 22 application columns, shared by the data file and the audit
        return id + "," + name + "," + email + "," + phone + "," + parentName + "," + parentPhone + "," + parentJob + "," // student and parent
            + kinName + "," + kinPhone + "," + guardian + "," + fees + "," + years + "," + paid + ","   // kin, family status and tuition
            + admission + "," + slip + "," + academic + "," + recommendation + "," + submitted + "," // document checks and submission time
            + year + "," + intake + "," + campus + "," + course;                         // academic year, intake, campus and course
    }

    String toCsv() {                                                                     // one line for the applicants.csv file
        return head() + "," + b64(feedback)                                              // application, then feedback encoded so commas cannot break the line
            + "," + regNo + "," + receipt + "," + recDate + "," + namesMatch + "," + letter // keys, recommendation date, name check, award letter
            + "," + standing + "," + b64(standingNote) + "," + newCourse + "," + b64(history) // standing, course request and the record of events
            + "," + payStatus + "," + payAmount + "," + b64(payNote);                     // the Finance office's check of the slip
    }

    static String b64(String s) { return Base64.getUrlEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); } // free text to comma-free text
    static String un64(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }     // and back

    static Applicant fromCsv(String line) {                                              // rebuilds an application from a saved line
        String[] c = line.split(",", -1);                                                // names and emails never contain commas
        boolean old = c.length == 23;                                                    // saved before reinstatement and course changes existed
        boolean unpaid = c.length == 32;                                                 // saved before the Finance office checked slips
        if (!old && !unpaid && c.length != 35) return null;                              // none of the formats: skip it
        return new Applicant(Integer.parseInt(c[0]), c[1], c[2], c[3], c[4], c[5], Integer.parseInt(c[6]), c[7], c[8], // student, parent, kin
            Integer.parseInt(c[9]), Double.parseDouble(c[10]), Integer.parseInt(c[11]), Double.parseDouble(c[12]), // family status and tuition details
            Boolean.parseBoolean(c[13]), Boolean.parseBoolean(c[14]), Boolean.parseBoolean(c[15]), Boolean.parseBoolean(c[16]), // document checks
            Long.parseLong(c[17]), c[18], Integer.parseInt(c[19]), Integer.parseInt(c[20]), c[21], // submission time, academic year, intake, campus, course
            un64(c[22]),                                                                 // decoded feedback
            old ? "" : c[23], old ? "" : c[24], old ? "" : c[25],                        // registration, receipt and recommendation date (blank in old lines)
            !old && Boolean.parseBoolean(c[26]), old ? 0 : Long.parseLong(c[27]),         // name check and award letter time
            old ? 0 : Integer.parseInt(c[28]), old ? "" : un64(c[29]),                   // standing and its note
            old ? "" : c[30], old ? "" : un64(c[31]),                                    // course request and record of events
            old || unpaid ? 0 : Integer.parseInt(c[32]), old || unpaid ? 0 : Double.parseDouble(c[33]), // payment status and amount received
            old || unpaid ? "" : un64(c[34]));                                           // and the Finance note
    }
}
