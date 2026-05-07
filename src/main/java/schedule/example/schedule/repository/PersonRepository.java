package schedule.example.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.enums.PersonRole;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<Person, UUID> {

	long countByRoleAndActiveTrue(PersonRole role);

	@Query("""
			select p from Person p
			where p.active = true
			order by p.totalAssignments desc
			""")
	List<Person> findTop10ByActiveTrueOrderByTotalAssignmentsDesc(Pageable pageable);

	/**
	 * Optimized search with index-friendly predicates.
	 *
	 * <p><strong>Performance fix:</strong>
	 * <ul>
	 *   <li>{@code name}: searches {@code normalizedName} (already lowercased + trimmed) with a
	 *       trailing-only wildcard — e.g. {@code 'ahmed%'}. This uses the
	 *       {@code idx_person_normalized_name} B-tree index for a fast prefix range scan instead
	 *       of a full table scan. The caller must pass the value pre-normalized via
	 *       {@link schedule.example.schedule.util.NameNormalizationUtil#normalizeForComparison}
	 *       with {@code '%'} appended.</li>
	 *   <li>{@code department}: trailing-wildcard LIKE on the indexed {@code department} column
	 *       (no leading {@code %}) allows MySQL to use {@code idx_person_department}.</li>
	 * </ul>
	 */
	@Query("""
		select p from Person p
		where (:role is null or p.role = :role)
		and (:department is null or p.department like :department)
		and (:name is null or p.normalizedName like :name)
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

	/**
	 * Loads people whose normalizedName has not been set yet — used for the one-time backfill.
	 * Pageable limits the result to a fixed batch size so the entire table is never loaded into
	 * memory at once (OOM fix for large datasets).
	 */
	@Query("select p from Person p where p.normalizedName is null")
	List<Person> findByNormalizedNameIsNullPaged(Pageable pageable);

	List<Person> findAllByNormalizedNameIsNull();

	@Query("select p.normalizedName from Person p where p.normalizedName is not null")
	List<String> findAllNormalizedNames();

	/**
	 * Returns the subset of {@code candidates} that already exist as normalized names in the DB.
	 * Used by bulk-upload deduplication to avoid loading the entire name table into memory when
	 * only a small batch of incoming names needs conflict detection.
	 */
	@Query("select p.normalizedName from Person p where p.normalizedName in :candidates")
	Set<String> findNormalizedNamesIn(@Param("candidates") Collection<String> candidates);
}