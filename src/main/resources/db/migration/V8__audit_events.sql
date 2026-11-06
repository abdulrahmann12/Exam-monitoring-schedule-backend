-- V8: activity log (Phase 2, step 4)
--   Append-only: the application never updates or deletes rows, except the retention clean-up.
--   No foreign key to admin_users on purpose: the history must survive when a user is deleted,
--   so the actor's email is stored as it was at the time.
create table audit_events (
    id binary(16) not null,
    occurred_at datetime(6) not null,
    actor_id binary(16),
    actor_email varchar(160),
    action varchar(60) not null,
    category varchar(30) not null,
    target_type varchar(40),
    target_id varchar(64),
    summary varchar(500) not null,
    outcome varchar(20) not null,
    ip_address varchar(64),
    primary key (id)
) engine=InnoDB;

create index idx_audit_events_occurred_at on audit_events (occurred_at);
create index idx_audit_events_category_time on audit_events (category, occurred_at);
create index idx_audit_events_actor_email on audit_events (actor_email);
create index idx_audit_events_action on audit_events (action);
