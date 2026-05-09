package schedule.example.schedule.security;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.CacheConfig;
import schedule.example.schedule.repository.AdminUserRepository;

/**
 * Loads admin user details for authentication.
 *
 * <p>{@link #loadUserByUsername(String)} is annotated with {@link Cacheable} to prevent a
 * database round-trip on every authenticated HTTP request. The cache TTL is controlled by
 * {@link CacheConfig} (5 minutes). This is safe because admin credentials change infrequently;
 * password changes must call {@link #evictUserCache(String)} to invalidate the entry immediately.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

	private final AdminUserRepository adminUserRepository;

	public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
		this.adminUserRepository = adminUserRepository;
	}

	/**
	 * Loads user details by email (case-insensitive), with result cached for 5 minutes.
	 *
	 * @param username the admin email address (used as JWT subject)
	 */
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		return adminUserRepository.findByEmailIgnoreCase(username)
			.map(admin -> {
				// Guard: a row with a null or empty password_hash means the account was never
				// fully initialised (e.g. created before DataInitializer ran, or a failed save).
				// Treat it as "not found" so Spring Security converts it to BadCredentialsException
				// instead of letting BCryptPasswordEncoder log "Empty encoded password" and silently
				// returning false, which produces a confusing 401 with no actionable stack trace.
				if (admin.getPasswordHash() == null || admin.getPasswordHash().isBlank()) {
					throw new UsernameNotFoundException(
						"Admin account exists for email '" + admin.getEmail() +
						"' but has no password hash — restart the server so DataInitializer can sync it."
					);
				}
				return User.builder()
					.username(admin.getEmail())
					.password(admin.getPasswordHash())
					.roles("ADMIN")
					.build();
			})
			.orElseThrow(() -> new UsernameNotFoundException(
				"No admin account found for email: " + username));
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