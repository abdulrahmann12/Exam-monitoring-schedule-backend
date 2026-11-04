package schedule.example.schedule.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** One person's presence in one (date, start, end) sitting. */
public interface PersonDutyWindow {

	UUID getPersonId();

	LocalDate getExamDate();

	LocalTime getStartTime();

	LocalTime getEndTime();
}
