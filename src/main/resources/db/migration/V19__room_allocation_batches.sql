-- V19: every "Save rooms" of the Room Allocation page is one batch, so it can be undone as a whole.
create table room_allocation_batches (
    id binary(16) not null,
    university_id binary(16) not null,
    schedule_group_id binary(16) not null,
    file_name varchar(255),
    created_by varchar(255),
    exams integer not null,
    exam_rooms integer not null,
    students_unplaced integer not null,
    undone_at datetime(6),
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

alter table room_allocation_batches add constraint fk_room_allocation_batches_university foreign key (university_id) references universities (id);
alter table room_allocation_batches add constraint fk_room_allocation_batches_group foreign key (schedule_group_id) references schedule_groups (id) on delete cascade;
create index idx_room_allocation_batches_group on room_allocation_batches (schedule_group_id, created_at);

-- Which save created an exam room (empty for exam rooms made in the Scheduler or by the timetable import).
alter table room_assignments add column allocation_batch_id binary(16);
create index idx_ra_allocation_batch on room_assignments (allocation_batch_id);
