package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.enums.RoomType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

	long countByActiveTrue();

	Optional<Room> findByNormalizedName(String normalizedName);

	List<Room> findAllByNormalizedNameIsNull();

	@Query("select r.normalizedName from Room r where r.normalizedName is not null")
	List<String> findAllNormalizedNames();

	@Query("""
		select r from Room r
		where (:type is null or r.type = :type)
		and (:name is null or lower(r.name) like lower(concat('%', :name, '%')))
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