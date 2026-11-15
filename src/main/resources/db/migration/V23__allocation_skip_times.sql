-- V23: Room Allocation remembers, per exam period, the time slots the user unticked under "Time slots to use"
-- (comma-separated "HH:mm-HH:mm"). Empty = every time slot may be used (as before).
alter table schedule_groups add column allocation_skip_times varchar(2000);
