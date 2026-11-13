import java.time.Instant;                                                                // a moment in time
import java.time.ZoneId;                                                                 // this computer's time zone
import java.time.format.DateTimeFormatter;                                               // date and time wording
import java.util.Locale;                                                                 // fixed number format for every computer

// The scoring model: Need (60) + Verification (40) = 100
public class Scorer {                                                                    // everything about scoring an application

    public record Result(Applicant a, double need, double verification,                  // the applicant and the two part scores
                         double total, String reason) {}                                 // the total and the stated reason

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.US).withZone(ZoneId.systemDefault()); // e.g. 11 Oct 2026 09:30

    static final String[] JOBS = {"Unemployed or unable to work", "Peasant farmer or casual worker", // parent occupation choices,
                                  "Small business or self-employed", "Salaried employee"}; // from greatest need to least
    static final double[] JOB_POINTS = {36, 26, 14, 0};                                  // need points for each occupation

    static String f(double v) { return String.format(Locale.US, "%.1f", v); }            // shows a number with one decimal place
    static String money(double v) { return String.format(Locale.US, "%,.0f", v); }       // shows money with thousands separators
    static String when(long millis) { return WHEN.format(Instant.ofEpochMilli(millis)); } // shows a submission time

    public static Result score(Applicant a) {                                            // scores one applicant and explains why
        // ---- NEED: up to 60 points ----
        double jobPts = JOB_POINTS[a.parentJob()];                                       // parent or guardian's occupation is worth up to 36 points
        double guardPts = a.guardian() * 12;                                             // 0 both parents, 12 single parent, 24 orphan
        double need = jobPts + guardPts;                                                 // need subtotal

        // ---- VERIFICATION: up to 40 points, 10 for each original document physically seen ----
        boolean[] seen = {a.admission(), a.slip(), a.academic(), a.recommendation()};    // the four required documents
        String[] docs = {"admission letter", "tuition payment slip", "academic documents", "LC3/LC5/Mayor recommendation"}; // their names
        double verification = 0;                                                         // verification subtotal
        StringBuilder checks = new StringBuilder();                                      // wording for the verification line
        for (int i = 0; i < 4; i++) {                                                    // each required document
            boolean stale = i == 3 && !a.recCurrent();                                   // a recommendation older than six months does not count
            verification += seen[i] && !stale ? 10 : 0;                                  // 10 points when the original was seen (and is current)
            checks.append(i == 0 ? "" : ", ").append(docs[i]).append(stale ? " outdated, dated " + (a.recDate().isEmpty() ? "unknown" : a.recDate()) + " (0/10)" // say which
                : seen[i] ? " seen (10/10)" : " not yet seen (0/10)");                   // seen or still awaited
        }

        // ---- STATED REASON: every number above is explained in words ----
        String[] g = {"both parents alive", "single parent or guardian", "orphan"};      // wording for each family status
        int uni = (int) Math.round(a.rate() * 100);                                      // university's percentage: 40 or 50
        String reason = "Need " + f(need) + "/60: parent or guardian is "                // need line starts with the declared occupation
            + JOBS[a.parentJob()].toLowerCase(Locale.US) + " (" + f(jobPts) + "/36), "   // and the points it earned
            + g[a.guardian()] + " (" + f(guardPts) + "/24)"                              // then the family status
            + " | Verification " + f(verification) + "/40: " + checks                    // verification line
            + " | Bursary: " + a.years() + "-year course so the university removes " + uni + "% of tuition = " + money(a.award()) // the 40%/50% rule
            + "; student pays " + (100 - uni) + "% = " + money(a.share()) + ", slip shows " + money(a.paid()) // the student's part
            + ", " + Applicant.PAYMENTS[a.payStatus()].toLowerCase(Locale.US) + (a.payStatus() == 1 ? " " + money(a.payAmount()) : "") // what Finance found
            + (a.qualifies() ? " (requirement met)" : " (short by " + money(a.share() - a.received()) + ")") // paid enough or not
            + " | Names " + (a.namesMatch() ? "spelled the same on every document" : "not yet checked against every document") // misspelling check
            + (a.active() ? "" : " | Standing: " + Applicant.STANDINGS[a.standing()] + ", not considered for an award") // withdrawn bursaries step aside
            + " | Applied " + when(a.submitted()) + ": earlier applications win ties";   // first come first served
        return new Result(a, need, verification, need + verification, reason);           // bundles everything together
    }
}
