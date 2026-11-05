package schedule.example.schedule.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.repository.AdminUserRepository;

/**
 * Loads user accounts for authentication.
 *
 * <p>Deliberately <strong>not cached</strong>: every request re-reads the account so that a
 * logout, password change, role change, disabled or deleted account takes effect immediately
 * (see {@link #loadForToken}). It is a single indexed lookup per request.
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

	private final AdminUserRepository adminUserRepository;

	public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
		this.adminUserRepository = adminUserRepository;
	}

	/**
	 * Loads user details by email (case-insensitive). Used by the password login.
	 * A disabled account is returned as disabled; Spring Security then refuses it.
	 */
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		return adminUserRepository.findByEmailIgnoreCase(username)
			.map(AdminUserDetailsService::toPrincipal)
			.orElseThrow(() -> new UsernameNotFoundException("No account found for email: " + username));
	}

	private static AppUserPrincipal toPrincipal(AdminUser user) {
		// A row without a password hash was never fully initialised — treat it as "not found"
		// so Spring Security answers with a clean 401 instead of a confusing encoder warning.
		if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
			throw new UsernameNotFoundException("Account '" + user.getEmail() + "' has no password set.");
		}
		return new AppUserPrincipal(user);
	}

	/**
	 * Loads the user for an incoming JWT. Rejects the token when the account was deleted or
	 * disabled, or when its version no longer matches (logged out / password changed since).
	 */
	public AppUserPrincipal loadForToken(String email, Integer tokenVersion) {
		AdminUser user = adminUserRepository.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new UsernameNotFoundException("No account found for email: " + email));
		if (tokenVersion == null || tokenVersion != user.getTokenVersion()) {
			throw new UsernameNotFoundException("Token has been revoked");
		}
		if (!user.isEnabled()) {
			throw new UsernameNotFoundException("Account is disabled");
		}
		return toPrincipal(user);
	}
}
