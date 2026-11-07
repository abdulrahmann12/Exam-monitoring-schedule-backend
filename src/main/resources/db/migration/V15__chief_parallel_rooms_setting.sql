-- V15: how many rooms one chief invigilator may cover at the same time (per university, was fixed at 2).
alter table settings add column chief_max_parallel_rooms integer not null default 2;
