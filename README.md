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
Built with Java 23, Spring Security (JWT), JPA/Hibernate, and MySQL.

---

## 🚀 Features

- JWT-based authentication & role-based authorization
- Exam schedule & assignment management
- Room and people (invigilator) management
- Bulk Excel upload support (Apache POI)
- Swagger UI (`/swagger-ui.html`)
- Caffeine in-memory caching
- Cloudinary image upload integration
- Health & metrics via Spring Actuator

---

## 🐳 Running on Hugging Face Spaces (Docker)

This Space uses the **Docker** SDK. The application starts on port **7860**.

### Automatic GitHub → Hugging Face sync

This repository now includes a GitHub Actions workflow at
`.github/workflows/sync-to-huggingface.yml` that pushes the current `main` branch to your
Hugging Face Space on every push.

Configure these in your GitHub repository before relying on auto-sync:

| Location | Name | Value |
|----------|------|-------|
| GitHub Actions secret | `HF_TOKEN` | A Hugging Face write token that can update the target Space |
| GitHub Actions variable | `HF_SPACE_REPO_ID` | The Space repository id, for example `your-user/your-space-name` |

After adding them:

1. Push changes to `main`.
2. Open the **Actions** tab in GitHub.
3. Confirm the **Sync to Hugging Face** workflow completed successfully.

For a permanent setup, keep GitHub as the deployment source of truth:

- Do not force-push or rewrite `main` yourself.
- Turn on branch protection for `main` and block force pushes.
- Keep `.github/workflows/sync-to-huggingface.yml` on `main`, because pushes to `main` are what trigger the sync.
- The workflow force-pushes only to the Hugging Face Space so the Space exactly matches the current GitHub `main` branch.

### Required Secrets

Set the following as **Repository Secrets** in your Space settings  
(*Settings → Variables and secrets → New secret*):

| Secret Name               | Description                             | Default (insecure — change it!) |
|---------------------------|-----------------------------------------|----------------------------------|
| `DB_USERNAME`             | MySQL database username                 | `db50370`                        |
| `DB_PASSWORD`             | MySQL database password                 | —                                |
| `JWT_SECRET`              | 256-bit hex secret for signing JWTs     | (auto-generated fallback)        |
| `BOOTSTRAP_ADMIN_EMAIL`   | Email of the first admin account        | `admin@uniguard.local`           |
| `BOOTSTRAP_ADMIN_PASSWORD`| Password of the first admin account     | `ChangeMe122`                    |
| `MAIL_USERNAME`           | Gmail address used to send emails       | —                                |
| `MAIL_PASSWORD`           | Gmail App Password (not login password) | —                                |
| `CLOUDINARY_NAME`         | Cloudinary cloud name                   | —                                |
| `CLOUDINARY_API_KEY`      | Cloudinary API key                      | —                                |
| `CLOUDINARY_API_SECRET`   | Cloudinary API secret                   | —                                |

> ⚠️ **Never commit real credentials.** All sensitive values are read from environment variables at runtime.

---

## 🛠️ Local Development

**Prerequisites:** Java 23, Maven 3.9+, MySQL (or H2 in-memory DB for quick tests)

### Run tests

```powershell
mvn test
```

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

Once the app is running, open:

- **Swagger UI:** `http://localhost:7860/swagger-ui.html`
- **OpenAPI JSON:** `http://localhost:7860/v3/api-docs`

---

## 🏗️ Tech Stack

| Layer       | Technology                         |
|-------------|-------------------------------------|
| Language    | Java 23                             |
| Framework   | Spring Boot 3.5                     |
| Security    | Spring Security + JWT (jjwt 0.12)  |
| Persistence | Spring Data JPA / Hibernate         |
| Database    | MySQL (PostgreSQL & H2 supported)   |
| Mapping     | MapStruct 1.6                       |
| Caching     | Caffeine                            |
| File Upload | Apache POI 5.4 (Excel)              |
| Storage     | Cloudinary                          |
| Build       | Maven 3.9                           |
| Container   | Docker (multi-stage build)          |
