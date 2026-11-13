# Bursary Allocation Fairness Scorer

A web application for Kampala International University that manages the full bursary process, from a student's application to the award letter. Every decision is scored by a transparent rule and explained in plain words.

## Features

- **Roles:** applicant, administrator (Bursary office), Finance office, auditor, and system administrator.
- **Transparent scoring:** Need (60) + Verification (40) = 100.
  - Need: parent or guardian occupation (up to 36) plus family status (single parent 12, orphan 24).
  - Verification: 10 points for each original document physically seen (admission letter, tuition payment slip, academic documents, LC3/LC5/Mayor recommendation). A recommendation older than 6 months scores 0.
- **Bursary rule:** the university removes 40% of tuition for courses of 4 or more years, and 50% for 3 years or less. A student qualifies once their own share is paid and confirmed by Finance.
- **Fair allocation:** applicants are ranked by score, and earlier submissions win ties. The sponsor budget is allocated in rank order, a place limit applies to each course, and waiting lists are emailed automatically.
- **Integrity checks:** duplicate document detection (fingerprints), name-spelling check across documents, registration and receipt number validation, and Finance confirmation of payment slips.
- **Lifecycle:** feedback messages, course-change requests, bursary withdrawal and reinstatement, and printable award letters.
- **Accountability:** a written reason for every score, a staff action log, and CSV audit and payment reports.

## Requirements

- Java 21 or newer (uses virtual threads and records).
- No external libraries or database: data is stored in CSV files.

## Run

Windows:

```bat
run.bat
```

Any OS:

```sh
javac -d out src/*.java
java -cp out App [adminEmail adminPassword]
```

Then open <http://localhost:8080>.

On first run an administrator account is created: `admin@kiu.ac.ug` / `Admin@12345` by default, or the email and password given on the command line. **Change this password immediately.**

## Email (optional)

Copy `mail.properties.example` to `mail.properties` and fill in your SMTP details. When it is not configured, password reset codes and notifications are written to `outbox.txt`.

## Project structure

| Path | Purpose |
|---|---|
| `src/App.java` | Web server, pages and routes |
| `src/Applicant.java` | Application record, validation, bursary rules |
| `src/Scorer.java` | Scoring model and stated reasons |
| `src/Store.java` | CSV storage, courses, waiting lists, activity log |
| `src/Auth.java` | Accounts, sessions, password hashing and resets |
| `src/Mail.java` | SMTP email (or outbox file) |
| `web/` | Stylesheet and images |
| `courses.csv` | Courses with their place limits and lengths |

Runtime files (`users.csv`, `applicants.csv`, `activity.log`, `uploads/`, `mail.properties`) are created locally and excluded from version control.
