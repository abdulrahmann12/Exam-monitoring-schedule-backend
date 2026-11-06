package schedule.example.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.audit.AuditCategory;
import schedule.example.schedule.audit.AuditEntry;
import schedule.example.schedule.audit.AuditService;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.auth.TwoFactorDtos.RecoveryCodes;
import schedule.example.schedule.dto.auth.TwoFactorDtos.SetupResponse;
import schedule.example.schedule.dto.auth.TwoFactorDtos.Status;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.MfaChallenge;
import schedule.example.schedule.entity.MfaRecoveryCode;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.AccountLockedException;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.MfaChallengeRepository;
import schedule.example.schedule.repository.MfaRecoveryCodeRepository;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.security.AdminUserDetailsService;
import schedule.example.schedule.security.PasswordPolicy;
import schedule.example.schedule.security.SecureTokens;
import schedule.example.schedule.security.mfa.SecretCipher;
import schedule.example.schedule.security.mfa.Totp;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Two-step sign-in with an authenticator app (Google Authenticator and compatible apps).
 *
 * <ul>
 *   <li>Switching on: password → QR code → first code from the app → 10 one-time recovery codes.</li>
 *   <li>Signing in: password right → a short-lived challenge (HttpOnly cookie) → code → session.
 *       Wrong codes count towards the normal account lock, and a challenge dies after 5 wrong codes,
 *       so the 6 digits cannot be guessed.</li>
 *   <li>A code can be used only once (the last used time step is remembered).</li>
 *   <li>Lost phone: a recovery code, or an administrator (or the platform owner) resets two-step sign-in.</li>
 * </ul>
 */
@Service
public class TwoFactorService {

	private static final Logger LOGGER = LoggerFactory.getLogger(TwoFactorService.class);
	static final int RECOVERY_CODE_COUNT = 10;
	static final int MAX_ATTEMPTS_PER_CHALLENGE = 5;
	private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"; // no 0/o, 1/l/i
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AdminUserRepository users;
	private final MfaRecoveryCodeRepository recoveryCodes;
	private final MfaChallengeRepository challenges;
	private final UniversityRepository universities;
	private final SecretCipher cipher;
	private final PasswordEncoder passwordEncoder;
	private final SessionService sessionService;
	private final LoginAttemptService loginAttemptService;
	private final AuditService auditService;
	private final String issuer;
	private final long challengeMinutes;

	public TwoFactorService(
		AdminUserRepository users,
		MfaRecoveryCodeRepository recoveryCodes,
		MfaChallengeRepository challenges,
		UniversityRepository universities,
		SecretCipher cipher,
		PasswordEncoder passwordEncoder,
		SessionService sessionService,
		LoginAttemptService loginAttemptService,
		AuditService auditService,
		@Value("${app.mfa.issuer:Uni-Guard Schedules}") String issuer,
		@Value("${app.mfa.challenge-minutes:5}") long challengeMinutes
	) {
		this.users = users;
		this.recoveryCodes = recoveryCodes;
		this.challenges = challenges;
		this.universities = universities;
		this.cipher = cipher;
		this.passwordEncoder = passwordEncoder;
		this.sessionService = sessionService;
		this.loginAttemptService = loginAttemptService;
		this.auditService = auditService;
		this.issuer = issuer;
		this.challengeMinutes = challengeMinutes;
	}

	public record Challenge(String token, Instant expiresAt) {
	}

	// ─────────────────────────── Own settings ───────────────────────────

	@Transactional(readOnly = true)
	public Status status(UUID userId) {
		AdminUser user = requireUser(userId);
		return new Status(cipher.isConfigured(), user.isTotpEnabled(), user.getTotpEnabledAt(),
			user.isTotpEnabled() ? recoveryCodes.countByUserIdAndUsedAtIsNull(userId) : 0);
	}

	/** Step 1 of switching on: a new secret (not active until confirmed with a code). */
	@Transactional
	public SetupResponse startSetup(UUID userId, String currentPassword) {
		AdminUser user = requireUser(userId);
		requireOwnAccountAllowed(user);
		if (!cipher.isConfigured()) {
			throw new BusinessRuleViolationException(Messages.MFA_NOT_AVAILABLE);
		}
		if (user.isTotpEnabled()) {
			throw new BusinessRuleViolationException(Messages.MFA_ALREADY_ON);
		}
		requirePassword(user, currentPassword);
		String secret = Totp.newSecret();
		user.setTotpPendingSecret(cipher.encrypt(secret));
		users.save(user);
		return new SetupResponse(group(secret), Totp.otpauthUri(issuer, user.getEmail(), secret));
	}

