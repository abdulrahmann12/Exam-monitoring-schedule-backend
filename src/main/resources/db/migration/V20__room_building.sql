-- V20: the building a room is in ("H" for H101). Split exams stay in one building when possible, and a chief who
-- covers several rooms at the same time gets them in one building. Empty = not known (the old behaviour).
-- Existing rooms get a building guessed from their name at start-up (RoomCategoryService.ensureDefaults).
alter table rooms add column building varchar(60);
