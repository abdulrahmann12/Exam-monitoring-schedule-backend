-- V18: room types per university ("Classroom", "Computer Lab", "Chemistry Lab", "Drawing Studio", ...).
-- special = 1: the room is only used for exams that ask for that type (labs, studios). Normal exams never get it.
-- Every university gets a default list at start-up (RoomCategoryService.ensureDefaults), which also gives
-- existing rooms a type: rooms named like a lab get "Lab", the others "Classroom".
create table room_categories (
    id binary(16) not null,
    university_id binary(16) not null,
    name varchar(60) not null,
    normalized_name varchar(60) not null,
    special bit not null default 0,
    sort_order integer not null default 0,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint ux_room_category_university_name unique (university_id, normalized_name)
) engine=InnoDB;

alter table room_categories add constraint fk_room_categories_university foreign key (university_id) references universities (id);

-- Empty = no type yet: treated as a normal room.
alter table rooms add column room_category_id binary(16);
alter table rooms add constraint fk_rooms_room_category foreign key (room_category_id) references room_categories (id);
create index idx_room_category on rooms (room_category_id);
