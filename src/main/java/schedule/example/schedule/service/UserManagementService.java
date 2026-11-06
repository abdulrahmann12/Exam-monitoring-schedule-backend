package schedule.example.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import schedule.example.schedule.audit.AuditCategory;
import schedule.example.schedule.audit.AuditEntry;
import schedule.example.schedule.audit.AuditService;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.user.CreateUserRequest;
import schedule.example.schedule.dto.user.InviteResponse;
import schedule.example.schedule.dto.user.ResetPasswordRequest;
import schedule.example.schedule.dto.user.UpdateUserRequest;
import schedule.example.schedule.dto.user.UserResponse;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AccountTokenPurpose;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.exception.DuplicateResourceException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.PasswordPolicy;
import schedule.example.schedule.security.SecureTokens;
import schedule.example.schedule.tenant.TenantContext;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * User accounts, managed by administrators.
 *
 * <p>Safety rules enforced here (not only in the UI):
 * <ul>
 *   <li>An administrator cannot change their own role, disable or delete themselves.</li>
 *   <li>There is always at least one enabled administrator (checked under a row lock, so two
 *       administrators acting at the same moment cannot both pass the check).</li>
 *   <li>The demo account is hidden and cannot be changed; the demo role cannot be assigned.</li>
 *   <li>New accounts and reset passwords are temporary: the user must choose their own password
 *       before doing anything else.</li>
 * </ul>
 */
@Service
@Validated
@RequiredArgsConstructor
public class UserManagementService {

	private static final Logger LOGGER = LoggerFactory.getLogger(UserManagementService.class);

	private final AdminUserRepository adminUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final LoginAttemptService loginAttemptService;
	private final SessionService sessionService;
	private final AccountTokenService accountTokenService;
	private final AccountEmailService accountEmailService;
	private final AuditService auditService;

	@Transactional(readOnly = true)
	public List<UserResponse> list() {
		return adminUserRepository.findByUniversityIdAndRoleNotOrderByEmailAsc(TenantContext.require(), AdminRole.DEMO_ADMIN).stream()
			.map(this::toResponse)
			.toList();
	}

	/**
	 * Creates a user, either with an invitation link (the user chooses their own password) or with a
	 * temporary password the user must replace at first sign-in.
	 */
	@Transactional
	public InviteResponse create(@Valid CreateUserRequest request, UUID actorId) {
		requireAssignable(request.role());
		if (!request.invite()) {
			if (request.temporaryPassword() == null || request.temporaryPassword().isBlank()) {
				throw new ValidationException(Messages.USER_TEMP_PASSWORD_REQUIRED);
			}
			PasswordPolicy.validate(request.temporaryPassword());
		}
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		if (adminUserRepository.findByEmailIgnoreCase(email).isPresent()) {
			throw new DuplicateResourceException(Messages.USER_EMAIL_EXISTS);
		}
		AdminUser user = new AdminUser();
		user.setEmail(email);
		user.setDisplayName(cleanName(request.displayName()));
		user.setRole(request.role());
		user.setEnabled(true);
		user.setUniversityId(TenantContext.require()); // new users join the administrator's university
		if (request.invite()) {
			setUnusablePassword(user);
		} else {
			user.setMustChangePassword(true);
			user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
		}
		AdminUser saved = adminUserRepository.saveAndFlush(user);
		LOGGER.warn("[USERS] '{}' created user '{}' with role {} ({}).", actorEmail(actorId), email, request.role(),
			request.invite() ? "invitation" : "temporary password");
		audit("USER_CREATED", saved, "Added user " + email + " as " + roleName(request.role())
			+ (request.invite() ? " (invitation)" : " (temporary password)"));
		return request.invite() ? sendInvitation(saved, actorId) : new InviteResponse(toResponse(saved), null, null, false);
	}

