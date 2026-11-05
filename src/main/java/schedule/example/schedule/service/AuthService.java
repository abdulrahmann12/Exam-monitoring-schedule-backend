package schedule.example.schedule.service;

import jakarta.servlet.http.HttpServletRequest;
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
import schedule.example.schedule.dto.auth.ChangePasswordRequest;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.JwtTokenProvider;
import schedule.example.schedule.security.PasswordPolicy;

import java.time.Instant;
import java.util.UUID;

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
	private final SessionService sessionService;

	/** Everything the controller needs to set the two cookies. */
	public record SignedIn(String email, String accessToken, Instant accessExpiresAt, String refreshToken, Instant sessionExpiresAt) {
	}

	public record AccessToken(String token, Instant expiresAt) {
	}

	public SignedIn login(@Valid LoginRequest request, HttpServletRequest httpRequest) {
		// A locked account is refused before the password is even checked.
		var account = adminUserRepository.findByEmailIgnoreCase(request.email());
		account.ifPresent(loginAttemptService::rejectIfLocked);
		try {
			Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password())
			);
			AdminUser admin = requireAdmin(authentication.getName());
			loginAttemptService.recordSuccess(admin.getId());
			return openSession(admin, httpRequest);
		} catch (AuthenticationException ex) {
			if (ex instanceof DisabledException disabled && account.isPresent()) {
				rejectDisabled(account.get(), request.password(), disabled);
			}
			if (account.isPresent()) {
				Instant lockedUntil = loginAttemptService.recordFailure(account.get().getId());
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

	/** New device session + first access token for a user whose identity is already proven. */
	public SignedIn openSession(AdminUser user, HttpServletRequest httpRequest) {
		SessionService.Started started = sessionService.start(user, httpRequest);
		AccessToken access = accessToken(user, started.session().getId());
		return new SignedIn(user.getEmail(), access.token(), access.expiresAt(), started.refreshToken(), started.session().getExpiresAt());
	}

	/** Silent renewal: rotates the refresh token and issues a fresh access token. */
	public SignedIn refresh(String refreshToken) {
		SessionService.Refreshed refreshed = sessionService.refresh(refreshToken);
		AccessToken access = accessToken(refreshed.user(), refreshed.session().getId());
		return new SignedIn(refreshed.user().getEmail(), access.token(), access.expiresAt(),
			refreshed.refreshToken(), refreshed.session().getExpiresAt());
	}

	/** Logout on this device only. Safe to call with a missing or already-ended token. */
	public void logout(String refreshToken) {
		sessionService.revokeByRefreshToken(refreshToken);
	}

	/**
	 * Signs the user out on every device: all sessions end and every access token already issued
	 * stops working (token version bump). The shared demo account is exempt.
	 */
	@Transactional
	public void logoutEverywhere(String email) {
		AdminUser admin = requireAdmin(email);
		if (admin.getRole() == AdminRole.DEMO_ADMIN) {
			return;
		}
		admin.setTokenVersion(admin.getTokenVersion() + 1);
		adminUserRepository.save(admin);
		sessionService.revokeAllForUser(admin.getId());
		LOGGER.info("[AUTH] Signed out everywhere: '{}'.", admin.getEmail());
	}

	/**
	 * Changes the password. Every other device is signed out; this one stays signed in and gets a
	 * new access token (the refresh cookie stays the same).
	 */
	@Transactional
	public AccessToken changePassword(String email, UUID currentSessionId, @Valid ChangePasswordRequest request) {
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
		sessionService.keepOnly(admin.getId(), currentSessionId, admin.getTokenVersion());
		LOGGER.info("[AUTH] Password changed — all other sessions revoked for '{}'.", admin.getEmail());
		return accessToken(admin, currentSessionId);
	}

	private AccessToken accessToken(AdminUser user, UUID sessionId) {
		JwtTokenProvider.TokenDetails token = jwtTokenProvider.generateToken(user.getEmail(), user.getTokenVersion(), sessionId);
		return new AccessToken(token.token(), token.expiresAt());
	}

	private AdminUser requireAdmin(String email) {
		return adminUserRepository.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new BadCredentialsException(Messages.AUTH_INVALID_CREDENTIALS));
	}
}
