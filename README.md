---
title: schedule-backend
emoji: 📅
colorFrom: blue
colorTo: green
sdk: docker
app_port: 7860
pinned: false
---


> Check out the Hugging Face Spaces configuration reference at  
> https://huggingface.co/docs/hub/spaces-config-reference

# 📅 Schedule Backend

## Guides

- [User guide](docs/USER_GUIDE.md) — for exam-office administrators, schedulers and viewers
- [Setup and go-live guide](docs/DEPLOYMENT.md) — settings, Vercel + Hugging Face (cookie option A), Google/Microsoft sign-in, go-live checklist


A **Spring Boot 3.5** REST API for managing exam invigilator scheduling.  
Built with Java 21 (LTS), Spring Security (JWT), JPA/Hibernate, and MySQL.

---

## 🚀 Features

- JWT-based authentication & role-based authorization
- Exam schedule & assignment management
- Room and people (invigilator) management
- Bulk Excel upload support (Apache POI)
- Caffeine in-memory caching
- Flyway schema migrations

---

## 🐳 Running on Hugging Face Spaces (Docker)

This Space uses the **Docker** SDK. The application starts on port **7860**.

### Configuration

Set these as **secrets** in your hosting provider (Hugging Face: *Settings → Variables and secrets*).
The full annotated list is in [`.env.example`](.env.example).

