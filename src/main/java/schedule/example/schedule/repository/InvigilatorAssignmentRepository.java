package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.InvigilatorAssignment;

import java.time.LocalDate;
import java.util.UUID;

public interface InvigilatorAssignmentRepository extends JpaRepository<InvigilatorAssignment, UUID> {

	boolean existsByInvigilatorId(UUID invigilatorId);

	@Query("""
		select count(ia) from InvigilatorAssignment ia
		where ia.roomAssignment.timeSlot.id = :slotId
		and ia.roomAssignment.examDate = :examDate
		and ia.invigilator.id = :invigilatorId
		and (:excludedRoomAssignmentId is null or ia.roomAssignment.id <> :excludedRoomAssignmentId)
		""")
	long countSlotUsage(
		@Param("slotId") UUID slotId,
		@Param("examDate") LocalDate examDate,
		@Param("invigilatorId") UUID invigilatorId,
		@Param("excludedRoomAssignmentId") UUID excludedRoomAssignmentId
	);
}