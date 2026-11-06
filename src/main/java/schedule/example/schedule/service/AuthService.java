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
import schedule.example.schedule.audit.AuditCategory;
import schedule.example.schedule.audit.AuditEntry;
import schedule.example.schedule.audit.AuditService;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.exception.AccountLockedException;
import schedule.example.schedule.dto.auth.ChangePasswordRequest;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.AdminUserDetailsService;
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
	private final AuditService auditService;
	private final AdminUserDetailsService adminUserDetailsService;
	private final TwoFactorService twoFactorService;

	/** Everything the controller needs to set the two cookies. */
	public record SignedIn(String email, String accessToken, Instant accessExpiresAt, String refreshToken, Instant sessionExpiresAt) {
	}

	public record AccessToken(String token, Instant expiresAt) {
	}

	/** Either signed in, or the password was right and the two-step code is still needed. */
	public record LoginResult(SignedIn signedIn, TwoFactorService.Challenge twoFactor, String email) {
	}

	public LoginResult login(@Valid LoginRequest request, HttpServletRequest httpRequest) {
		// A locked account is refused before the password is even checked.
		var account = adminUserRepository.findByEmailIgnoreCase(request.email());
		try {
			account.ifPresent(loginAttemptService::rejectIfLocked);
		} catch (AccountLockedException locked) {
			auditService.record(authEvent("LOGIN_REFUSED_LOCKED", AuditCategory.SECURITY, "Sign-in refused: account is locked")
				.withOutcome(AuditEntry.Outcome.DENIED).forAccount(request.email()));
			throw locked;
		}
		AdminUser admin;
		try {
			Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password())
			);
			admin = requireAdmin(authentication.getName());
		} catch (AuthenticationException ex) {
			throw failedSignIn(request, account, ex);
		}
		// The password was right. Checked only now (and outside the wrong-password handling), so it
		// reveals nothing to someone who does not know the password and never counts as a failure.
		if (!adminUserDetailsService.universityActive(admin)) {
			auditService.record(authEvent("LOGIN_REFUSED_UNIVERSITY_INACTIVE", AuditCategory.SECURITY, "Sign-in refused: university is not active")
				.withOutcome(AuditEntry.Outcome.DENIED).forAccount(admin.getEmail()));
			throw new DisabledException(Messages.AUTH_UNIVERSITY_INACTIVE);
		}
		if (admin.isTotpEnabled()) {
			// The failure counter is NOT reset here: otherwise knowing the password would allow
			// endless guessing of the 6-digit code (a few guesses, password again, repeat).
			TwoFactorService.Challenge challenge = twoFactorService.beginChallenge(admin);
			auditService.record(authEvent("LOGIN_PASSWORD_ACCEPTED", AuditCategory.AUTH, "Password accepted, waiting for the two-step code")
				.forAccount(admin.getEmail()));
			return new LoginResult(null, challenge, admin.getEmail());
		}
		loginAttemptService.recordSuccess(admin.getId());
		SignedIn signedIn = openSession(admin, httpRequest);
		auditService.record(authEvent("LOGIN_SUCCEEDED", AuditCategory.AUTH, "Signed in").forAccount(admin.getEmail()));
		return new LoginResult(signedIn, null, admin.getEmail());
	}

	/** Second step of sign-in: the code from the authenticator app (or a recovery code). */
	public SignedIn completeTwoFactor(String challengeToken, String code, HttpServletRequest httpRequest) {
		AdminUser user = twoFactorService.completeChallenge(challengeToken, code);
		SignedIn signedIn = openSession(user, httpRequest);
		auditService.record(authEvent("LOGIN_SUCCEEDED", AuditCategory.AUTH, "Signed in (two-step)").forAccount(user.getEmail()));
		return signedIn;
	}

	/** Wrong password, unknown account or disabled account: records it and builds the error to throw. */
	private RuntimeException failedSignIn(LoginRequest request, java.util.Optional<AdminUser> account, AuthenticationException ex) {
		if (ex instanceof DisabledException disabled && account.isPresent()) {
			rejectDisabled(account.get(), request.password(), disabled);
		}
		auditService.record(authEvent("LOGIN_FAILED", AuditCategory.AUTH,
				account.isPresent() ? "Failed sign-in (wrong password)" : "Failed sign-in (no account with this email)")
			.withOutcome(AuditEntry.Outcome.FAILURE).forAccount(request.email()));
		if (account.isPresent()) {
			Instant lockedUntil = loginAttemptService.recordFailure(account.get().getId());
			if (lockedUntil != null) {
				auditService.record(authEvent("ACCOUNT_LOCKED", AuditCategory.SECURITY,
						"Account locked after too many wrong passwords (until " + lockedUntil + ")")
					.withOutcome(AuditEntry.Outcome.DENIED).forAccount(account.get().getEmail()));
				return loginAttemptService.lockedException(lockedUntil); // tell the user right away
			}
		}
		return new BadCredentialsException(Messages.AUTH_INVALID_CREDENTIALS, ex);
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
			auditService.record(authEvent("LOGIN_REFUSED_DISABLED", AuditCategory.SECURITY, "Sign-in refused: account is disabled")
				.withOutcome(AuditEntry.Outcome.DENIED).forAccount(account.getEmail()));
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
		sessionService.revokeByRefreshToken(refreshToken)
			.flatMap(adminUserRepository::findById)
			.ifPresent(user -> auditService.record(authEvent("LOGGED_OUT", AuditCategory.AUTH, "Signed out").forAccount(user.getEmail())));
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
		auditService.recordAfterCommit(authEvent("SIGNED_OUT_EVERYWHERE", AuditCategory.AUTH, "Signed out on all devices"));
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
		auditService.recordAfterCommit(authEvent("PASSWORD_CHANGED", AuditCategory.AUTH, "Changed own password (other devices signed out)"));
		return accessToken(admin, currentSessionId);
	}

	private static AuditEntry authEvent(String action, AuditCategory category, String summary) {
		return new AuditEntry(action, category, "USER", null, summary, AuditEntry.Outcome.SUCCESS, null);
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