| Variable                         | Required | Description |
|----------------------------------|----------|-------------|
| `DB_URL`                         | ✅ | JDBC URL of the MySQL database |
| `DB_USERNAME` / `DB_PASSWORD`    | ✅ | Database credentials |
| `JWT_SECRET`                     | ✅ | ≥ 32 random characters (`openssl rand -base64 48`) |
| `CORS_ALLOWED_ORIGINS`           | ✅ in prod | Exact frontend origin(s), comma-separated — no wildcards |
| `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | first start | Creates the first admin when none exists. Never overwrites an existing password |
| `BOOTSTRAP_ADMIN_RESET_PASSWORD` | — | `true` for one restart to reset that admin's password (recovery) |
| `BOOTSTRAP_OWNER_EMAIL` / `BOOTSTRAP_OWNER_PASSWORD` | — | Optional. Creates the platform owner (owner panel at `/owner`: create universities, switch them off/on, usage). Belongs to no university and sees no university data. Must be an email no university account uses |
| `BOOTSTRAP_OWNER_RESET_PASSWORD` | — | `true` for one restart to reset the owner's password (recovery) |
| `MFA_ENCRYPTION_KEY` | for two-step sign-in | 32 random bytes, Base64 (`openssl rand -base64 32`). Encrypts the authenticator secrets. Keep it stable and separate from `JWT_SECRET`; without it users cannot switch two-step sign-in on. Changing it breaks existing set-ups (recovery codes still work). The `BOOTSTRAP_*_RESET_PASSWORD` recoveries also remove two-step sign-in |
| `MFA_ISSUER` | — | Name shown in the authenticator app (default `Uni-Guard Schedules`) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | for Google sign-in | From Google Cloud Console > APIs & Services > Credentials (OAuth client, type Web application). Redirect URI: `<APP_FRONTEND_URL>/api/auth/sso/google/callback` |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | for Microsoft sign-in | From Microsoft Entra admin center > App registrations (Web platform). Redirect URI: `<APP_FRONTEND_URL>/api/auth/sso/microsoft/callback` |
| `MICROSOFT_TENANT` | — | `organizations` (any work/school account, default), `common` (also personal accounts) or one organisation's tenant id |
| `APP_SSO_CALLBACK_BASE_URL` | — | Only if the redirect URI must use another address than `APP_FRONTEND_URL` |
| `MAIL_WORKER_ENABLED` | — | `true` (default): this server sends queued emails in the background. With several servers it can stay on everywhere (each email is taken by one server only) |
| `MAIL_LOG_RETENTION_DAYS` | — | Days to keep the email delivery log (default 90). Sent emails keep no content |
| `JWT_EXPIRATION_MINUTES`         | — | Access token lifetime, default 15 (renewed silently from the refresh cookie) |
| `AUTH_COOKIE_SAME_SITE`          | — | `Lax` (default) when frontend and API share a site; `None` for different sites (HTTPS only) |
| `AUTH_COOKIE_SECURE`             | — | `true` (default); `false` only for plain-http local development |
| `AUTH_COOKIE_DOMAIN`             | — | Optional cookie domain, e.g. `.university.edu` |
| `AUTH_SESSION_DAYS`              | — | Days a device stays signed in, default 7 |
| `AUTH_MAX_SESSIONS_PER_USER`     | — | Active devices per account, default 20 (oldest is signed out) |
| `LOGIN_MAX_FAILED_ATTEMPTS` / `LOGIN_LOCK_DURATION_MINUTES` | — | Account lockout: default 5 wrong passwords → locked 2880 min (2 days). Unlock early with `BOOTSTRAP_ADMIN_RESET_PASSWORD` |
| `DEMO_MODE_ENABLED`              | — | `true` only on a public demo deployment; demo sessions are read-only |
| `BREVO_API_KEY` / `BREVO_SENDER_EMAIL` | — | Enables emails: staff notifications, user invitations, "forgot password" |
| `APP_FRONTEND_URL`               | ✅ prod | Frontend address used in invitation / reset links (never taken from request headers) |
| `APP_INVITE_HOURS` / `APP_RESET_LINK_MINUTES` | — | Link lifetimes, default 72 h / 30 min |
| `APP_LOG_ACCOUNT_LINKS`          | — | Local development only: print links in the log when email is off |
| `APP_AUDIT_RETENTION_DAYS`       | — | Days the activity log is kept, default 365 (0 = forever) |

The server refuses to start when a required value is missing and prints which one.

> ⚠️ **Never commit real credentials.** `.env` files are git-ignored.

---

## 🛠️ Local Development

**Prerequisites:** Java 21 (LTS), Maven 3.9+, MySQL (or H2 in-memory DB for quick tests)

### Run tests

```powershell
mvn test
```

Tests run on an in-memory H2 database (`test` profile) — no MySQL needed. CI additionally
verifies the Flyway scripts on a real MySQL 8 (`MySqlFreshInstallIT`, `MySqlUpgradeFromHibernateIT`).

### Database migrations (Flyway)

The schema is owned by Flyway scripts in `src/main/resources/db/migration`; Hibernate only
validates it (`ddl-auto=validate`).

* Existing databases created by the old `ddl-auto=update` are baselined at V1 automatically on first start.
* To change the schema: edit the entities, run
  `mvn test -Dtest=SchemaDdlGenerator -DgenerateDdl=true`, compare `target/schema-mysql.sql`
  with the previous version, and write the `ALTER` statements into a new `V<n>__description.sql`.
* Never edit a migration that has already been deployed.

### Run the application

**Against MySQL** — set the variables from `.env.example`, then:

```powershell
mvn spring-boot:run
```

**Locally without MySQL** (H2 file database in `./data`, demo enabled,
admin `admin@local.test` / `LocalAdmin123` — see `src/test/resources/application-local.properties`):

```bash
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/cp.txt
mvn -q test-compile
java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" com.uniguard.schedule.ScheduleApplication --spring.profiles.active=local
```

(On macOS/Linux use `:` instead of `;` in the classpath.) The frontend dev server proxies `/api` to `http://localhost:8080`.

### Run on a different port

```powershell
$env:SERVER_PORT=8082
mvn spring-boot:run
```

### Build & run the Docker image locally

```bash
docker build -t schedule-backend .
docker run -p 7860:7860 \
  -e DB_USERNAME=your_user \
  -e DB_PASSWORD=your_pass \
  -e JWT_SECRET=your_secret \
  schedule-backend
```

---

## 📖 API Documentation

See [API_DOCUMENTATION.md](API_DOCUMENTATION.md). (No Swagger UI is bundled; the API is not
publicly browsable.)

---

## 🏗️ Tech Stack

| Layer       | Technology                         |
|-------------|-------------------------------------|
| Language    | Java 21 (LTS)                             |
| Framework   | Spring Boot 3.5                     |
| Security    | Spring Security + JWT (jjwt 0.12)  |
| Persistence | Spring Data JPA / Hibernate         |
| Database    | MySQL (PostgreSQL & H2 supported)   |
| Mapping     | MapStruct 1.6                       |
| Caching     | Caffeine                            |
| File Upload | Apache POI 5.4 (Excel)              |
| Migrations  | Flyway                              |
| Build       | Maven 3.9                           |
| Container   | Docker (multi-stage build)          |
