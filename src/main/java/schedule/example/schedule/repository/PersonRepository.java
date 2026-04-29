package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.enums.PersonRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<Person, UUID> {

	long countByRoleAndActiveTrue(PersonRole role);

	@Query("""
			select p from Person p
			where p.active = true
			order by p.totalAssignments desc
			""")
	List<Person> findTop10ByActiveTrueOrderByTotalAssignmentsDesc(Pageable pageable);

	@Query("""
		select p from Person p
		where (:role is null or p.role = :role)
		and (:department is null or lower(p.department) like lower(concat('%', :department, '%')))
		and (:name is null or lower(p.name) like lower(concat('%', :name, '%')))
		""")
	Page<Person> search(
		@Param("role") PersonRole role,
		@Param("department") String department,
		@Param("name") String name,
		Pageable pageable
	);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Person p set p.totalAssignments = p.totalAssignments + :delta where p.id = :personId")
	int adjustTotalAssignments(@Param("personId") UUID personId, @Param("delta") int delta);

	Optional<Person> findByNormalizedName(String normalizedName);

	List<Person> findAllByNormalizedNameIsNull();

	@Query("select p.normalizedName from Person p where p.normalizedName is not null")
	List<String> findAllNormalizedNames();
}