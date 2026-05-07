package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.enums.RoomType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

	long countByActiveTrue();

	Optional<Room> findByNormalizedName(String normalizedName);

	/**
	 * Loads rooms whose normalizedName has not been set — used for one-time backfill.
	 */
	@Query("select r from Room r where r.normalizedName is null")
	List<Room> findByNormalizedNameIsNullPaged(Pageable pageable);

	List<Room> findAllByNormalizedNameIsNull();

	@Query("select r.normalizedName from Room r where r.normalizedName is not null")
	List<String> findAllNormalizedNames();

	/**
	 * Returns only the normalized names from {@code candidates} that already exist in the DB.
	 */
	@Query("select r.normalizedName from Room r where r.normalizedName in :candidates")
	Set<String> findNormalizedNamesIn(@Param("candidates") Collection<String> candidates);

	/**
	 * Optimized search using index-friendly predicates.
	 *
	 * <p>{@code name} searches {@code normalizedName} with a trailing-only wildcard so that MySQL
	 * can perform a B-tree prefix scan on {@code idx_room_normalized_name} instead of a full
	 * table scan. The caller must pass the value pre-normalized (lowercased + trimmed) with
	 * {@code '%'} appended.
	 */
	@Query("""
		select r from Room r
		where (:type is null or r.type = :type)
		and (:name is null or r.normalizedName like :name)
		and (:minCapacity is null or r.capacity >= :minCapacity)
		and (:maxCapacity is null or r.capacity <= :maxCapacity)
		""")
	Page<Room> search(
		@Param("type") RoomType type,
		@Param("name") String name,
		@Param("minCapacity") Integer minCapacity,
		@Param("maxCapacity") Integer maxCapacity,
		Pageable pageable
	);
}