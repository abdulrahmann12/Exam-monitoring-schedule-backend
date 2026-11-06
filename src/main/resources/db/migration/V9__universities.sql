-- V9: several universities on one server (Phase 2, step 5)
--   * every scheduling table gets university_id; all existing data becomes the first university
--     (id 00000000-0000-0000-0000-000000000001, the id the single settings row always had)
--   * names that had to be unique server-wide are now unique per university
--   * user accounts and activity-log entries also record their university

create table universities (
    id binary(16) not null,
    name varchar(160) not null,
    slug varchar(80) not null,
    active bit not null default 1,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index ux_universities_slug on universities (slug);

-- Older versions always stored the single settings row with this id. Make sure of it before linking it to
-- the first university (a different id would break the foreign key below halfway through this script).
-- Only when there is exactly one row; the derived table lets MySQL/MariaDB read the table being updated.
update settings set id = 0x00000000000000000000000000000001
where id <> 0x00000000000000000000000000000001
  and (select n from (select count(*) as n from settings) as single_row) = 1;

insert into universities (id, name, slug, active, created_at)
select 0x00000000000000000000000000000001,
       coalesce((select s.university_name from settings s where s.id = 0x00000000000000000000000000000001), 'University'),
       'default',
       1,
       now(6);

-- ── Scheduling data ─────────────────────────────────────────────────────────

alter table people add column university_id binary(16);
update people set university_id = 0x00000000000000000000000000000001;
alter table people modify column university_id binary(16) not null;
create index idx_people_university on people (university_id);
alter table people add constraint fk_people_university foreign key (university_id) references universities (id);

alter table rooms add column university_id binary(16);
update rooms set university_id = 0x00000000000000000000000000000001;
alter table rooms modify column university_id binary(16) not null;
create index idx_rooms_university on rooms (university_id);
alter table rooms add constraint fk_rooms_university foreign key (university_id) references universities (id);

alter table schedule_groups add column university_id binary(16);
update schedule_groups set university_id = 0x00000000000000000000000000000001;
alter table schedule_groups modify column university_id binary(16) not null;
create index idx_schedule_groups_university on schedule_groups (university_id);
alter table schedule_groups add constraint fk_schedule_groups_university foreign key (university_id) references universities (id);

alter table time_slots add column university_id binary(16);
update time_slots set university_id = 0x00000000000000000000000000000001;
alter table time_slots modify column university_id binary(16) not null;
create index idx_time_slots_university on time_slots (university_id);
alter table time_slots add constraint fk_time_slots_university foreign key (university_id) references universities (id);

alter table room_assignments add column university_id binary(16);
update room_assignments set university_id = 0x00000000000000000000000000000001;
alter table room_assignments modify column university_id binary(16) not null;
create index idx_room_assignments_university on room_assignments (university_id);
alter table room_assignments add constraint fk_room_assignments_university foreign key (university_id) references universities (id);

alter table invigilator_assignments add column university_id binary(16);
update invigilator_assignments set university_id = 0x00000000000000000000000000000001;
alter table invigilator_assignments modify column university_id binary(16) not null;
create index idx_invigilator_assignments_university on invigilator_assignments (university_id);
alter table invigilator_assignments add constraint fk_invigilator_assignments_university foreign key (university_id) references universities (id);

alter table person_unavailability add column university_id binary(16);
update person_unavailability set university_id = 0x00000000000000000000000000000001;
alter table person_unavailability modify column university_id binary(16) not null;
create index idx_person_unavailability_university on person_unavailability (university_id);
alter table person_unavailability add constraint fk_person_unavailability_university foreign key (university_id) references universities (id);

-- Settings: one row per university; the row id is the university id.
alter table settings add column university_id binary(16);
update settings set university_id = id;
alter table settings modify column university_id binary(16) not null;
create index idx_settings_university on settings (university_id);
alter table settings add constraint fk_settings_university foreign key (university_id) references universities (id);

-- ── Unique per university instead of server-wide ────────────────────────────

alter table people drop index idx_person_normalized_name;
create unique index ux_person_university_normalized_name on people (university_id, normalized_name);
alter table people drop index idx_person_email;
create unique index ux_person_university_email on people (university_id, email);

alter table rooms drop index idx_room_normalized_name;
create unique index ux_room_university_normalized_name on rooms (university_id, normalized_name);

alter table schedule_groups drop index idx_schedule_group_name;
create unique index ux_schedule_group_university_name on schedule_groups (university_id, name);

-- ── Accounts and activity log ───────────────────────────────────────────────

-- Nullable: reserved for the platform owner, who belongs to no single university.
alter table admin_users add column university_id binary(16);
update admin_users set university_id = 0x00000000000000000000000000000001;
create index idx_admin_users_university on admin_users (university_id);
alter table admin_users add constraint fk_admin_users_university foreign key (university_id) references universities (id);

-- No foreign key: the history must outlive anything it mentions.
alter table audit_events add column university_id binary(16);
update audit_events set university_id = 0x00000000000000000000000000000001;
create index idx_audit_events_university_time on audit_events (university_id, occurred_at);
