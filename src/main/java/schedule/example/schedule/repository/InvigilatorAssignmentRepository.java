package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.InvigilatorAssignment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface InvigilatorAssignmentRepository extends JpaRepository<InvigilatorAssignment, UUID> {

	boolean existsByInvigilatorId(UUID invigilatorId);

	/**
	 * Counts how many times a specific invigilator is already assigned to the given slot + date,
	 * optionally excluding a specific room-assignment (for update validation).
	 *
	 * <p>Uses explicit {@code join ra} to ensure the query planner can leverage
	 * {@code idx_invigilator_assignment_person} and {@code idx_ra_slot_date_chief} together.
	 */
	@Query("""
		select count(ia) from InvigilatorAssignment ia
		join ia.roomAssignment ra
		where ra.scheduleGroup.id = :groupId
		and ra.timeSlot.id = :slotId
		and ra.examDate = :examDate
		and ia.invigilator.id = :invigilatorId
		and (:excludedRoomAssignmentId is null or ra.id <> :excludedRoomAssignmentId)
		""")
	long countSlotUsage(
		@Param("groupId") UUID groupId,
		@Param("slotId") UUID slotId,
		@Param("examDate") LocalDate examDate,
		@Param("invigilatorId") UUID invigilatorId,
		@Param("excludedRoomAssignmentId") UUID excludedRoomAssignmentId
	);

	/**
	 * Returns the IDs of invigilators from {@code invigilatorIds} that are already assigned
	 * to the given slot + date (double-booking check).
	 *
	 * <p><strong>Performance fix:</strong> replaces N individual {@code countSlotUsage} calls
	 * (one per invigilator) with a single IN-clause query. For a room with 5 invigilators this
	 * reduces 5 COUNT round-trips to 1 batch query.
	 */
	@Query("""
		select ia.invigilator.id from InvigilatorAssignment ia
		join ia.roomAssignment ra
		where ra.scheduleGroup.id = :groupId
		and ra.timeSlot.id = :slotId
		and ra.examDate = :examDate
		and ia.invigilator.id in :invigilatorIds
		and (:excludedRoomAssignmentId is null or ra.id <> :excludedRoomAssignmentId)
		""")
	Set<UUID> findDoubleBookedInvigilatorIds(
		@Param("groupId") UUID groupId,
		@Param("slotId") UUID slotId,
		@Param("examDate") LocalDate examDate,
		@Param("invigilatorIds") Collection<UUID> invigilatorIds,
		@Param("excludedRoomAssignmentId") UUID excludedRoomAssignmentId
	);

	@Query("""
		select ia.invigilator.id as personId, count(ia) as assignmentCount
		from InvigilatorAssignment ia
		join ia.roomAssignment ra
		where ra.scheduleGroup.id = :groupId
		and ia.invigilator is not null
		group by ia.invigilator.id
		""")
	List<PersonWorkloadCount> countInvigilatorAssignmentsByScheduleGroupId(@Param("groupId") UUID groupId);
}