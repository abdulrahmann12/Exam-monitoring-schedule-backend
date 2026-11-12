package com.uniguard.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.enums.AdminRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

	Optional<AdminUser> findByEmailIgnoreCase(String email);

	/**
	 * Everything one API request must check, in ONE database round trip (it runs on every request):
	 * [0] the user, [1] how many open sessions with this id belong to them (0 or 1),
	 * [2] whether their university is active (null = no university / not found).
	 */
	@Query("SELECT a, "
		+ "(SELECT COUNT(s) FROM UserSession s WHERE s.id = :sessionId AND s.userId = a.id AND s.revokedAt IS NULL AND s.expiresAt > :now), "
		+ "(SELECT u.active FROM University u WHERE u.id = a.universityId) "
		+ "FROM AdminUser a WHERE UPPER(a.email) = UPPER(:email)")
	List<Object[]> findForRequest(String email, UUID sessionId, java.time.Instant now);

	boolean existsByRole(AdminRole role);

	/** Upgrade helper: accounts created before universities existed join the given university. */
	@Modifying
	// The platform owner never belongs to a university, so it is never adopted.
	@Query("UPDATE AdminUser a SET a.universityId = :universityId WHERE a.universityId IS NULL "
		+ "AND a.role <> com.uniguard.schedule.entity.enums.AdminRole.SUPER_ADMIN")
	int assignUniversityWhereMissing(UUID universityId);

	List<AdminUser> findByUniversityId(UUID universityId);

	/** Owner panel: per university, the number of accounts (without demo/owner) and the latest sign-in. */
	@Query("SELECT a.universityId, COUNT(a), MAX(a.lastLoginAt) FROM AdminUser a WHERE a.universityId IS NOT NULL "
		+ "AND a.role <> com.uniguard.schedule.entity.enums.AdminRole.DEMO_ADMIN "
		+ "AND a.role <> com.uniguard.schedule.entity.enums.AdminRole.SUPER_ADMIN GROUP BY a.universityId")
	List<Object[]> accountUsageByUniversity();

	List<AdminUser> findByUniversityIdAndRoleOrderByEmailAsc(UUID universityId, AdminRole role);

	/** One university's accounts except the given role (hides the demo account), sorted by email. */
	List<AdminUser> findByUniversityIdAndRoleNotOrderByEmailAsc(UUID universityId, AdminRole role);

	/**
	 * Locks all enabled administrator rows until the transaction ends. Two administrators demoting
	 * or disabling each other at the same moment are serialised, so the "at least one active
	 * administrator" rule cannot be broken by a race.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM AdminUser a WHERE a.universityId = :universityId AND a.role = com.uniguard.schedule.entity.enums.AdminRole.ADMIN AND a.enabled = true")
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