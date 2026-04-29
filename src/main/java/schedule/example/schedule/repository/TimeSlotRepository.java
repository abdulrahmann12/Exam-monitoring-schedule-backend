package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.TimeSlot;

import java.util.UUID;

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
}