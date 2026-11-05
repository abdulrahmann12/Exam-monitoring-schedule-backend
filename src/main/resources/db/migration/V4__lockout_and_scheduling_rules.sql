-- V4: account lockout + remaining Phase 1 scheduling rules
--   * admin account lockout after repeated wrong passwords
--   * staff gender + gender-separated halls
--   * subject codes a person teaches (may not invigilate those exams)
--   * student count per exam room (drives required invigilators)
--   * settings: max duties per exam period, minimum rest, students per invigilator

alter table admin_users add column failed_login_attempts integer not null default 0;
alter table admin_users add column locked_until datetime(6);

alter table people add column gender enum ('FEMALE','MALE');

create table person_subject_codes (
    person_id binary(16) not null,
    subject_code varchar(40) not null,
    primary key (person_id, subject_code)
) engine=InnoDB;

create index idx_person_subject_codes_person_id on person_subject_codes (person_id);

alter table person_subject_codes
    add constraint FKaye56xwhixkj664cc8p34mjpf
    foreign key (person_id) references people (id);

alter table rooms add column hall_gender enum ('FEMALE','MALE','MIXED') not null default 'MIXED';

alter table room_assignments add column student_count integer;

alter table settings add column max_duties_per_period integer not null default 0;
alter table settings add column min_rest_minutes integer not null default 0;
alter table settings add column students_per_invigilator integer not null default 0;
