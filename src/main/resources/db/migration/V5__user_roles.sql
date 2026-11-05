-- V5: multiple users with roles (Phase 2, step 1)
--   * new roles SCHEDULER and VIEWER next to ADMIN / DEMO_ADMIN
--   * display name, enabled flag, forced password change, last sign-in time
alter table admin_users modify column role enum ('ADMIN','SCHEDULER','VIEWER','DEMO_ADMIN') not null;

alter table admin_users add column display_name varchar(120);
alter table admin_users add column enabled bit not null default 1;
alter table admin_users add column must_change_password bit not null default 0;
alter table admin_users add column last_login_at datetime(6);
