package com.uniguard.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.uniguard.schedule.entity.SsoState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SsoStateRepository extends JpaRepository<SsoState, UUID> {

	/** Locked so the same state cannot be redeemed twice at the same moment. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM SsoState s WHERE s.stateHash = :hash")
	Optional<SsoState> lockByStateHash(String hash);

	@Modifying
	@Query("DELETE FROM SsoState s WHERE s.expiresAt < :now")
	int deleteExpired(Instant now);
}
