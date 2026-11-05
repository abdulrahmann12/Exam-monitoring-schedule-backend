package schedule.example.schedule.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.UserSession;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.InvalidTokenException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.UserSessionRepository;
import schedule.example.schedule.security.AuthCookieProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Server-side sessions behind the refresh cookie.
 *
 * <p>Every refresh <em>rotates</em> the token: the old one stops working and a new one is issued.
 * If an already-rotated token is presented again (someone copied it), the whole session is ended —
 * the thief and the real user are both signed out, and the real user simply signs in again.
 *
 * <p>Exception: within {@link #ROTATION_GRACE} after a rotation the old token is answered with a
 * plain 401 instead. That covers two browser tabs refreshing at the same moment (they share the
 * cookie, so the losing tab just retries with the new one).
 */
@Service
@RequiredArgsConstructor
public class SessionService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SessionService.class);
	static final Duration ROTATION_GRACE = Duration.ofSeconds(30);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserSessionRepository sessions;
	private final AdminUserRepository users;
	private final AuthCookieProperties properties;

	public record Started(UserSession session, String refreshToken) {
	}

	public record Refreshed(UserSession session, AdminUser user, String refreshToken) {
	}

	/** Opens a session for a user who just proved their identity. */
	@Transactional
	public Started start(AdminUser user, HttpServletRequest request) {
		Instant now = Instant.now();
		enforceSessionLimit(user, now);
		String token = newToken();
		UserSession session = new UserSession();
		session.setUserId(user.getId());
		session.setRefreshTokenHash(hash(token));
		session.setTokenVersion(user.getTokenVersion());
		session.setCreatedAt(now);
		session.setLastUsedAt(now);
		session.setExpiresAt(now.plus(properties.sessionDays(), ChronoUnit.DAYS));
		if (request != null) {
			session.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
			session.setIpAddress(truncate(request.getRemoteAddr(), 64));
		}
		return new Started(sessions.save(session), token);
	}

	/** Exchanges a refresh token for a new one. Throws {@link InvalidTokenException} (401) when it is not usable. */
	@Transactional(noRollbackFor = InvalidTokenException.class)
	public Refreshed refresh(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidTokenException(Messages.AUTH_SESSION_ENDED);
		}
		Instant now = Instant.now();
		String hash = hash(refreshToken);
		UserSession session = sessions.lockByRefreshTokenHash(hash).orElse(null);
		if (session == null) {
			handleUnknownToken(hash, now);
			throw new InvalidTokenException(Messages.AUTH_SESSION_ENDED);
		}
		if (!session.isActive(now)) {
			throw new InvalidTokenException(Messages.AUTH_SESSION_ENDED);
		}
		AdminUser user = users.findById(session.getUserId()).orElse(null);
		if (user == null || !user.isEnabled() || user.getTokenVersion() != session.getTokenVersion()) {
			// Account gone/disabled, or "sign out everywhere" / password change since this session began.
			session.setRevokedAt(now);
			throw new InvalidTokenException(Messages.AUTH_SESSION_ENDED);
		}
		String next = newToken();
		session.setPreviousTokenHash(hash);
		session.setRefreshTokenHash(hash(next));
		session.setRotatedAt(now);
		session.setLastUsedAt(now);
		return new Refreshed(sessions.save(session), user, next);
	}

	private void handleUnknownToken(String hash, Instant now) {
		sessions.findFirstByPreviousTokenHash(hash).ifPresent(session -> {
			if (!session.isActive(now)) {
				return;
			}
			boolean justRotated = session.getRotatedAt() != null && session.getRotatedAt().isAfter(now.minus(ROTATION_GRACE));
			if (justRotated) {
				throw new InvalidTokenException(Messages.AUTH_SESSION_RENEWED_ELSEWHERE);
			}
			session.setRevokedAt(now);
			sessions.save(session);
			LOGGER.warn("[AUTH] Reused refresh token for session {} (user {}) — possible theft; session ended.",
				session.getId(), session.getUserId());
		});
	}

	/**
	 * Used for every API call: the access token's session must still be open and belong to the user.
	 * One primary-key lookup.
	 */
	@Transactional(readOnly = true)
	public boolean isActive(UUID sessionId, UUID userId) {
		if (sessionId == null) {
			return false;
		}
		Instant now = Instant.now();
		return sessions.findById(sessionId)
			.filter(session -> session.getUserId().equals(userId) && session.isActive(now))
			.isPresent();
	}

	/** Logout on this device: ends the session the refresh token belongs to (current or just-rotated). */
	@Transactional
	public void revokeByRefreshToken(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return;
		}
		String hash = hash(refreshToken);
		Instant now = Instant.now();
		sessions.findByRefreshTokenHash(hash)
			.or(() -> sessions.findFirstByPreviousTokenHash(hash))
			.filter(session -> session.getRevokedAt() == null)
			.ifPresent(session -> {
				session.setRevokedAt(now);
				sessions.save(session);
			});
	}

	@Transactional
	public void revoke(UUID sessionId) {
		sessions.findById(sessionId).filter(s -> s.getRevokedAt() == null).ifPresent(session -> {
			session.setRevokedAt(Instant.now());
			sessions.save(session);
		});
	}

	@Transactional
	public int revokeAllForUser(UUID userId) {
		return sessions.revokeAllForUser(userId, Instant.now());
	}

	/** Password change: other devices are signed out; this one continues with the new token version. */
	@Transactional
	public void keepOnly(UUID userId, UUID keepSessionId, int newTokenVersion) {
		if (keepSessionId == null) {
			// "id <> NULL" matches nothing in SQL — never let that silently keep every session open.
			sessions.revokeAllForUser(userId, Instant.now());
			return;
		}
		sessions.revokeAllForUserExcept(userId, keepSessionId, Instant.now());
		sessions.findById(keepSessionId).ifPresent(session -> {
			session.setTokenVersion(newTokenVersion);
			sessions.save(session);
		});
	}

	@Transactional
	public void deleteAllForUser(UUID userId) {
		sessions.deleteAllForUser(userId);
	}

	/** Nightly housekeeping: ended sessions are kept one day (for investigation), then deleted. */
	@Scheduled(cron = "0 17 3 * * *")
	@Transactional
	public void deleteEndedSessions() {
		int deleted = sessions.deleteEndedBefore(Instant.now().minus(1, ChronoUnit.DAYS));
		if (deleted > 0) {
			LOGGER.info("[AUTH] Removed {} ended session(s).", deleted);
		}
	}

	private void enforceSessionLimit(AdminUser user, Instant now) {
		int max = properties.maxSessionsPerUser();
		// The shared demo account has one session per visitor; capping it would kick visitors out.
		if (max <= 0 || user.getRole() == AdminRole.DEMO_ADMIN) {
			return;
		}
		List<UserSession> active = sessions.findActiveByUser(user.getId(), now);
		for (int i = 0; i <= active.size() - max; i++) {
			active.get(i).setRevokedAt(now); // oldest first
			sessions.save(active.get(i));
		}
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
