-- V17: signature blocks printed at the foot of exported schedules (e.g. "Head of Schedules Committee — Dr. ...").
--   A small JSON list per university; NULL / empty = no signature lines.
alter table settings add column document_signatures varchar(2000) null;
