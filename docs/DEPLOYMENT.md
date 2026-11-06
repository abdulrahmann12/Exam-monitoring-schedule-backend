# Uni-Guard Schedules — setup and go-live guide

For whoever deploys and runs the system. Today: backend on a Hugging Face Space (Docker), frontend on Vercel,
MySQL database, Brevo for email.

## How the pieces connect (cookie setup "option A")

```
browser ──► https://<app>.vercel.app            (frontend, Vercel)
              └── /api/*  ──forwarded──►  https://<space>.hf.space/api/*   (backend, Hugging Face)
```

`frontend/vercel.json` forwards every `/api/...` request to the backend. For the browser, the app and the API
are one site, so the HttpOnly sign-in cookies work in every browser (Safari blocks cookies between different
sites). The frontend production build calls `/api` on its own address (`VITE_API_BASE_URL` empty).

Consequences, already handled in the settings below:
- Many users reach the backend through a few Vercel addresses → `LOGIN_RATE_LIMIT_PER_MINUTE=60`
  (each account is still locked after 5 wrong passwords).
- Email links and Microsoft/Google redirects use `APP_FRONTEND_URL` (the Vercel address).

## Backend settings (Hugging Face Space → Settings → Variables and secrets)

All settings are listed in `backend/.env.example` (with explanations). Production values are prepared in
`backend/.env` on the developer's computer (ignored by git — never commit it). Required:

| Setting | Notes |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL. The server refuses to start without them |
| `JWT_SECRET` | ≥ 32 characters (`openssl rand -base64 48`) |
| `MFA_ENCRYPTION_KEY` | 32 random bytes, Base64 (`openssl rand -base64 32`). **Never change it later** |
| `APP_FRONTEND_URL`, `CORS_ALLOWED_ORIGINS` | The Vercel address, e.g. `https://uni-guard.vercel.app` |
| `AUTH_COOKIE_SAME_SITE=Lax`, `AUTH_COOKIE_SECURE=true` | Option A |
| `LOGIN_RATE_LIMIT_PER_MINUTE=60` | Option A |
| `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD` | Platform owner, created at first start. An email no university account uses |
| `BREVO_API_KEY`, `BREVO_SENDER_EMAIL` | Without them no email is sent (links can be shared by hand) |
| `DEMO_MODE_ENABLED=true` | Only on the public demo Space |

Optional: `GOOGLE_CLIENT_ID/SECRET`, `MICROSOFT_CLIENT_ID/SECRET`, `MICROSOFT_TENANT` (see below).

## Frontend settings (Vercel)

- Nothing to set: `.env.production` has `VITE_API_BASE_URL=""`. If the Vercel project defines
  `VITE_API_BASE_URL` in its settings, **remove it** (otherwise the browser calls the backend directly and
  sign-in cookies break in Safari).
- If the backend address changes, update the destination in `frontend/vercel.json`.

## Sign-in with Google / Microsoft (optional)

**Google** — Google Cloud Console → APIs & Services → Credentials → Create OAuth client ID → *Web application*.
Authorised redirect URI: `https://<app>.vercel.app/api/auth/sso/google/callback`. Copy the client id and
secret into `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`.

**Microsoft** — Microsoft Entra admin center → App registrations → New registration. Supported account types:
*Accounts in any organizational directory*. Redirect URI (Web): `https://<app>.vercel.app/api/auth/sso/microsoft/callback`.
Certificates & secrets → New client secret. Copy the Application (client) ID and the secret value into
`MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET`; keep `MICROSOFT_TENANT=organizations`.

Each university's administrator then enters their own Microsoft tenant id in **Settings → Sign-in with Microsoft
for your university** so their staff can sign in straight away.

## Go-live checklist

1. **Change the leaked secrets** (they are in the old git history):
   database password (databaseasp.net panel), Brevo API key (create new, delete old),
   Cloudinary API key (delete — not used any more). `JWT_SECRET` and `MFA_ENCRYPTION_KEY` are new in `backend/.env`.
2. **Back up the production database.** At the first start Flyway records the existing schema as V1 and applies
   V2–V13 (new columns and tables; existing data becomes the first university).
3. **Set the backend settings** in the Space (table above).
4. **Open a pull request** `phase-0-1-hardening` → `main` in both repositories. GitHub then runs all tests,
   including the database migrations on a real MySQL. Merge only when everything is green.
5. **Merge to `main`.** The backend deploys to Hugging Face automatically after its tests pass; Vercel deploys the
   frontend.
6. **Clean the git history** (after step 1): `python go-live/purge_leaked_secrets.py` (dry run), then with `--push`.
   Delete and re-clone local copies afterwards.
7. **Check the live site**: sign in as the existing administrator → change the password (the old default is public)
   → set up two-step sign-in. Sign in as the owner at `/owner`. Everyone signs in once again (old tokens are rejected).

## Running locally (no MySQL needed)

See the backend README, "Locally without MySQL": the backend runs with an H2 database file
(`--spring.profiles.active=local`), the frontend with `npm run dev` (http://localhost:5173, forwards `/api`
to `localhost:8080`).

## Recovery

- Lost administrator password: `BOOTSTRAP_ADMIN_RESET_PASSWORD=true` (with `BOOTSTRAP_ADMIN_EMAIL` /
  `BOOTSTRAP_ADMIN_PASSWORD`) for one restart. Also removes that account's two-step sign-in.
- Lost owner password: `BOOTSTRAP_OWNER_RESET_PASSWORD=true` the same way.
- Database: restore the backup taken before the deploy. Flyway migrations are forward-only.
