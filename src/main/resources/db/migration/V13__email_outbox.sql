-- V13: reliable email (Phase 2, extra step C)
--   * every email goes through this outbox: schedule emails are stored with their PDF and sent by a
--     background worker with retries; account emails (invitation, password reset) contain one-time
--     links, so only their delivery is recorded here (no content)
--   * dedupe_key + content_hash: a schedule that was already sent unchanged is not sent again
create table email_outbox (
    id binary(16) not null,
    attachment mediumblob,
    attachment_name varchar(200),
    attempts integer not null,
    batch_id binary(16),
    claimed_at datetime(6),
    content_hash varchar(64),
    created_at datetime(6) not null,
    created_by varchar(160),
    dedupe_key varchar(200),
    html_body mediumtext,
    kind varchar(30) not null,
    last_error varchar(500),
    next_attempt_at datetime(6),
    provider_message_id varchar(200),
    recipient varchar(160) not null,
    recipient_name varchar(160),
    sent_at datetime(6),
    status varchar(12) not null,
    subject varchar(300) not null,
    university_id binary(16),
    primary key (id)
) engine=InnoDB;

create index idx_email_outbox_due on email_outbox (status, next_attempt_at);
create index idx_email_outbox_university on email_outbox (university_id, created_at);
create index idx_email_outbox_dedupe on email_outbox (dedupe_key);