	/** Step 2: the first code proves the app is set up. Other devices are signed out. */
	@Transactional
	public RecoveryCodes enable(UUID userId, UUID currentSessionId, String code) {
		AdminUser user = requireUser(userId);
		requireOwnAccountAllowed(user);
		if (user.isTotpEnabled()) {
			throw new BusinessRuleViolationException(Messages.MFA_ALREADY_ON);
		}
		if (user.getTotpPendingSecret() == null) {
			throw new BusinessRuleViolationException(Messages.MFA_SETUP_FIRST);
		}
		String secret = cipher.decrypt(user.getTotpPendingSecret());
		long step = Totp.verify(secret, code, Instant.now(), null);
		if (step < 0) {
			throw new ValidationException(Messages.MFA_CODE_WRONG);
		}
		user.setTotpSecret(user.getTotpPendingSecret());
		user.setTotpPendingSecret(null);
		user.setTotpEnabled(true);
		user.setTotpEnabledAt(Instant.now());
		user.setTotpLastStep(step);
		users.save(user);
		sessionService.keepOnly(userId, currentSessionId, user.getTokenVersion());
		auditService.recordAfterCommit(entry("MFA_ENABLED", AuditCategory.AUTH, user, "Switched on two-step sign-in (other devices signed out)"));
		return new RecoveryCodes(newRecoveryCodes(userId));
	}

	/** Switching off needs the password and a code (or a recovery code). */
	@Transactional
	public void disable(UUID userId, String currentPassword, String code) {
		AdminUser user = requireUser(userId);
		requireOwnAccountAllowed(user);
		if (!user.isTotpEnabled()) {
			throw new BusinessRuleViolationException(Messages.MFA_NOT_ON);
		}
		requirePassword(user, currentPassword);
		if (!checkSecondFactor(user, code)) {
			throw new ValidationException(Messages.MFA_CODE_WRONG);
		}
		clear(user);
		users.save(user);
		auditService.recordAfterCommit(entry("MFA_DISABLED", AuditCategory.SECURITY, user, "Switched off two-step sign-in"));
	}

	/** New recovery codes; the old ones stop working. */
	@Transactional
	public RecoveryCodes regenerateRecoveryCodes(UUID userId, String currentPassword, String code) {
		AdminUser user = requireUser(userId);
		requireOwnAccountAllowed(user);
		if (!user.isTotpEnabled()) {
			throw new BusinessRuleViolationException(Messages.MFA_NOT_ON);
		}
		requirePassword(user, currentPassword);
		if (!checkSecondFactor(user, code)) {
			throw new ValidationException(Messages.MFA_CODE_WRONG);
		}
		List<String> codes = newRecoveryCodes(userId);
		auditService.recordAfterCommit(entry("MFA_RECOVERY_CODES_RENEWED", AuditCategory.AUTH, user, "Created new recovery codes (old ones stopped working)"));
		return new RecoveryCodes(codes);
	}

	// ─────────────────────────── Sign-in ───────────────────────────

	/** Called by the password sign-in for an account with two-step sign-in. Only the newest challenge works. */
	@Transactional
	public Challenge beginChallenge(AdminUser user) {
		challenges.deleteAllForUser(user.getId());
		String token = SecureTokens.newToken();
		Instant now = Instant.now();
		MfaChallenge challenge = new MfaChallenge();
		challenge.setUserId(user.getId());
		challenge.setTokenHash(SecureTokens.sha256(token));
		challenge.setCreatedAt(now);
		challenge.setExpiresAt(now.plus(challengeMinutes, ChronoUnit.MINUTES));
		challenges.save(challenge);
		return new Challenge(token, challenge.getExpiresAt());
	}

	/**
	 * Second step of sign-in. Returns the user when the code is right. Wrong codes are saved even
	 * though an exception is thrown (they count towards the account lock).
	 */
	@Transactional(noRollbackFor = {AuthenticationException.class, AccountLockedException.class})
	public AdminUser completeChallenge(String token, String code) {
		if (token == null || token.isBlank()) {
			throw new BadCredentialsException(Messages.MFA_CHALLENGE_INVALID);
		}
		MfaChallenge challenge = challenges.lockByTokenHash(SecureTokens.sha256(token))
			.orElseThrow(() -> new BadCredentialsException(Messages.MFA_CHALLENGE_INVALID));
		if (challenge.getExpiresAt().isBefore(Instant.now())) {
			challenges.delete(challenge);
			throw new BadCredentialsException(Messages.MFA_CHALLENGE_INVALID);
		}
		AdminUser user = users.findById(challenge.getUserId()).orElse(null);
		if (user == null || !user.isEnabled() || !user.isTotpEnabled()
			|| !AdminUserDetailsService.universityActive(user, universities)) {
			challenges.delete(challenge);
			throw new BadCredentialsException(Messages.MFA_CHALLENGE_INVALID);
		}
		loginAttemptService.rejectIfLocked(user);

		if (checkSecondFactor(user, code)) {
			challenges.delete(challenge);
			loginAttemptService.recordSuccess(user.getId());
			return user;
		}

		challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
		if (challenge.getFailedAttempts() >= MAX_ATTEMPTS_PER_CHALLENGE) {
			challenges.delete(challenge); // start again with the password
		} else {
			challenges.save(challenge);
		}
		auditService.record(entry("MFA_FAILED", AuditCategory.AUTH, user, "Wrong two-step code at sign-in")
			.withOutcome(AuditEntry.Outcome.FAILURE));
		Instant lockedUntil = loginAttemptService.recordFailure(user.getId());
		if (lockedUntil != null) {
			challenges.deleteAllForUser(user.getId());
			auditService.record(entry("ACCOUNT_LOCKED", AuditCategory.SECURITY, user,
				"Account locked after too many wrong codes (until " + lockedUntil + ")").withOutcome(AuditEntry.Outcome.DENIED));
			throw loginAttemptService.lockedException(lockedUntil);
		}
		throw new BadCredentialsException(Messages.MFA_CODE_WRONG);
	}

