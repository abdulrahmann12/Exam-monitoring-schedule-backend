-- V3: per-admin token version — enables server-side logout and revoking all sessions
--     after a password change. Existing tokens carry no version claim and are rejected,
--     so every user signs in once after this deploy.
alter table admin_users add column token_version integer not null default 0;
