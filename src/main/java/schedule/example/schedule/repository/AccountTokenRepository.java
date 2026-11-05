package schedule.example.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import schedule.example.schedule.entity.AccountToken;
import schedule.example.schedule.entity.enums.AccountTokenPurpose;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

	Optional<AccountToken> findByTokenHash(String tokenHash);

	/** Locks the row so the same link cannot be used twice by two requests racing each other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM AccountToken t WHERE t.tokenHash = :hash")
	Optional<AccountToken> lockByTokenHash(String hash);

	@Query("SELECT COUNT(t) > 0 FROM AccountToken t WHERE t.userId = :userId AND t.purpose = :purpose AND t.usedAt IS NULL AND t.expiresAt > :now")
	boolean existsUsable(UUID userId, AccountTokenPurpose purpose, Instant now);

	/** Only the newest link of a kind works: older unused ones are retired when a new one is issued. */
	@Modifying
	@Query("UPDATE AccountToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.purpose = :purpose AND t.usedAt IS NULL")
	int retireUnused(UUID userId, AccountTokenPurpose purpose, Instant now);

	@Modifying
	@Query("UPDATE AccountToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
	int retireAllUnused(UUID userId, Instant now);

	@Modifying
	@Query("DELETE FROM AccountToken t WHERE t.userId = :userId")
	int deleteAllForUser(UUID userId);

	@Modifying
	@Query("DELETE FROM AccountToken t WHERE t.expiresAt < :cutoff")
	int deleteExpiredBefore(Instant cutoff);
}
