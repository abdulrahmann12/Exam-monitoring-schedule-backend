-- V2: scheduling integrity (Phase 1)
--   * optimistic-locking version columns
--   * date-specific staff leave
--   * institution-wide "max duty sessions per person per day" setting

alter table people           add column version bigint not null default 0;
alter table room_assignments add column version bigint not null default 0;
alter table time_slots       add column version bigint not null default 0;

alter table settings add column max_duties_per_day integer not null default 0;

create table person_unavailability (
    id binary(16) not null,
    created_at datetime(6) not null,
    end_date date not null,
    reason varchar(200),
    start_date date not null,
    person_id binary(16) not null,
    primary key (id)
) engine=InnoDB;

create index idx_unavailability_person on person_unavailability (person_id);
create index idx_unavailability_range  on person_unavailability (start_date, end_date);

alter table person_unavailability
    add constraint FKe5vhkiiy86n7iaed5yxcpjie
    foreign key (person_id) references people (id) on delete cascade;