	/** New invitation link for a user who has not signed in yet (e.g. the first email got lost or expired). */
	@Transactional
	public InviteResponse invite(UUID id, UUID actorId) {
		AdminUser user = requireManageable(id);
		if (user.getId().equals(actorId) || user.getLastLoginAt() != null) {
			throw new BusinessRuleViolationException(Messages.USER_INVITE_NOT_ALLOWED);
		}
		// Any temporary password stops working: the link is now the only way in.
		setUnusablePassword(user);
		user.setTokenVersion(user.getTokenVersion() + 1);
		adminUserRepository.save(user);
		sessionService.revokeAllForUser(user.getId());
		LOGGER.warn("[USERS] '{}' sent a new invitation to '{}'.", actorEmail(actorId), user.getEmail());
		audit("USER_INVITED", user, "Sent a new invitation to " + user.getEmail());
		return sendInvitation(user, actorId);
	}

	private InviteResponse sendInvitation(AdminUser user, UUID actorId) {
		AccountTokenService.Issued link = accountTokenService.issue(user, AccountTokenPurpose.INVITE);
		String invitedBy = adminUserRepository.findById(actorId)
			.map(actor -> actor.getDisplayName() != null ? actor.getDisplayName() : actor.getEmail())
			.orElse("An administrator");
		boolean emailSent = accountEmailService.canSend();
		// Only email the link once it is safely stored (after commit).
		afterCommit(() -> accountEmailService.sendInvite(user, link, invitedBy));
		return new InviteResponse(toResponse(user), link.link(), link.expiresAt(), emailSent);
	}

