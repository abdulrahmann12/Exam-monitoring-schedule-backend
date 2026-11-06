-- V6: server-side sessions for cookie login (Phase 2, step 2)
--   One row per signed-in device. The refresh token itself is never stored, only its SHA-256 hash.
--   previous_token_hash keeps the last rotated token so a reused (stolen) one can be detected.
create table user_sessions (
    id binary(16) not null,
    user_id binary(16) not null,
    refresh_token_hash varchar(64) not null,
    previous_token_hash varchar(64),
    token_version integer not null,
    rotated_at datetime(6),
    created_at datetime(6) not null,
    last_used_at datetime(6) not null,
    expires_at datetime(6) not null,
    revoked_at datetime(6),
    user_agent varchar(255),
    ip_address varchar(64),
    primary key (id),
    constraint fk_user_sessions_user foreign key (user_id) references admin_users (id) on delete cascade
) engine=InnoDB;

create unique index ux_user_sessions_refresh_hash on user_sessions (refresh_token_hash);
create index idx_user_sessions_previous_hash on user_sessions (previous_token_hash);
create index idx_user_sessions_user_id on user_sessions (user_id);
create index idx_user_sessions_expires_at on user_sessions (expires_at);
