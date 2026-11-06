package com.uniguard.schedule.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.uniguard.schedule.entity.EmailMessage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

	/** For the delivery log: only these columns are read (never the content or the PDF). */
	interface Summary {
		UUID getId();

		UUID getBatchId();

		String getKind();

		String getRecipient();

		String getRecipientName();

		String getSubject();

		String getStatus();

		int getAttempts();

		Instant getNextAttemptAt();

		String getLastError();

		Instant getCreatedAt();

		Instant getSentAt();

		String getCreatedBy();
	}

	Page<Summary> findByUniversityIdOrderByCreatedAtDesc(UUID universityId, Pageable pageable);

	Page<Summary> findByUniversityIdAndStatusOrderByCreatedAtDesc(UUID universityId, String status, Pageable pageable);

	@Query("SELECT m.status, COUNT(m) FROM EmailMessage m WHERE m.universityId = :universityId GROUP BY m.status")
	List<Object[]> countByStatus(UUID universityId);

	/** The newest email for this recipient's schedule that was sent or is on its way. */
	@Query("SELECT m.id, m.contentHash, m.recipient FROM EmailMessage m WHERE m.dedupeKey = :key AND m.status IN :statuses ORDER BY m.createdAt DESC")
	List<Object[]> latestForKey(String key, Collection<String> statuses, Pageable pageable);

	/** A newer version replaces a version that has not been picked up yet. */
	@Modifying
	@Query("UPDATE EmailMessage m SET m.status = 'CANCELLED', m.lastError = 'Replaced by a newer version', m.htmlBody = NULL, m.attachment = NULL "
		+ "WHERE m.dedupeKey = :key AND m.status = 'QUEUED'")
	int cancelQueuedForKey(String key);

	/** Emails the worker should send now (also ones a crashed worker left in SENDING). */
	@Query("SELECT m.id FROM EmailMessage m WHERE m.kind = 'SCHEDULE' AND ((m.status = 'QUEUED' AND m.nextAttemptAt <= :now) "
		+ "OR (m.status = 'SENDING' AND m.claimedAt < :stale)) ORDER BY m.createdAt ASC")
	List<UUID> findDue(Instant now, Instant stale, Pageable pageable);

	/** Takes one email for this worker; 0 when another worker (or instance) was faster. */
	@Modifying
	@Query("UPDATE EmailMessage m SET m.status = 'SENDING', m.claimedAt = :now WHERE m.id = :id AND ((m.status = 'QUEUED' AND m.nextAttemptAt <= :now) "
		+ "OR (m.status = 'SENDING' AND m.claimedAt < :stale))")
	int claim(UUID id, Instant now, Instant stale);

	Optional<EmailMessage> findByIdAndUniversityId(UUID id, UUID universityId);

	@Modifying
	@Query("UPDATE EmailMessage m SET m.status = 'QUEUED', m.attempts = 0, m.nextAttemptAt = :now, m.lastError = NULL "
		+ "WHERE m.universityId = :universityId AND m.status = 'FAILED' AND m.kind = 'SCHEDULE' AND m.htmlBody IS NOT NULL")
	int retryAllFailed(UUID universityId, Instant now);

	@Modifying
	@Query("DELETE FROM EmailMessage m WHERE m.createdAt < :cutoff")
	int deleteOlderThan(Instant cutoff);
}
