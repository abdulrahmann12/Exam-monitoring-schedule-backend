package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import schedule.example.schedule.entity.AdminUser;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

	Optional<AdminUser> findByEmailIgnoreCase(String email);

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