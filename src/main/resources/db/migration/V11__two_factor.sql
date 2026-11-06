-- V11: two-step sign-in with an authenticator app (Google Authenticator, Microsoft Authenticator, ...)
--   * the shared secret is stored encrypted (AES-GCM, key from MFA_ENCRYPTION_KEY), never in plain text
--   * recovery codes and sign-in challenges are stored only as SHA-256 hashes
alter table admin_users add column totp_secret varchar(255);
alter table admin_users add column totp_pending_secret varchar(255);
alter table admin_users add column totp_enabled bit not null default 0;
alter table admin_users add column totp_enabled_at datetime(6);
alter table admin_users add column totp_last_step bigint;

create table mfa_recovery_codes (
    id binary(16) not null,
    user_id binary(16) not null,
    code_hash varchar(64) not null,
    used_at datetime(6),
    created_at datetime(6) not null,
    primary key (id),
    constraint fk_mfa_recovery_codes_user foreign key (user_id) references admin_users (id) on delete cascade
) engine=InnoDB;
create index idx_mfa_recovery_codes_user on mfa_recovery_codes (user_id);

create table mfa_challenges (
    id binary(16) not null,
    user_id binary(16) not null,
    token_hash varchar(64) not null,
    expires_at datetime(6) not null,
    failed_attempts integer not null default 0,
    created_at datetime(6) not null,
    primary key (id),
    constraint fk_mfa_challenges_user foreign key (user_id) references admin_users (id) on delete cascade
) engine=InnoDB;
create unique index ux_mfa_challenges_token on mfa_challenges (token_hash);
create index idx_mfa_challenges_user on mfa_challenges (user_id);
