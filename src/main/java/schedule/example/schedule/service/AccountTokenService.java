package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.AccountLinkProperties;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.AccountToken;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AccountTokenPurpose;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AccountTokenRepository;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.PasswordPolicy;
import schedule.example.schedule.security.SecureTokens;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One-time links for invitations and password resets.
 *
 * <ul>
 *   <li>Only the SHA-256 hash of a link's secret is stored.</li>
 *   <li>A link works once, until it expires, and only the newest link of its kind works.</li>
 *   <li>Using a link sets the password, unlocks the account and signs out every existing session.</li>
 *   <li>Every failure gives the same answer ("invalid or expired"), so links cannot be probed.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccountTokenService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountTokenService.class);

	private final AccountTokenRepository tokens;
	private final AdminUserRepository users;
	private final AccountLinkProperties properties;
	private final PasswordEncoder passwordEncoder;
	private final SessionService sessionService;

	public record Issued(String link, Instant expiresAt) {
	}

	public record LinkInfo(AccountTokenPurpose purpose, String email, Instant expiresAt) {
	}

	@Transactional
	public Issued issue(AdminUser user, AccountTokenPurpose purpose) {
		Instant now = Instant.now();
		tokens.retireUnused(user.getId(), purpose, now);
		String secret = SecureTokens.newToken();
		AccountToken token = new AccountToken();
		token.setUserId(user.getId());
		token.setPurpose(purpose);
		token.setTokenHash(SecureTokens.sha256(secret));
		token.setCreatedAt(now);
		token.setExpiresAt(purpose == AccountTokenPurpose.INVITE
			? now.plus(properties.inviteHours(), ChronoUnit.HOURS)
			: now.plus(properties.resetMinutes(), ChronoUnit.MINUTES));
		tokens.save(token);
		return new Issued(properties.passwordLink(secret), token.getExpiresAt());
	}

	/** Lets the page greet the user ("Set the password for x") before they type anything. */
	@Transactional(readOnly = true)
	public LinkInfo check(String secret) {
		Instant now = Instant.now();
		AccountToken token = findUsable(secret, now, false);
		AdminUser user = usableOwner(token);
		return new LinkInfo(token.getPurpose(), user.getEmail(), token.getExpiresAt());
	}

	@Transactional
	public void setPassword(String secret, String newPassword) {
		Instant now = Instant.now();
		AccountToken token = findUsable(secret, now, true);
		AdminUser user = usableOwner(token);
		PasswordPolicy.validate(newPassword); // before anything changes: a weak password keeps the link usable

		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.setMustChangePassword(false);
		user.setFailedLoginAttempts(0);
		user.setLockedUntil(null);
		user.setTokenVersion(user.getTokenVersion() + 1);
		users.save(user);
		token.setUsedAt(now);
		tokens.save(token);
		tokens.retireAllUnused(user.getId(), now);
		sessionService.revokeAllForUser(user.getId());
		LOGGER.info("[AUTH] Password set from a {} link for '{}'; all sessions signed out.", token.getPurpose(), user.getEmail());
	}

	@Transactional(readOnly = true)
	public boolean hasPendingInvite(UUID userId) {
		return tokens.existsUsable(userId, AccountTokenPurpose.INVITE, Instant.now());
	}

	@Transactional
	public void deleteAllForUser(UUID userId) {
		tokens.deleteAllForUser(userId);
	}

	/** Nightly: links that expired more than a week ago are deleted. */
	@Scheduled(cron = "0 27 3 * * *")
	@Transactional
	public void deleteExpired() {
		tokens.deleteExpiredBefore(Instant.now().minus(7, ChronoUnit.DAYS));
	}

	private AccountToken findUsable(String secret, Instant now, boolean lock) {
		if (secret == null || secret.isBlank() || secret.length() > 200) {
			throw invalid();
		}
		String hash = SecureTokens.sha256(secret.trim());
		AccountToken token = (lock ? tokens.lockByTokenHash(hash) : tokens.findByTokenHash(hash)).orElseThrow(AccountTokenService::invalid);
		if (!token.isUsable(now)) {
			throw invalid();
		}
		return token;
	}

	private AdminUser usableOwner(AccountToken token) {
		AdminUser user = users.findById(token.getUserId()).orElseThrow(AccountTokenService::invalid);
		if (!user.isEnabled() || user.getRole() == AdminRole.DEMO_ADMIN) {
			throw invalid();
		}
		return user;
	}

	private static ValidationException invalid() {
		return new ValidationException(Messages.AUTH_LINK_INVALID);
	}
}
