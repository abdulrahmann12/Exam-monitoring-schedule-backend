package schedule.example.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.auth.AuthResponse;
import schedule.example.schedule.dto.auth.ChangePasswordRequest;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.JwtTokenProvider;
import schedule.example.schedule.security.PasswordPolicy;

@Service
@Validated
@RequiredArgsConstructor
public class AuthService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final AdminUserRepository adminUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final LoginAttemptService loginAttemptService;

	public AuthResponse login(@Valid LoginRequest request) {
		// A locked account is refused before the password is even checked.
		var account = adminUserRepository.findByEmailIgnoreCase(request.email());
		account.ifPresent(loginAttemptService::rejectIfLocked);
		try {
			Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password())
			);
			AdminUser admin = requireAdmin(authentication.getName());
			loginAttemptService.recordSuccess(admin.getId());
			return issueToken(admin);
		} catch (AuthenticationException ex) {
			if (ex instanceof DisabledException disabled && account.isPresent()) {
				rejectDisabled(account.get(), request.password(), disabled);
			}
			if (account.isPresent()) {
				java.time.Instant lockedUntil = loginAttemptService.recordFailure(account.get().getId());
				if (lockedUntil != null) {
					throw loginAttemptService.lockedException(lockedUntil); // tell the user right away
				}
			}
			throw new BadCredentialsException(Messages.AUTH_INVALID_CREDENTIALS, ex);
		}
	}

	/**
	 * Spring checks "disabled" before the password, which would tell anyone that the email exists.
	 * So the "disabled" message is only shown to someone who also typed the right password;
	 * everyone else gets the usual generic answer (and the attempt counts as a failure).
	 */
	private void rejectDisabled(AdminUser account, String rawPassword, DisabledException ex) {
		boolean passwordCorrect = rawPassword != null
			&& !PasswordPolicy.exceedsMaximum(rawPassword)
			&& passwordEncoder.matches(rawPassword, account.getPasswordHash());
		if (passwordCorrect) {
			throw new DisabledException(Messages.AUTH_ACCOUNT_DISABLED, ex);
		}
	}

	/**
	 * Server-side logout: bumps the account's token version so every token issued so far — on
	 * this device and any other — stops working. The shared demo account is exempt (one visitor
	 * logging out must not kick out all the others).
	 */
	@Transactional
	public void logout(String email) {
		AdminUser admin = requireAdmin(email);
		if (admin.getRole() == AdminRole.DEMO_ADMIN) {
			return;
		}
		admin.setTokenVersion(admin.getTokenVersion() + 1);
		adminUserRepository.save(admin);
		LOGGER.info("[AUTH] Logout — all sessions revoked for '{}'.", admin.getEmail());
	}

	/**
	 * Changes the password, revokes all existing sessions, and returns a fresh token so the
	 * caller stays signed in on the current device.
	 */
	@Transactional
	public AuthResponse changePassword(String email, @Valid ChangePasswordRequest request) {
		AdminUser admin = requireAdmin(email);
		if (!passwordEncoder.matches(request.currentPassword(), admin.getPasswordHash())) {
			throw new ValidationException(Messages.AUTH_CURRENT_PASSWORD_WRONG);
		}
		if (request.currentPassword().equals(request.newPassword())) {
			throw new ValidationException(Messages.AUTH_PASSWORD_UNCHANGED);
		}
		PasswordPolicy.validate(request.newPassword());
		admin.setPasswordHash(passwordEncoder.encode(request.newPassword()));
		admin.setMustChangePassword(false);
		admin.setTokenVersion(admin.getTokenVersion() + 1);
		adminUserRepository.save(admin);
		LOGGER.info("[AUTH] Password changed — all other sessions revoked for '{}'.", admin.getEmail());
		return issueToken(admin);
	}

	private AuthResponse issueToken(AdminUser admin) {
		JwtTokenProvider.TokenDetails token = jwtTokenProvider.generateToken(admin.getEmail(), admin.getTokenVersion());
		return new AuthResponse(token.token(), "Bearer", token.expiresAt(), admin.getEmail());
	}

	private AdminUser requireAdmin(String email) {
		return adminUserRepository.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new BadCredentialsException(Messages.AUTH_INVALID_CREDENTIALS));
	}
}
