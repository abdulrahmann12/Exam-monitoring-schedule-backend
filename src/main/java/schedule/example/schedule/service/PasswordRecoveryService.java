package schedule.example.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AccountTokenPurpose;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.RateLimitExceededException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.SlidingWindowRateLimiter;

import java.time.Duration;
import java.util.Locale;

/**
 * "Forgot password". The answer never reveals whether an account exists: unknown, disabled and demo
 * accounts get exactly the same response, and no email.
 *
 * <p>Limits: per IP (stops one machine from spraying many addresses — answered with 429, which
 * reveals nothing about accounts) and per email address (stops someone from flooding a person's
 * inbox — silently ignored, so it reveals nothing either).
 */
@Service
public class PasswordRecoveryService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PasswordRecoveryService.class);

	private final AdminUserRepository users;
	private final AccountTokenService accountTokenService;
	private final AccountEmailService accountEmailService;
	private final SlidingWindowRateLimiter perIp = new SlidingWindowRateLimiter(5, Duration.ofMinutes(15));
	private final SlidingWindowRateLimiter perEmail = new SlidingWindowRateLimiter(3, Duration.ofHours(1));

	public PasswordRecoveryService(AdminUserRepository users, AccountTokenService accountTokenService, AccountEmailService accountEmailService) {
		this.users = users;
		this.accountTokenService = accountTokenService;
		this.accountEmailService = accountEmailService;
	}

	public void requestReset(String email, String clientIp) {
		if (!perIp.tryAcquire(clientIp == null ? "unknown" : clientIp)) {
			throw new RateLimitExceededException(Messages.AUTH_TOO_MANY_RESET_REQUESTS);
		}
		String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		AdminUser user = users.findByEmailIgnoreCase(normalized).orElse(null);
		if (user == null || !user.isEnabled() || user.getRole() == AdminRole.DEMO_ADMIN) {
			return;
		}
		if (!perEmail.tryAcquire(normalized)) {
			LOGGER.warn("[AUTH] Password reset emails for '{}' are being requested too often — ignored.", normalized);
			return;
		}
		AccountTokenService.Issued link = accountTokenService.issue(user, AccountTokenPurpose.PASSWORD_RESET);
		accountEmailService.sendPasswordReset(user, link);
	}
}
