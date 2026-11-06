package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import schedule.example.schedule.entity.AuditEvent;

import java.time.Instant;
import java.util.UUID;

/** Append-only by design: the only delete is the retention clean-up. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

	@Modifying
	@Query("DELETE FROM AuditEvent e WHERE e.occurredAt < :cutoff")
	int deleteOlderThan(Instant cutoff);

	/** Owner panel: latest logged activity per university. */
	@Query("SELECT e.universityId, MAX(e.occurredAt) FROM AuditEvent e WHERE e.universityId IS NOT NULL GROUP BY e.universityId")
	java.util.List<Object[]> lastActivityByUniversity();

	/** Platform-level entries (owner actions, sign-ins of accounts without a university). */
	org.springframework.data.domain.Page<AuditEvent> findByUniversityIdIsNull(org.springframework.data.domain.Pageable pageable);
}