	/** Random password nobody knows: the account can only be entered through the emailed link. */
	private void setUnusablePassword(AdminUser user) {
		user.setPasswordHash(passwordEncoder.encode(SecureTokens.newToken()));
		user.setMustChangePassword(false);
	}

	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		} else {
			action.run();
		}
	}

	@Transactional
	public UserResponse update(UUID id, @Valid UpdateUserRequest request, UUID actorId) {
		// Lock first, then load the target, so the checks below see the current state.
		List<AdminUser> activeAdmins = adminUserRepository.lockActiveAdmins(TenantContext.require());
		AdminUser user = requireManageable(id);
		requireAssignable(request.role());
		boolean enabled = request.enabled();
		boolean roleChanged = request.role() != user.getRole();
		boolean disabling = user.isEnabled() && !enabled;

		if (user.getId().equals(actorId) && (roleChanged || disabling)) {
			throw new BusinessRuleViolationException(Messages.USER_CANNOT_CHANGE_SELF);
		}
		boolean losesActiveAdmin = isActiveAdmin(user) && (request.role() != AdminRole.ADMIN || !enabled);
		if (losesActiveAdmin && activeAdmins.size() <= 1) {
			throw new BusinessRuleViolationException(Messages.USER_LAST_ADMIN);
		}

		List<String> changes = new java.util.ArrayList<>();
		if (roleChanged) {
			changes.add("role " + roleName(user.getRole()) + " → " + roleName(request.role()));
		}
		if (user.isEnabled() != enabled) {
			changes.add(enabled ? "enabled" : "disabled");
		}
		if (!java.util.Objects.equals(user.getDisplayName(), cleanName(request.displayName()))) {
			changes.add("name '" + nameOrDash(user.getDisplayName()) + "' → '" + nameOrDash(cleanName(request.displayName())) + "'");
		}

		user.setDisplayName(cleanName(request.displayName()));
		user.setRole(request.role());
		user.setEnabled(enabled);
		if (disabling) {
			// Tokens are already rejected for disabled accounts; bumping the version kills them for good.
			user.setTokenVersion(user.getTokenVersion() + 1);
		}
		AdminUser saved = adminUserRepository.save(user);
		if (disabling) {
			sessionService.revokeAllForUser(user.getId());
		}
		LOGGER.warn("[USERS] '{}' updated user '{}': role={}, enabled={}.",
			actorEmail(actorId), user.getEmail(), user.getRole(), user.isEnabled());
		if (!changes.isEmpty()) {
			audit(disabling ? "USER_DISABLED" : "USER_UPDATED", user, "Changed user " + user.getEmail() + ": " + String.join(", ", changes));
		}
		return toResponse(saved);
	}

	@Transactional
	public UserResponse unlock(UUID id, UUID actorId) {
		AdminUser user = requireManageable(id);
		loginAttemptService.unlock(user);
		LOGGER.warn("[USERS] '{}' unlocked user '{}'.", actorEmail(actorId), user.getEmail());
		audit("USER_UNLOCKED", user, "Unlocked " + user.getEmail());
		return toResponse(user);
	}

	@Transactional
	public UserResponse resetPassword(UUID id, @Valid ResetPasswordRequest request, UUID actorId) {
		AdminUser user = requireManageable(id);
		if (user.getId().equals(actorId)) {
			throw new BusinessRuleViolationException(Messages.USER_RESET_OWN_PASSWORD);
		}
		PasswordPolicy.validate(request.temporaryPassword());
		user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
		user.setMustChangePassword(true);
		user.setTokenVersion(user.getTokenVersion() + 1); // signs the user out everywhere
		user.setFailedLoginAttempts(0);
		user.setLockedUntil(null);
		AdminUser saved = adminUserRepository.save(user);
		sessionService.revokeAllForUser(user.getId());
		LOGGER.warn("[USERS] '{}' reset the password of '{}'.", actorEmail(actorId), user.getEmail());
		audit("USER_PASSWORD_RESET", user, "Set a temporary password for " + user.getEmail() + " (signed out everywhere)");
		return toResponse(saved);
	}

	@Transactional
	public void delete(UUID id, UUID actorId) {
		List<AdminUser> activeAdmins = adminUserRepository.lockActiveAdmins(TenantContext.require());
		AdminUser user = requireManageable(id);
		if (user.getId().equals(actorId)) {
			throw new BusinessRuleViolationException(Messages.USER_CANNOT_CHANGE_SELF);
		}
		if (isActiveAdmin(user) && activeAdmins.size() <= 1) {
			throw new BusinessRuleViolationException(Messages.USER_LAST_ADMIN);
		}
		sessionService.deleteAllForUser(user.getId());
		accountTokenService.deleteAllForUser(user.getId());
		adminUserRepository.delete(user);
		LOGGER.warn("[USERS] '{}' deleted user '{}'.", actorEmail(actorId), user.getEmail());
		audit("USER_DELETED", user, "Deleted user " + user.getEmail() + " (" + roleName(user.getRole()) + ")");
	}

	/** Recorded only if the change is really saved. */
	private void audit(String action, AdminUser user, String summary) {
		auditService.recordAfterCommit(AuditEntry.success(action, AuditCategory.USERS, "USER", user.getId(), summary));
	}

	private static String roleName(AdminRole role) {
		return switch (role) {
			case ADMIN -> "Administrator";
			case SCHEDULER -> "Scheduler";
			case VIEWER -> "Viewer";
			case DEMO_ADMIN -> "Demo";
			case SUPER_ADMIN -> "Platform owner";
		};
	}

	private static String nameOrDash(String name) {
		return name == null ? "-" : name;
	}

	private AdminUser requireManageable(UUID id) {
		AdminUser user = adminUserRepository.findById(id)
			// Another university's account answers exactly like a missing one.
			.filter(found -> TenantContext.require().equals(found.getUniversityId()))
			.orElseThrow(() -> new NotFoundException(Messages.USER_NOT_FOUND));
		if (user.getRole() == AdminRole.DEMO_ADMIN) {
			throw new BusinessRuleViolationException(Messages.USER_DEMO_ACCOUNT_PROTECTED);
		}
		return user;
	}

	private static void requireAssignable(AdminRole role) {
		if (role == null || !role.isAssignable()) {
			throw new ValidationException(Messages.USER_ROLE_NOT_ASSIGNABLE);
		}
	}

	private static boolean isActiveAdmin(AdminUser user) {
		return user.getRole() == AdminRole.ADMIN && user.isEnabled();
	}

	private static String cleanName(String name) {
		return name == null || name.isBlank() ? null : name.trim();
	}

	private String actorEmail(UUID actorId) {
		return adminUserRepository.findById(actorId).map(AdminUser::getEmail).orElse("unknown");
	}

	private UserResponse toResponse(AdminUser user) {
		boolean locked = loginAttemptService.isLocked(user);
		return new UserResponse(
			user.getId(),
			user.getEmail(),
			user.getDisplayName(),
			user.getRole(),
			user.isEnabled(),
			locked,
			locked ? user.getLockedUntil() : null,
			user.isMustChangePassword(),
			accountTokenService.hasPendingInvite(user.getId()),
			user.getLastLoginAt(),
			user.getCreatedAt()
		);
	}
}
