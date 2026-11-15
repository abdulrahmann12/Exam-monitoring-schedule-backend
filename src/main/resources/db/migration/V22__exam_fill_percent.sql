-- V22: Room Allocation seats at most this percentage of each room's seats (gaps between students against cheating).
-- 100 = every seat (as before). The rooms' own capacities are not changed.
alter table settings add column exam_fill_percent integer not null default 100;
