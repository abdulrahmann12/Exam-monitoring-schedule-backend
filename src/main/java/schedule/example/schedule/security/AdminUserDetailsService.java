package schedule.example.schedule.security;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.CacheConfig;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.repository.AdminUserRepository;

/**
 * Loads admin user details for authentication.
 *
 * <p>Deliberately <strong>not cached</strong>: every request re-reads the account so that a
 * logout, password change or deleted account takes effect immediately (see {@link #loadForToken}).
 * It is a single indexed primary-key-sized lookup per request.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

	private final AdminUserRepository adminUserRepository;

	public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
		this.adminUserRepository = adminUserRepository;
	}

	/**
	 * Loads user details by email (case-insensitive). Used by the password login.
	 *
	 * @param username the admin email address (used as JWT subject)
	 */
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		return adminUserRepository.findByEmailIgnoreCase(username)
			.map(AdminUserDetailsService::toUserDetails)
			.orElseThrow(() -> new UsernameNotFoundException(
				"No admin account found for email: " + username));
	}

	private static UserDetails toUserDetails(AdminUser admin) {
		// A row without a password hash was never fully initialised — treat it as "not found"
		// so Spring Security answers with a clean 401 instead of a confusing encoder warning.
		if (admin.getPasswordHash() == null || admin.getPasswordHash().isBlank()) {
			throw new UsernameNotFoundException("Admin account '" + admin.getEmail() + "' has no password set.");
		}
		return User.builder()
			.username(admin.getEmail())
			.password(admin.getPasswordHash())
			.roles(admin.getRole() != null ? admin.getRole().name() : "ADMIN")
			.build();
	}

	/**
	 * Loads the user for an incoming JWT and rejects the token when its version no longer matches
	 * the account (user logged out or changed password since it was issued).
	 */
	public UserDetails loadForToken(String email, Integer tokenVersion) {
		AdminUser admin = adminUserRepository.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new UsernameNotFoundException("No admin account found for email: " + email));
		if (tokenVersion == null || tokenVersion != admin.getTokenVersion()) {
			throw new UsernameNotFoundException("Token has been revoked");
		}
		return toUserDetails(admin);
	}

	/**
	 * Evicts the cached user details for the given email.
	 * Call this whenever an admin's password or account status changes.
	 */
	public void evictUserCache(String email) {
		// Spring AOP handles the cache eviction — no body needed.
	}

	/**
	 * Evicts ALL cached user details (entire cache).
	 * Call this when orphaned/ghost rows are removed so stale null-password entries
	 * cannot survive in cache and cause "Empty encoded password" on the next login attempt.
	 */
	public void evictAllUserCache() {
		// Spring AOP handles the cache eviction — no body needed.
	}
}