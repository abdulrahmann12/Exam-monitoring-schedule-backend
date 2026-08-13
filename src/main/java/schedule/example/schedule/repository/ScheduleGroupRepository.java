package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.ScheduleGroup;

import java.util.Optional;
import java.util.UUID;

public interface ScheduleGroupRepository extends JpaRepository<ScheduleGroup, UUID> {

	Optional<ScheduleGroup> findByNameIgnoreCase(String name);

	Optional<ScheduleGroup> findFirstByOrderByCreatedAtAsc();

	@Query("""
		select g from ScheduleGroup g
		where (:activeOnly is null or g.active = :activeOnly)
		and (:name is null or lower(g.name) like lower(concat(:name, '%')))
		""")
	Page<ScheduleGroup> search(
		@Param("name") String name,
		@Param("activeOnly") Boolean activeOnly,
		Pageable pageable
	);
}
