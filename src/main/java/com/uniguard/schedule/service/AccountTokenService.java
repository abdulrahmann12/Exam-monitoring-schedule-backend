package com.uniguard.schedule.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uniguard.schedule.config.AccountLinkProperties;
import com.uniguard.schedule.audit.AuditCategory;
import com.uniguard.schedule.audit.AuditEntry;
import com.uniguard.schedule.audit.AuditService;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.entity.AccountToken;
import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.enums.AccountTokenPurpose;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.exception.DuplicateResourceException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.repository.AccountTokenRepository;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.security.PasswordPolicy;
import com.uniguard.schedule.security.SecureTokens;

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
	/** How long a link to confirm a new email works. */
	static final int EMAIL_CHANGE_HOURS = 24;

	private final AccountTokenRepository tokens;
	private final AdminUserRepository users;
	private final AccountLinkProperties properties;
	private final PasswordEncoder passwordEncoder;
	private final SessionService sessionService;
	private final AuditService auditService;

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
		requirePasswordLink(token);
		AdminUser user = usableOwner(token);
		return new LinkInfo(token.getPurpose(), user.getEmail(), token.getExpiresAt());
	}

	@Transactional
	public void setPassword(String secret, String newPassword) {
		Instant now = Instant.now();
		AccountToken token = findUsable(secret, now, true);
		requirePasswordLink(token);
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
		boolean invite = token.getPurpose() == AccountTokenPurpose.INVITE;
		auditService.recordAfterCommit(new AuditEntry(invite ? "INVITATION_ACCEPTED" : "PASSWORD_RESET_COMPLETED", AuditCategory.AUTH,
			"USER", user.getId().toString(),
			invite ? "Accepted the invitation and chose a password" : "Set a new password with a reset link (all devices signed out)",
			AuditEntry.Outcome.SUCCESS, user.getEmail()));
	}

	/** One-time link that confirms {@code newEmail} as the new sign-in email (sent to that address). */
	@Transactional
	public Issued issueEmailChange(AdminUser user, String newEmail) {
		Instant now = Instant.now();
		tokens.retireUnused(user.getId(), AccountTokenPurpose.EMAIL_CHANGE, now);
		String secret = SecureTokens.newToken();
		AccountToken token = new AccountToken();
		token.setUserId(user.getId());
		token.setPurpose(AccountTokenPurpose.EMAIL_CHANGE);
		token.setTokenHash(SecureTokens.sha256(secret));
		token.setNewEmail(newEmail);
		token.setCreatedAt(now);
		token.setExpiresAt(now.plus(EMAIL_CHANGE_HOURS, ChronoUnit.HOURS));
		tokens.save(token);
		return new Issued(properties.emailChangeLink(secret), token.getExpiresAt());
	}

	/**
	 * The new address opened its link: it becomes the sign-in email. Every device is signed out
	 * (the old email is in their tokens) and other open links stop working.
	 *
	 * @return the new email
	 */
	@Transactional
	public String confirmEmailChange(String secret) {
		Instant now = Instant.now();
		AccountToken token = findUsable(secret, now, true);
		if (token.getPurpose() != AccountTokenPurpose.EMAIL_CHANGE || token.getNewEmail() == null) {
			throw invalid();
		}
		AdminUser user = usableOwner(token);
		String newEmail = token.getNewEmail();
		users.findByEmailIgnoreCase(newEmail)
			.filter(other -> !other.getId().equals(user.getId()))
			.ifPresent(other -> { throw new DuplicateResourceException(Messages.USER_EMAIL_EXISTS); });
		String oldEmail = user.getEmail();
		user.setEmail(newEmail);
		user.setTokenVersion(user.getTokenVersion() + 1);
		users.save(user);
		token.setUsedAt(now);
		tokens.save(token);
		tokens.retireAllUnused(user.getId(), now);
		sessionService.revokeAllForUser(user.getId());
		LOGGER.info("[AUTH] Sign-in email changed from '{}' to '{}'; all sessions signed out.", oldEmail, newEmail);
		auditService.recordAfterCommit(new AuditEntry("EMAIL_CHANGED", AuditCategory.SECURITY, "USER", user.getId().toString(),
			"Changed sign-in email from " + oldEmail + " to " + newEmail + " (all devices signed out)",
			AuditEntry.Outcome.SUCCESS, newEmail));
		return newEmail;
	}

	/** The password changed: a pending email change is cancelled (it may have been started by someone else). */
	@Transactional
	public void cancelEmailChange(UUID userId) {
		tokens.retireUnused(userId, AccountTokenPurpose.EMAIL_CHANGE, Instant.now());
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

	/** Email-change links only confirm an address; they can never set a password. */
	private static void requirePasswordLink(AccountToken token) {
		if (token.getPurpose() == AccountTokenPurpose.EMAIL_CHANGE) {
			throw invalid();
		}
	}

	private static ValidationException invalid() {
		return new ValidationException(Messages.AUTH_LINK_INVALID);
	}
}
