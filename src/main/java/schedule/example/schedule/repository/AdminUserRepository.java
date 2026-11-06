package schedule.example.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

	Optional<AdminUser> findByEmailIgnoreCase(String email);

	boolean existsByRole(AdminRole role);

	/** Upgrade helper: accounts created before universities existed join the given university. */
	@Modifying
	@Query("UPDATE AdminUser a SET a.universityId = :universityId WHERE a.universityId IS NULL")
	int assignUniversityWhereMissing(UUID universityId);

	/** One university's accounts except the given role (hides the demo account), sorted by email. */
	List<AdminUser> findByUniversityIdAndRoleNotOrderByEmailAsc(UUID universityId, AdminRole role);

	/**
	 * Locks all enabled administrator rows until the transaction ends. Two administrators demoting
	 * or disabling each other at the same moment are serialised, so the "at least one active
	 * administrator" rule cannot be broken by a race.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM AdminUser a WHERE a.universityId = :universityId AND a.role = schedule.example.schedule.entity.enums.AdminRole.ADMIN AND a.enabled = true")
	List<AdminUser> lockActiveAdmins(UUID universityId);

	/**
	 * Deletes any admin rows that were created without a password hash (e.g. rows inserted
	 * before DataInitializer existed, or rows left by a failed bootstrap save).
	 * Called once at startup by DataInitializer after the bootstrap account is saved, so the
	 * next loadUserByUsername call won't find a passwordless ghost row for the same email.
	 *
	 * @param excludeEmail the bootstrap email to keep (already synced with a real password)
	 */
	@Modifying
	@Query("DELETE FROM AdminUser a WHERE (a.passwordHash IS NULL OR TRIM(a.passwordHash) = '') AND LOWER(a.email) != LOWER(:excludeEmail)")
	int deleteOrphanedPasswordlessAccounts(String excludeEmail);
}