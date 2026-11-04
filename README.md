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
| `JWT_EXPIRATION_MINUTES`         | — | Session length, default 720 (12 h) |
| `DEMO_MODE_ENABLED`              | — | `true` only on a public demo deployment; demo sessions are read-only |
| `BREVO_API_KEY` / `BREVO_SENDER_EMAIL` | — | Enables "notify staff" emails |

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

```powershell
mvn spring-boot:run
```

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
