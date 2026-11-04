-- V1: baseline schema — exactly what Hibernate (ddl-auto=update) produced before Flyway was introduced.
--
-- Existing databases already contain these tables: Flyway marks them as baselined at V1
-- (spring.flyway.baseline-on-migrate=true) and this script is skipped for them.
-- Fresh databases run it to start from the same state.

    create table admin_users (
        id binary(16) not null,
        created_at datetime(6) not null,
        email varchar(160) not null,
        password_hash varchar(255) not null,
        role enum ('ADMIN','DEMO_ADMIN') not null,
        updated_at datetime(6) not null,
        primary key (id)
    ) engine=InnoDB;

    create table invigilator_assignments (
        id binary(16) not null,
        created_at datetime(6) not null,
        position_index integer not null,
        required bit not null,
        invigilator_id binary(16),
        room_assignment_id binary(16) not null,
        primary key (id)
    ) engine=InnoDB;

    create table people (
        id binary(16) not null,
        active bit not null,
        created_at datetime(6) not null,
        department varchar(160) not null,
        email varchar(255),
        max_parallel_rooms integer not null,
        name varchar(160) not null,
        normalized_name varchar(160),
        role enum ('CHIEF_INVIGILATOR','INVIGILATOR') not null,
        total_assignments integer not null,
        updated_at datetime(6) not null,
        primary key (id)
    ) engine=InnoDB;

    create table person_available_days (
        person_id binary(16) not null,
        available_day enum ('FRI','MON','SAT','SUN','THU','TUE','WED') not null,
        primary key (person_id, available_day)
    ) engine=InnoDB;

    create table room_assignments (
        id binary(16) not null,
        created_at datetime(6) not null,
        exam_date date not null,
        generation_version integer not null,
        is_locked bit not null,
        source enum ('GENERATED','MANUAL','MIXED') not null,
        subject_code varchar(40),
        subject_name varchar(160),
        updated_at datetime(6) not null,
        chief_invigilator_id binary(16),
        room_id binary(16) not null,
        schedule_group_id binary(16),
        time_slot_id binary(16) not null,
        primary key (id)
    ) engine=InnoDB;

    create table rooms (
        id binary(16) not null,
        active bit not null,
        capacity integer not null,
        created_at datetime(6) not null,
        min_invigilators integer not null,
        name varchar(120) not null,
        normalized_name varchar(120),
        type enum ('LARGE','MEDIUM','SMALL') not null,
        updated_at datetime(6) not null,
        primary key (id)
    ) engine=InnoDB;

    create table schedule_groups (
        id binary(16) not null,
        active bit not null,
        archived bit not null,
        created_at datetime(6) not null,
        description varchar(500),
        name varchar(120) not null,
        updated_at datetime(6) not null,
        primary key (id)
    ) engine=InnoDB;

    create table settings (
        id binary(16) not null,
        app_tagline varchar(200),
        department varchar(160),
        exam_period varchar(120) not null,
        logo_url varchar(500),
        system_name varchar(160) not null,
        theme enum ('DARK','LIGHT') not null,
        university_name varchar(200) not null,
        primary key (id)
    ) engine=InnoDB;

    create table time_slots (
        id binary(16) not null,
        active bit not null,
        created_at datetime(6) not null,
        end_time time(6) not null,
        label varchar(120),
        sort_order integer not null,
        start_time time(6) not null,
        updated_at datetime(6) not null,
        schedule_group_id binary(16),
        primary key (id)
    ) engine=InnoDB;

    alter table admin_users 
       add constraint idx_admin_user_email unique (email);

    create index idx_invigilator_assignment_person 
       on invigilator_assignments (invigilator_id);

    create index idx_invigilator_assignment_room 
       on invigilator_assignments (room_assignment_id);

    create index idx_person_role 
       on people (role);

    create index idx_person_name 
       on people (name);

    create index idx_person_active 
       on people (active);

    create index idx_person_department 
       on people (department);

    alter table people 
       add constraint idx_person_normalized_name unique (normalized_name);

    alter table people 
       add constraint idx_person_email unique (email);

    create index idx_person_available_days_person_id 
       on person_available_days (person_id);

    create index idx_room_assignment_date 
       on room_assignments (exam_date);

    create index idx_room_assignment_time_slot 
       on room_assignments (time_slot_id);

    create index idx_room_assignment_chief 
       on room_assignments (chief_invigilator_id);

    create index idx_room_assignment_locked 
       on room_assignments (is_locked);

    create index idx_ra_slot_date_chief 
       on room_assignments (time_slot_id, exam_date, chief_invigilator_id);

    create index idx_ra_date_slot 
       on room_assignments (exam_date, time_slot_id);

    create index idx_ra_schedule_group 
       on room_assignments (schedule_group_id);

    create index idx_ra_group_slot_date_chief 
       on room_assignments (schedule_group_id, time_slot_id, exam_date, chief_invigilator_id);

    alter table room_assignments 
       add constraint uk_room_assignment_date_slot_room unique (exam_date, time_slot_id, room_id);

    alter table room_assignments 
       add constraint uk_ra_group_date_slot_room unique (schedule_group_id, exam_date, time_slot_id, room_id);

    create index idx_room_name 
       on rooms (name);

    create index idx_room_type 
       on rooms (type);

    create index idx_room_active 
       on rooms (active);

    alter table rooms 
       add constraint idx_room_normalized_name unique (normalized_name);

    create index idx_schedule_group_active 
       on schedule_groups (active);

    alter table schedule_groups 
       add constraint idx_schedule_group_name unique (name);

    create index idx_time_slot_sort_order 
       on time_slots (sort_order);

    create index idx_time_slot_active 
       on time_slots (active);

    create index idx_time_slot_schedule_group 
       on time_slots (schedule_group_id);

    alter table invigilator_assignments 
       add constraint FKjrd7x0hpyrxiyievjpdlk84io 
       foreign key (invigilator_id) 
       references people (id);

    alter table invigilator_assignments 
       add constraint FK2uardnig9c1irlr974eay4rg3 
       foreign key (room_assignment_id) 
       references room_assignments (id);

    alter table person_available_days 
       add constraint FK64xuylary1umtmv3jgx1m3jrg 
       foreign key (person_id) 
       references people (id);

    alter table room_assignments 
       add constraint FKnsj703sohm72hkijcmno2bmcn 
       foreign key (chief_invigilator_id) 
       references people (id);

    alter table room_assignments 
       add constraint FKt96wkyclodjlrg52xftxyve1h 
       foreign key (room_id) 
       references rooms (id);

    alter table room_assignments 
       add constraint FKmxbudffkk9rb9forgngdpep54 
       foreign key (schedule_group_id) 
       references schedule_groups (id) 
       on delete cascade;

    alter table room_assignments 
       add constraint FKjuca4u70jqhp8q6t3gcfug5u4 
       foreign key (time_slot_id) 
       references time_slots (id);

    alter table time_slots 
       add constraint FKfy5mfwmduy3eh7ywba8rbtple 
       foreign key (schedule_group_id) 
       references schedule_groups (id) 
       on delete cascade;
