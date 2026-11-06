package schedule.example.schedule.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.University;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.service.SessionService;

import java.util.UUID;

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
	private final SessionService sessionService;
	private final UniversityRepository universityRepository;

	public AdminUserDetailsService(AdminUserRepository adminUserRepository, SessionService sessionService, UniversityRepository universityRepository) {
		this.adminUserRepository = adminUserRepository;
		this.sessionService = sessionService;
		this.universityRepository = universityRepository;
	}

	/**
	 * True when the user's university exists and is active. Only the platform owner may have no
	 * university; any other account without one is refused (fail closed).
	 */
	public boolean universityActive(AdminUser user) {
		return universityActive(user, universityRepository);
	}

	public static boolean universityActive(AdminUser user, UniversityRepository universityRepository) {
		if (user.getUniversityId() == null) {
			return user.getRole() == schedule.example.schedule.entity.enums.AdminRole.SUPER_ADMIN;
		}
		return user.getRole() != schedule.example.schedule.entity.enums.AdminRole.SUPER_ADMIN
			&& universityRepository.findById(user.getUniversityId()).map(University::isActive).orElse(false);
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
	 * disabled, when its version no longer matches (signed out everywhere / password changed),
	 * or when its session (device) was logged out.
	 */
	public AppUserPrincipal loadForToken(String email, Integer tokenVersion, UUID sessionId) {
		AdminUser user = adminUserRepository.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new UsernameNotFoundException("No account found for email: " + email));
		if (tokenVersion == null || tokenVersion != user.getTokenVersion()) {
			throw new UsernameNotFoundException("Token has been revoked");
		}
		if (!user.isEnabled()) {
			throw new UsernameNotFoundException("Account is disabled");
		}
		if (!sessionService.isActive(sessionId, user.getId())) {
			throw new UsernameNotFoundException("Session has ended");
		}
		if (!universityActive(user)) {
			throw new UsernameNotFoundException("University is not active");
		}
		if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
			throw new UsernameNotFoundException("Account has no password set.");
		}
		return new AppUserPrincipal(user, sessionId);
	}
}
