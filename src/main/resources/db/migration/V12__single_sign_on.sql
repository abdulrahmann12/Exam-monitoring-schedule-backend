-- V12: sign-in with Microsoft or Google (OpenID Connect)
--   * user_identities: an account's connected Microsoft / Google identity (stable subject, never the email)
--   * sso_states: one row per started sign-in (state stored only as a SHA-256 hash, 10 minutes, single use)
--   * universities.microsoft_tenant_id: the university's own Microsoft organisation; only then is a first
--     Microsoft sign-in matched by email (Microsoft email addresses are not guaranteed to be verified)
create table user_identities (
    id binary(16) not null,
    user_id binary(16) not null,
    provider varchar(20) not null,
    subject varchar(255) not null,
    email varchar(160),
    created_at datetime(6) not null,
    last_used_at datetime(6),
    primary key (id),
    constraint fk_user_identities_user foreign key (user_id) references admin_users (id) on delete cascade
) engine=InnoDB;
create unique index ux_user_identities_provider_subject on user_identities (provider, subject);
create unique index ux_user_identities_user_provider on user_identities (user_id, provider);

create table sso_states (
    id binary(16) not null,
    state_hash varchar(64) not null,
    provider varchar(20) not null,
    purpose varchar(10) not null,
    user_id binary(16),
    nonce varchar(100) not null,
    code_verifier varchar(100) not null,
    return_path varchar(40),
    expires_at datetime(6) not null,
    created_at datetime(6) not null,
    primary key (id),
    constraint fk_sso_states_user foreign key (user_id) references admin_users (id) on delete cascade
) engine=InnoDB;
create unique index ux_sso_states_hash on sso_states (state_hash);
create index idx_sso_states_expires on sso_states (expires_at);

alter table universities add column microsoft_tenant_id varchar(64);
