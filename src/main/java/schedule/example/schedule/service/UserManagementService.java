package schedule.example.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.user.CreateUserRequest;
import schedule.example.schedule.dto.user.ResetPasswordRequest;
import schedule.example.schedule.dto.user.UpdateUserRequest;
import schedule.example.schedule.dto.user.UserResponse;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.exception.DuplicateResourceException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.PasswordPolicy;

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

	@Transactional(readOnly = true)
	public List<UserResponse> list() {
		return adminUserRepository.findByRoleNotOrderByEmailAsc(AdminRole.DEMO_ADMIN).stream()
			.map(this::toResponse)
			.toList();
	}

	@Transactional
	public UserResponse create(@Valid CreateUserRequest request, UUID actorId) {
		requireAssignable(request.role());
		PasswordPolicy.validate(request.temporaryPassword());
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		if (adminUserRepository.findByEmailIgnoreCase(email).isPresent()) {
			throw new DuplicateResourceException(Messages.USER_EMAIL_EXISTS);
		}
		AdminUser user = new AdminUser();
		user.setEmail(email);
		user.setDisplayName(cleanName(request.displayName()));
		user.setRole(request.role());
		user.setEnabled(true);
		user.setMustChangePassword(true);
		user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
		AdminUser saved = adminUserRepository.saveAndFlush(user);
		LOGGER.warn("[USERS] '{}' created user '{}' with role {}.", actorEmail(actorId), email, request.role());
		return toResponse(saved);
	}

	@Transactional
	public UserResponse update(UUID id, @Valid UpdateUserRequest request, UUID actorId) {
		// Lock first, then load the target, so the checks below see the current state.
		List<AdminUser> activeAdmins = adminUserRepository.lockActiveAdmins();
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
		return toResponse(saved);
	}

	@Transactional
	public UserResponse unlock(UUID id, UUID actorId) {
		AdminUser user = requireManageable(id);
		loginAttemptService.unlock(user);
		LOGGER.warn("[USERS] '{}' unlocked user '{}'.", actorEmail(actorId), user.getEmail());
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
		return toResponse(saved);
	}

	@Transactional
	public void delete(UUID id, UUID actorId) {
		List<AdminUser> activeAdmins = adminUserRepository.lockActiveAdmins();
		AdminUser user = requireManageable(id);
		if (user.getId().equals(actorId)) {
			throw new BusinessRuleViolationException(Messages.USER_CANNOT_CHANGE_SELF);
		}
		if (isActiveAdmin(user) && activeAdmins.size() <= 1) {
			throw new BusinessRuleViolationException(Messages.USER_LAST_ADMIN);
		}
		sessionService.deleteAllForUser(user.getId());
		adminUserRepository.delete(user);
		LOGGER.warn("[USERS] '{}' deleted user '{}'.", actorEmail(actorId), user.getEmail());
	}

	private AdminUser requireManageable(UUID id) {
		AdminUser user = adminUserRepository.findById(id)
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
			user.getLastLoginAt(),
			user.getCreatedAt()
		);
	}
}