	// ─────────────────────────── Resets ───────────────────────────

	/** Removes two-step sign-in from an account (the caller saves the user and ends its sessions). */
	public void clear(AdminUser user) {
		user.setTotpSecret(null);
		user.setTotpPendingSecret(null);
		user.setTotpEnabled(false);
		user.setTotpEnabledAt(null);
		user.setTotpLastStep(null);
		if (user.getId() != null) {
			recoveryCodes.deleteAllForUser(user.getId());
			challenges.deleteAllForUser(user.getId());
		}
	}

	@Scheduled(cron = "0 17 * * * *")
	@Transactional
	public void removeExpiredChallenges() {
		challenges.deleteExpired(Instant.now());
	}

	// ─────────────────────────── Helpers ───────────────────────────

	/** A 6-digit app code (each used once), or an unused recovery code. */
	private boolean checkSecondFactor(AdminUser user, String code) {
		String trimmed = code == null ? "" : code.trim();
		if (trimmed.replaceAll("\\s", "").matches("\\d{" + Totp.DIGITS + "}")) {
			String secret;
			try {
				secret = cipher.decrypt(user.getTotpSecret());
			} catch (IllegalStateException ex) {
				LOGGER.error("[MFA] Cannot read the authenticator secret of '{}': {}. Recovery codes still work.", user.getEmail(), ex.getMessage());
				throw new IllegalStateException(Messages.MFA_SERVER_KEY_PROBLEM, ex);
			}
			long step = Totp.verify(secret, trimmed, Instant.now(), user.getTotpLastStep());
			if (step < 0) {
				return false;
			}
			user.setTotpLastStep(step);
			users.save(user);
			return true;
		}
		String normalized = normalizeRecoveryCode(trimmed);
		if (normalized.length() != 10) {
			return false;
		}
		return recoveryCodes.findFirstByUserIdAndCodeHashAndUsedAtIsNull(user.getId(), SecureTokens.sha256(normalized))
			.map(found -> {
				found.setUsedAt(Instant.now());
				recoveryCodes.save(found);
				auditService.recordAfterCommit(entry("MFA_RECOVERY_CODE_USED", AuditCategory.SECURITY, user,
					"Used a recovery code (" + (recoveryCodes.countByUserIdAndUsedAtIsNull(user.getId())) + " left)"));
				return true;
			})
			.orElse(false);
	}

	private List<String> newRecoveryCodes(UUID userId) {
		recoveryCodes.deleteAllForUser(userId);
		List<String> shown = new ArrayList<>();
		Instant now = Instant.now();
		for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
			StringBuilder raw = new StringBuilder();
			for (int c = 0; c < 10; c++) {
				raw.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
			}
			MfaRecoveryCode code = new MfaRecoveryCode();
			code.setUserId(userId);
			code.setCodeHash(SecureTokens.sha256(raw.toString()));
			code.setCreatedAt(now);
			recoveryCodes.save(code);
			shown.add(raw.substring(0, 5) + "-" + raw.substring(5));
		}
		return shown;
	}

	static String normalizeRecoveryCode(String code) {
		return code.toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "");
	}

	/** "JBSWY3DPEHPK3PXP" → "JBSW Y3DP EHPK 3PXP" (easier to type by hand). */
	private static String group(String secret) {
		return secret.replaceAll("(.{4})(?!$)", "$1 ");
	}

	private void requirePassword(AdminUser user, String currentPassword) {
		if (currentPassword == null || PasswordPolicy.exceedsMaximum(currentPassword)
			|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new ValidationException(Messages.AUTH_CURRENT_PASSWORD_WRONG);
		}
	}

	/** The shared demo account never gets two-step sign-in (anyone could lock everyone else out). */
	private static void requireOwnAccountAllowed(AdminUser user) {
		if (user.getRole() == AdminRole.DEMO_ADMIN) {
			throw new BusinessRuleViolationException(Messages.DEMO_OPERATION_NOT_ALLOWED);
		}
	}

	private AdminUser requireUser(UUID userId) {
		return users.findById(userId).orElseThrow(() -> new NotFoundException(Messages.USER_NOT_FOUND));
	}

	private static AuditEntry entry(String action, AuditCategory category, AdminUser user, String summary) {
		return AuditEntry.success(action, category, "USER", user.getId(), summary).forAccount(user.getEmail());
	}
}
