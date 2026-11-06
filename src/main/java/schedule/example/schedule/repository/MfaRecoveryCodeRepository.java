package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import schedule.example.schedule.entity.MfaRecoveryCode;

import java.util.Optional;
import java.util.UUID;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

	Optional<MfaRecoveryCode> findFirstByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

	long countByUserIdAndUsedAtIsNull(UUID userId);

	@Modifying
	@Query("DELETE FROM MfaRecoveryCode c WHERE c.userId = :userId")
	int deleteAllForUser(UUID userId);
}
