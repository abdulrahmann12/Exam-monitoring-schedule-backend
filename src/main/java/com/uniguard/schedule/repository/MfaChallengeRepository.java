package com.uniguard.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.uniguard.schedule.entity.MfaChallenge;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, UUID> {

	/** Locked: two code attempts for the same challenge at the same moment are handled one after the other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM MfaChallenge c WHERE c.tokenHash = :hash")
	Optional<MfaChallenge> lockByTokenHash(String hash);

	@Modifying
	@Query("DELETE FROM MfaChallenge c WHERE c.userId = :userId")
	int deleteAllForUser(UUID userId);

	@Modifying
	@Query("DELETE FROM MfaChallenge c WHERE c.expiresAt < :now")
	int deleteExpired(Instant now);
}
