package com.uniguard.schedule.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.uniguard.schedule.entity.TimeSlot;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

        @Query("""
                select ts from TimeSlot ts
                where (:activeOnly is null or ts.active = :activeOnly)
                and (:label is null or lower(ts.label) like lower(concat('%', :label, '%')))
                """)
        Page<TimeSlot> search(
                @Param("label") String label,
                @Param("activeOnly") Boolean activeOnly,
                Pageable pageable
        );

	/** Active time slots (shared by every exam period), ordered for display — used by the bulk schedule upload. */
	List<TimeSlot> findByActiveTrueOrderBySortOrderAsc();
}
