-- V7: one-time links for invitations and password resets (Phase 2, step 3)
--   The link itself is never stored, only the SHA-256 hash of its secret part.
create table account_tokens (
    id binary(16) not null,
    user_id binary(16) not null,
    purpose enum ('INVITE','PASSWORD_RESET') not null,
    token_hash varchar(64) not null,
    created_at datetime(6) not null,
    expires_at datetime(6) not null,
    used_at datetime(6),
    primary key (id),
    constraint fk_account_tokens_user foreign key (user_id) references admin_users (id) on delete cascade
) engine=InnoDB;

create unique index ux_account_tokens_hash on account_tokens (token_hash);
create index idx_account_tokens_user_id on account_tokens (user_id);
