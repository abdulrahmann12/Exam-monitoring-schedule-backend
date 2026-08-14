package schedule.example.schedule.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.ScheduleGroup;
import schedule.example.schedule.entity.TimeSlot;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

        @Query("""
                select ts from TimeSlot ts
                where ts.scheduleGroup.id = :groupId
                and (:activeOnly is null or ts.active = :activeOnly)
                and (:label is null or lower(ts.label) like lower(concat('%', :label, '%')))
                """)
        Page<TimeSlot> search(
                @Param("groupId") UUID groupId,
                @Param("label") String label,
                @Param("activeOnly") Boolean activeOnly,
                Pageable pageable
        );

	/** Active time slots for a group, ordered for display — used by the bulk schedule upload template/validation. */
	List<TimeSlot> findByScheduleGroupIdAndActiveTrueOrderBySortOrderAsc(UUID scheduleGroupId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update TimeSlot ts set ts.scheduleGroup = :group where ts.scheduleGroup is null")
	int attachOrphansToGroup(@Param("group") ScheduleGroup group);

	long countByScheduleGroupId(UUID scheduleGroupId);

	void deleteByScheduleGroupId(UUID scheduleGroupId);

	@Query("""
		select ts.scheduleGroup.id as groupId, count(ts) as total
		from TimeSlot ts
		where ts.scheduleGroup.id in :groupIds
		group by ts.scheduleGroup.id
		""")
	List<GroupCountProjection> countByScheduleGroupIdIn(@Param("groupIds") Collection<UUID> groupIds);
}