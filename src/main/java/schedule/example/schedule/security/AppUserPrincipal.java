package schedule.example.schedule.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in user as seen by the security layer. Re-loaded from the database on every request,
 * so role changes, disabling and forced password changes take effect immediately.
 */
public class AppUserPrincipal extends User {

	private final UUID id;
	private final String displayName;
	private final AdminRole role;
	private final boolean mustChangePassword;
	/** Session (device) of the current request; null during the password login itself. */
	private final UUID sessionId;
	/** The user's university; every data access in this request is limited to it. */
	private final UUID universityId;

	public AppUserPrincipal(AdminUser user) {
		this(user, null);
	}

	public AppUserPrincipal(AdminUser user, UUID sessionId) {
		super(
			user.getEmail(),
			user.getPasswordHash(),
			user.isEnabled(),
			true,
			true,
			true,
			List.of(new SimpleGrantedAuthority("ROLE_" + roleOf(user).name()))
		);
		this.id = user.getId();
		this.displayName = user.getDisplayName();
		this.role = roleOf(user);
		this.mustChangePassword = user.isMustChangePassword();
		this.sessionId = sessionId;
		this.universityId = user.getUniversityId();
	}

	public UUID getUniversityId() {
		return universityId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	private static AdminRole roleOf(AdminUser user) {
		// Fail closed: an account with no role gets the weakest one, never ADMIN.
		return user.getRole() != null ? user.getRole() : AdminRole.VIEWER;
	}

	public UUID getId() {
		return id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public AdminRole getRole() {
		return role;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	// User#equals/hashCode compare by username only — fine for a principal.
	@Override
	public boolean equals(Object other) {
		return super.equals(other);
	}

	@Override
	public int hashCode() {
		return super.hashCode();
	}
}
