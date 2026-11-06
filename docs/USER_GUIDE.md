# Uni-Guard Schedules — user guide

For the exam office: administrators, schedulers and viewers of a university.

## Roles

| | Administrator | Scheduler | Viewer |
|---|---|---|---|
| See schedules, people, rooms, reports; export PDFs | ✓ | ✓ | ✓ |
| Edit people, rooms, time slots, schedules; import timetables; send schedules by email | ✓ | ✓ | |
| See the email delivery log (Emails page) | ✓ | ✓ | |
| Change settings (branding, scheduling rules) | ✓ | | |
| Manage users, see the activity log | ✓ | | |

## Signing in

- Sign in with your email and password, or with **Sign in with Microsoft / Google** when your university has it.
- **Forgot password?** on the sign-in page sends a link (valid 30 minutes, works once).
- A new user receives an invitation email with a link to choose a password (valid 72 hours).
- After 5 wrong passwords the account is locked for 2 days; an administrator can unlock it (Users page).

### Two-step sign-in (recommended for administrators)

1. **Settings → Two-step sign-in → Set up**, confirm your password.
2. Scan the QR code with Google Authenticator (or Microsoft Authenticator, Authy, 1Password) and enter the 6-digit code.
3. **Save the 10 recovery codes** somewhere safe (not on the same phone). Each works once if you lose the phone.

From then on you enter the 6-digit code after your password. Lost phone and codes: ask an administrator to
reset it (Users page, phone button).

### Connected accounts

**Settings → Connected accounts → Connect** links your Microsoft or Google account to your Uni-Guard account,
so you can use the "Sign in with ..." buttons. You can disconnect it at any time; your password keeps working.

## Setting up an exam period

1. **Exam period**: choose or create it at the top of every page.
2. **Rooms**: name, capacity, type, hall gender (mixed / male / female), minimum invigilators.
3. **People**: chiefs and invigilators with department, email (for schedule emails), available weekdays,
   gender, subjects they teach (never assigned to those exams), and leave dates.
4. **Time slots**: the sittings of the exam period (e.g. Morning 09:00–12:00).
5. **Settings** (administrators): maximum duties per day and per exam period, rest time between sessions,
   students per invigilator.

## Importing the exam timetable

**Scheduler → Bulk upload**:

- Download the template, or upload an export from your student-information system (Banner, PeopleSoft, Odoo)
  as Excel (.xlsx) or CSV. Columns are recognised by their names, for example *Exam Date*, *Exam Start Time*,
  *Exam End Time*, *Room* / *Classroom* / *Facility ID*, *Course Title*, *Subject* + *Course Number*, *Enrollment*.
- Rooms must exist in Uni-Guard (same names); missing time slots are created.
- Dates like 03/04/2027 are only accepted when the file or your choice ("Dates like 03/04/2027 mean...") makes the
  order clear. 2027-04-03 or 03-APR-2027 are always clear.
- If any row is wrong, nothing is imported and every problem is listed with its row number.
- After the import, staff are placed automatically; the result says how many places were filled.

## Building the schedule

- **Scheduler**: pick a date and time slot, select rooms, **Generate**. Rules applied: no double booking
  (also across exam periods), room overlaps, leave, gender halls, own subjects, daily / period limits, rest time,
  fair workload (minutes worked in this exam period).
- Locked rooms are never changed by Generate.
- **Assignments / Timeline**: review and print; export PDFs.

## Emailing the schedules

**Send emails** (calendar menu at the top): each person receives a PDF with only their own duties.

- Emails are sent in the background and retried automatically if the email service has a problem.
- People who already received exactly this schedule are not emailed again; after changes only the people whose
  duties changed get a new email. Tick "send to everyone again" to resend to all.
- **Emails page**: what is waiting, sent or failed (with the reason), and **Send again** for failed ones.

## Reports

**Reports** page, for the selected exam period:

- **Hours per person**: sessions, hours, rooms, days. A chief covering two rooms at the same time works one session.
  **Export hours (Excel)** gives a file for finance.
- **Fairness & coverage**: a 0–100 fairness score per role (100 = equal hours), workload per department, and
  exam rooms missing a chief or invigilators.

## Administrators

- **Users**: add (with invitation email or temporary password), change role, disable, unlock, reset password,
  reset two-step sign-in, delete. You cannot demote, disable or delete yourself; there is always one active administrator.
- **Activity**: who did what and when (sign-ins, changes, security alerts); filter and export to Excel.
- **Settings → Sign-in with Microsoft for your university**: enter your organisation's Microsoft tenant id so staff
  can sign in with their university Microsoft account straight away.
