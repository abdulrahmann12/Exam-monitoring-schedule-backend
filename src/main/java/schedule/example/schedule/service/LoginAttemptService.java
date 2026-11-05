package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.exception.AccountLockedException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.LoginLockoutProperties;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Per-account brute-force protection: after {@code maxFailedAttempts} consecutive wrong passwords
 * the account is locked for {@code durationMinutes}. Complements the per-IP rate limiter, which
 * an attacker can sidestep by rotating IP addresses.
 *
 * <p>Recovery before the lock expires: another administrator presses "Unlock" on the Users page,
 * or (if no administrator can sign in) restart once with {@code BOOTSTRAP_ADMIN_RESET_PASSWORD=true}.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

	private static final Logger LOGGER = LoggerFactory.getLogger(LoginAttemptService.class);
	private static final DateTimeFormatter LOCK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final AdminUserRepository adminUserRepository;
	private final LoginLockoutProperties properties;

	/** Throws when the account is currently locked. Expired locks are ignored. */
	public void rejectIfLocked(AdminUser admin) {
		if (isLocked(admin, Instant.now())) {
			throw lockedException(admin.getLockedUntil());
		}
	}

	/**
	 * Counts one failed attempt; locks the account when the limit is reached.
	 *
	 * @return the lock expiry when this attempt locked the account, otherwise {@code null}.
	 *         (Returned instead of thrown: an exception here would roll the lock back.)
	 */
	@Transactional
	public Instant recordFailure(UUID adminId) {
		if (properties.maxFailedAttempts() <= 0) {
			return null;
		}
		AdminUser admin = adminUserRepository.findById(adminId).orElse(null);
		if (admin == null) {
			return null;
		}
		Instant now = Instant.now();
		if (admin.getLockedUntil() != null && !isLocked(admin, now)) {
			admin.setLockedUntil(null); // previous lock expired — start counting afresh
			admin.setFailedLoginAttempts(0);
		}
		admin.setFailedLoginAttempts(admin.getFailedLoginAttempts() + 1);
		if (admin.getFailedLoginAttempts() >= properties.maxFailedAttempts()) {
			admin.setLockedUntil(now.plus(properties.durationMinutes(), ChronoUnit.MINUTES));
			admin.setFailedLoginAttempts(0);
			adminUserRepository.save(admin);
			LOGGER.warn("[AUTH] Account '{}' LOCKED until {} after {} failed sign-in attempts.",
				admin.getEmail(), admin.getLockedUntil(), properties.maxFailedAttempts());
			return admin.getLockedUntil();
		}
		adminUserRepository.save(admin);
		return null;
	}

	public AccountLockedException lockedException(Instant lockedUntil) {
		return new AccountLockedException(MessageFormat.format(Messages.AUTH_ACCOUNT_LOCKED, LOCK_TIME.format(lockedUntil)));
	}

	/** Clears the failure counter and records the sign-in time after a successful sign-in. */
	@Transactional
	public void recordSuccess(UUID adminId) {
		adminUserRepository.findById(adminId).ifPresent(admin -> {
			admin.setFailedLoginAttempts(0);
			admin.setLockedUntil(null);
			admin.setLastLoginAt(Instant.now());
			adminUserRepository.save(admin);
		});
	}

	/** Lifts a lock right away (administrator action). */
	@Transactional
	public void unlock(AdminUser admin) {
		admin.setFailedLoginAttempts(0);
		admin.setLockedUntil(null);
		adminUserRepository.save(admin);
		LOGGER.warn("[AUTH] Account '{}' unlocked by an administrator.", admin.getEmail());
	}

	public boolean isLocked(AdminUser admin) {
		return isLocked(admin, Instant.now());
	}

	private static boolean isLocked(AdminUser admin, Instant now) {
		return admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(now);
	}
}
