-- V21: the Scheduler, Timeline, Reports and exports read an exam period's rooms by date (and time slot).
-- With this index the database reads them already in that order instead of collecting and sorting them.
create index idx_ra_group_date_slot on room_assignments (schedule_group_id, exam_date, time_slot_id);
