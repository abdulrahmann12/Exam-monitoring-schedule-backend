package schedule.example.schedule.repository;

import java.util.UUID;

public interface PersonWorkloadCount {

	UUID getPersonId();

	long getAssignmentCount();
}
