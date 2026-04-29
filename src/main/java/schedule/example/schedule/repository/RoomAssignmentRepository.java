package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.RoomAssignment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomAssignmentRepository extends JpaRepository<RoomAssignment, UUID> {

	boolean existsByRoomId(UUID roomId);

	boolean existsByTimeSlotId(UUID timeSlotId);

	boolean existsByChiefInvigilatorId(UUID chiefInvigilatorId);

	boolean existsByRoomIdAndTimeSlotIdAndExamDate(UUID roomId, UUID timeSlotId, LocalDate examDate);

	boolean existsByRoomIdAndTimeSlotIdAndExamDateAndIdNot(UUID roomId, UUID timeSlotId, LocalDate examDate, UUID id);

	long countByTimeSlotIdAndExamDateAndChiefInvigilatorId(UUID timeSlotId, LocalDate examDate, UUID chiefInvigilatorId);

	long countByTimeSlotIdAndExamDateAndChiefInvigilatorIdAndIdNot(UUID timeSlotId, LocalDate examDate, UUID chiefInvigilatorId, UUID id);

	@Query("""
		select ra.id from RoomAssignment ra
		where (:slotId is null or ra.timeSlot.id = :slotId)
		and (:roomId is null or ra.room.id = :roomId)
		and (:locked is null or ra.locked = :locked)
		and (:fromDate is null or ra.examDate >= :fromDate)
		and (:toDate is null or ra.examDate <= :toDate)
		""")
	Page<UUID> findIdsByFilters(
		@Param("slotId") UUID slotId,
		@Param("roomId") UUID roomId,
		@Param("locked") Boolean locked,
		@Param("fromDate") LocalDate fromDate,
		@Param("toDate") LocalDate toDate,
		Pageable pageable
	);

	@EntityGraph(attributePaths = {
		"room",
		"timeSlot",
		"chiefInvigilator",
		"invigilatorAssignments",
		"invigilatorAssignments.invigilator"
	})
	@Query("""
		select distinct ra from RoomAssignment ra
		where ra.examDate in :examDates
		and ra.timeSlot.id in :timeSlotIds
		""")
	List<RoomAssignment> findAllDetailedByExamDateInAndTimeSlotIdIn(
		@Param("examDates") Collection<LocalDate> examDates,
		@Param("timeSlotIds") Collection<UUID> timeSlotIds
	);

	@EntityGraph(attributePaths = {
		"room",
		"timeSlot",
		"chiefInvigilator",
		"invigilatorAssignments",
		"invigilatorAssignments.invigilator"
	})
	@Query("select distinct ra from RoomAssignment ra where ra.id in :ids")
	List<RoomAssignment> findAllDetailedByIdIn(@Param("ids") Collection<UUID> ids);

	@EntityGraph(attributePaths = {
		"room",
		"timeSlot",
		"chiefInvigilator",
		"invigilatorAssignments",
		"invigilatorAssignments.invigilator"
	})
	@Query("select ra from RoomAssignment ra where ra.id = :id")
	Optional<RoomAssignment> findDetailedById(@Param("id") UUID id);

	@Query("""
		select distinct ra from RoomAssignment ra
		join fetch ra.timeSlot ts
		where ra.chiefInvigilator.id = :personId
		""")
	List<RoomAssignment> findAllChiefAssignmentsByPersonId(@Param("personId") UUID personId);

	@Query("""
		select distinct ra from RoomAssignment ra
		join fetch ra.timeSlot ts
		join ra.invigilatorAssignments ia
		where ia.invigilator.id = :personId
		""")
	List<RoomAssignment> findAllInvigilatorAssignmentsByPersonId(@Param("personId") UUID personId);
}