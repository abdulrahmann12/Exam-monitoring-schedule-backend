-- V16: time slots are shared by every exam period of a university (like people and rooms).
--
-- Until now each exam period (schedule group) had its own copy of its slots, so two periods could
-- both have "Morning 09:00-12:00". Identical slots (same university, start, end and label) are merged
-- into the oldest one and room assignments are moved onto it. An assignment is NOT moved when that
-- would put two exams in the same room, date and slot (unique key uk_room_assignment_date_slot_room);
-- it keeps its old slot, which then simply stays as a separate slot.
--
-- time_slots.schedule_group_id is no longer used by the application and is set to NULL. The column
-- (and its foreign key, whose generated name differs between databases) is left in place on purpose
-- so this script runs the same on MySQL, MariaDB and H2; a later clean-up may drop it.
--
-- Written in plain SQL that MySQL, MariaDB and H2 (MySQL mode) all accept: no UPDATE ... JOIN, and no
-- sub-query on the table being changed (MySQL error 1093) - helper tables are used instead.

create table v16_slot_map (
    dup_id binary(16) not null,
    keep_id binary(16) not null,
    dup_active boolean not null,
    primary key (dup_id)
);

insert into v16_slot_map (dup_id, keep_id, dup_active)
select d.id,
       (select k.id from time_slots k
         where k.university_id = d.university_id
           and k.start_time = d.start_time
           and k.end_time = d.end_time
           and coalesce(k.label, '') = coalesce(d.label, '')
         order by k.created_at, k.id
         limit 1),
       d.active
from time_slots d;

delete from v16_slot_map where dup_id = keep_id;

-- Assignments that must stay on their old slot: the room already has an exam in the merged slot that day
-- (on the slot that is kept, or on an earlier duplicate of it that moves first).
create table v16_blocked (
    assignment_id binary(16) not null,
    primary key (assignment_id)
);

insert into v16_blocked (assignment_id)
select ra.id
from room_assignments ra, v16_slot_map m
where ra.time_slot_id = m.dup_id
  and exists (
      select 1 from room_assignments o
       where o.exam_date = ra.exam_date
         and o.room_id = ra.room_id
         and o.id <> ra.id
         and (o.time_slot_id = m.keep_id
              or (o.id < ra.id and o.time_slot_id in (select m2.dup_id from v16_slot_map m2 where m2.keep_id = m.keep_id))));

update room_assignments
   set time_slot_id = (select m.keep_id from v16_slot_map m where m.dup_id = room_assignments.time_slot_id)
 where time_slot_id in (select dup_id from v16_slot_map)
   and id not in (select assignment_id from v16_blocked);

-- A merged slot stays usable if any of its copies was active.
update time_slots
   set active = true
 where id in (select keep_id from v16_slot_map where dup_active = true);

-- Copies that no assignment uses any more are removed.
delete from time_slots
 where id in (select dup_id from v16_slot_map)
   and id not in (select time_slot_id from room_assignments);

-- Slots no longer belong to one exam period.
update time_slots set schedule_group_id = null;

drop table v16_blocked;
drop table v16_slot_map;
