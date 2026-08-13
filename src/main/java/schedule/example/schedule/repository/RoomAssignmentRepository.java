package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.ScheduleGroup;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomAssignmentRepository extends JpaRepository<RoomAssignment, UUID> {

	boolean existsByRoomId(UUID roomId);

	boolean existsByTimeSlotId(UUID timeSlotId);

	boolean existsByChiefInvigilatorId(UUID chiefInvigilatorId);

	boolean existsByScheduleGroupIdAndRoomIdAndTimeSlotIdAndExamDate(
		UUID scheduleGroupId, UUID roomId, UUID timeSlotId, LocalDate examDate);

	boolean existsByScheduleGroupIdAndRoomIdAndTimeSlotIdAndExamDateAndIdNot(
		UUID scheduleGroupId, UUID roomId, UUID timeSlotId, LocalDate examDate, UUID id);

	long countByScheduleGroupIdAndTimeSlotIdAndExamDateAndChiefInvigilatorId(
		UUID scheduleGroupId, UUID timeSlotId, LocalDate examDate, UUID chiefInvigilatorId);

	long countByScheduleGroupIdAndTimeSlotIdAndExamDateAndChiefInvigilatorIdAndIdNot(
		UUID scheduleGroupId, UUID timeSlotId, LocalDate examDate, UUID chiefInvigilatorId, UUID id);

	@Query("""
		select ra.id from RoomAssignment ra
		where ra.scheduleGroup.id = :groupId
		and (:slotId is null or ra.timeSlot.id = :slotId)
		and (:roomId is null or ra.room.id = :roomId)
		and (:locked is null or ra.locked = :locked)
		and (:fromDate is null or ra.examDate >= :fromDate)
		and (:toDate is null or ra.examDate <= :toDate)
		""")
	Page<UUID> findIdsByFilters(
		@Param("groupId") UUID groupId,
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
		where ra.scheduleGroup.id = :groupId
		and ra.examDate in :examDates
		and ra.timeSlot.id in :timeSlotIds
		""")
	List<RoomAssignment> findAllDetailedByExamDateInAndTimeSlotIdIn(
		@Param("groupId") UUID groupId,
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

	/**
	 * Returns only the exam dates of future chief-invigilator assignments for a person.
	 *
	 * <p><strong>Performance fix:</strong> the previous version returned full {@code RoomAssignment}
	 * entities with {@code timeSlot} join-fetched. The caller only needs {@code examDate} to check
	 * availability. Selecting the scalar date eliminates loading of room, subject, source, and all
	 * other columns — drastically reducing data transferred from the DB.
	 */
	@Query("""
		select ra.examDate from RoomAssignment ra
		where ra.chiefInvigilator.id = :personId
		and ra.examDate >= :fromDate
		""")
	List<LocalDate> findFutureChiefExamDatesByPersonId(
		@Param("personId") UUID personId,
		@Param("fromDate") LocalDate fromDate
	);

	/**
	 * Returns only the exam dates of future invigilator assignments for a person.
	 *
	 * <p>Same rationale as {@link #findFutureChiefExamDatesByPersonId} — scalar projection
	 * instead of full entity loading.
	 */
	@Query("""
		select distinct ra.examDate from RoomAssignment ra
		join ra.invigilatorAssignments ia
		where ia.invigilator.id = :personId
		and ra.examDate >= :fromDate
		""")
	List<LocalDate> findFutureInvigilatorExamDatesByPersonId(
		@Param("personId") UUID personId,
		@Param("fromDate") LocalDate fromDate
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RoomAssignment ra set ra.scheduleGroup = :group where ra.scheduleGroup is null")
	int attachOrphansToGroup(@Param("group") ScheduleGroup group);

	long countByScheduleGroupId(UUID scheduleGroupId);

	@EntityGraph(attributePaths = {
		"room",
		"timeSlot",
		"chiefInvigilator",
		"invigilatorAssignments",
		"invigilatorAssignments.invigilator"
	})
	@Query("select distinct ra from RoomAssignment ra where ra.scheduleGroup.id = :groupId")
	List<RoomAssignment> findAllDetailedByScheduleGroupId(@Param("groupId") UUID groupId);

	@Query("""
		select ra.scheduleGroup.id as groupId, count(ra) as total
		from RoomAssignment ra
		where ra.scheduleGroup.id in :groupIds
		group by ra.scheduleGroup.id
		""")
	List<GroupCountProjection> countByScheduleGroupIdIn(@Param("groupIds") Collection<UUID> groupIds);

	@Query("""
		select ra.chiefInvigilator.id as personId, count(ra) as assignmentCount
		from RoomAssignment ra
		where ra.scheduleGroup.id = :groupId
		and ra.chiefInvigilator is not null
		group by ra.chiefInvigilator.id
		""")
	List<PersonWorkloadCount> countChiefAssignmentsByScheduleGroupId(@Param("groupId") UUID groupId);

	@EntityGraph(attributePaths = {
		"room",
		"timeSlot",
		"chiefInvigilator",
		"invigilatorAssignments",
		"invigilatorAssignments.invigilator"
	})
	@Query("""
		select distinct ra from RoomAssignment ra
		where ra.scheduleGroup.id = :groupId
		and ra.examDate in :examDates
		""")
	List<RoomAssignment> findAllDetailedByScheduleGroupIdAndExamDateIn(
		@Param("groupId") UUID groupId,
		@Param("examDates") Collection<LocalDate> examDates
	);
}

