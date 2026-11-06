package com.uniguard.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.uniguard.schedule.entity.UserSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

	/**
	 * Locks the row: two requests refreshing with the same token at the same moment are handled
	 * one after the other, so only one of them rotates it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM UserSession s WHERE s.refreshTokenHash = :hash")
	Optional<UserSession> lockByRefreshTokenHash(String hash);

	Optional<UserSession> findFirstByPreviousTokenHash(String hash);

	Optional<UserSession> findByRefreshTokenHash(String hash);

	@Query("SELECT s FROM UserSession s WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.expiresAt > :now ORDER BY s.createdAt ASC")
	List<UserSession> findActiveByUser(UUID userId, Instant now);

	@Modifying
	@Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
	int revokeAllForUser(UUID userId, Instant now);

	@Modifying
	@Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.id <> :keepId")
	int revokeAllForUserExcept(UUID userId, UUID keepId, Instant now);

	@Modifying
	@Query("DELETE FROM UserSession s WHERE s.userId = :userId")
	int deleteAllForUser(UUID userId);

	/** Housekeeping: drops sessions that ended before the cut-off. */
	@Modifying
	@Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoff OR (s.revokedAt IS NOT NULL AND s.revokedAt < :cutoff)")
	int deleteEndedBefore(Instant cutoff);
}
