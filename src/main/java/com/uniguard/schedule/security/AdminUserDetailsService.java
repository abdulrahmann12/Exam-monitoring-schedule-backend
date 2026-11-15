package com.uniguard.schedule.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.University;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.repository.UniversityRepository;

import java.util.List;
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
	private final UniversityRepository universityRepository;

	public AdminUserDetailsService(AdminUserRepository adminUserRepository, UniversityRepository universityRepository) {
		this.adminUserRepository = adminUserRepository;
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
			return user.getRole() == com.uniguard.schedule.entity.enums.AdminRole.SUPER_ADMIN;
		}
		return user.getRole() != com.uniguard.schedule.entity.enums.AdminRole.SUPER_ADMIN
			&& universityRepository.findById(user.getUniversityId()).map(University::isActive).orElse(false);
	}

	/** Same rule, when the university's state is already known (loaded together with the user). */
	private static boolean universityActive(AdminUser user, boolean universityIsActive) {
		if (user.getUniversityId() == null) {
			return user.getRole() == com.uniguard.schedule.entity.enums.AdminRole.SUPER_ADMIN;
		}
		return user.getRole() != com.uniguard.schedule.entity.enums.AdminRole.SUPER_ADMIN && universityIsActive;
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
		// One round trip for the user, the session and the university (this runs on every API request).
		List<Object[]> rows = sessionId == null ? List.of() : adminUserRepository.findForRequest(email, sessionId, java.time.Instant.now());
		if (rows.isEmpty()) {
			throw new UsernameNotFoundException(sessionId == null ? "Session has ended" : "No account found for email: " + email);
		}
		Object[] row = rows.get(0);
		AdminUser user = (AdminUser) row[0];
		boolean sessionOpen = ((Number) row[1]).longValue() > 0;
		Boolean universityActive = (Boolean) row[2];
		if (tokenVersion == null || tokenVersion != user.getTokenVersion()) {
			throw new UsernameNotFoundException("Token has been revoked");
		}
		if (!user.isEnabled()) {
			throw new UsernameNotFoundException("Account is disabled");
		}
		if (!sessionOpen) {
			throw new UsernameNotFoundException("Session has ended");
		}
		if (!universityActive(user, Boolean.TRUE.equals(universityActive))) {
			throw new UsernameNotFoundException("University is not active");
		}
		if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
			throw new UsernameNotFoundException("Account has no password set.");
		}
		return new AppUserPrincipal(user, sessionId);
	}
}
