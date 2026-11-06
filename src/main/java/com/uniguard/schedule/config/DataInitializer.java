package com.uniguard.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uniguard.schedule.audit.AuditCategory;
import com.uniguard.schedule.audit.AuditEntry;
import com.uniguard.schedule.audit.AuditService;
import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.Settings;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.entity.enums.ThemeMode;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.security.AdminUserDetailsService;
import com.uniguard.schedule.security.PasswordPolicy;
import com.uniguard.schedule.service.ScheduleGroupSupport;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.tenant.UniversityProvisioning;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Component
public class DataInitializer implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

	private final AdminUserRepository adminUserRepository;
	private final SettingsRepository settingsRepository;
	private final BootstrapAdminProperties bootstrapAdminProperties;
	private final BootstrapOwnerProperties bootstrapOwnerProperties;
	private final DemoProperties demoProperties;
	private final PasswordEncoder passwordEncoder;
	private final AdminUserDetailsService adminUserDetailsService;
	private final ScheduleGroupSupport scheduleGroupSupport;
	private final AuditService auditService;
	private final UniversityProvisioning universityProvisioning;
	private final com.uniguard.schedule.service.TwoFactorService twoFactorService;
	private final TransactionTemplate transaction;

	public DataInitializer(
		AdminUserRepository adminUserRepository,
		SettingsRepository settingsRepository,
		BootstrapAdminProperties bootstrapAdminProperties,
		BootstrapOwnerProperties bootstrapOwnerProperties,
		DemoProperties demoProperties,
		PasswordEncoder passwordEncoder,
		AdminUserDetailsService adminUserDetailsService,
		ScheduleGroupSupport scheduleGroupSupport,
		AuditService auditService,
		UniversityProvisioning universityProvisioning,
		com.uniguard.schedule.service.TwoFactorService twoFactorService,
		PlatformTransactionManager transactionManager
	) {
		this.adminUserRepository = adminUserRepository;
		this.settingsRepository = settingsRepository;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
		this.bootstrapOwnerProperties = bootstrapOwnerProperties;
		this.demoProperties = demoProperties;
		this.passwordEncoder = passwordEncoder;
		this.adminUserDetailsService = adminUserDetailsService;
		this.scheduleGroupSupport = scheduleGroupSupport;
		this.auditService = auditService;
		this.universityProvisioning = universityProvisioning;
		this.twoFactorService = twoFactorService;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	private static final int MIN_BOOTSTRAP_PASSWORD_LENGTH = 10;

	/**
	 * Not one big transaction on purpose: university data must be written inside
	 * {@code TenantContext.runAs}, which has to wrap its own transaction (see {@link UniversityProvisioning}).
	 */
	@Override
	public void run(ApplicationArguments args) {
		// The first university always exists (on upgrades the V9 migration already created it).
		String defaultName = settingsRepository.findById(TenantContext.DEFAULT_UNIVERSITY_ID)
			.map(Settings::getUniversityName)
			.filter(name -> name != null && !name.isBlank())
			.orElse("University");
		universityProvisioning.ensureUniversity(TenantContext.DEFAULT_UNIVERSITY_ID, defaultName, "default");

		transaction.executeWithoutResult(status -> {
			// Accounts from before universities existed belong to the first university.
			int adopted = adminUserRepository.assignUniversityWhereMissing(TenantContext.DEFAULT_UNIVERSITY_ID);
			if (adopted > 0) {
				LOGGER.warn("[UNIVERSITY] {} account(s) without a university were assigned to the first university.", adopted);
			}
			bootstrapAdministrator();
			bootstrapOwner();
			if (demoProperties.enabled()) {
				seedDemoAccount();
			} else {
				LOGGER.warn("[DEMO] Demo mode is disabled (app.demo.enabled=false). Demo account will not be seeded.");
			}
		});

		universityProvisioning.ensureData(TenantContext.DEFAULT_UNIVERSITY_ID, defaultName);
	}

	/**
	 * Creates the first administrator from {@code BOOTSTRAP_ADMIN_EMAIL}/{@code BOOTSTRAP_ADMIN_PASSWORD}.
	 *
	 * <ul>
	 *   <li>No administrator yet → both variables are required; the account is created.</li>
	 *   <li>An administrator exists → nothing happens. Restarts never overwrite a password that an
	 *       admin changed through the app (the old behaviour reset it on every restart).</li>
	 *   <li>{@code BOOTSTRAP_ADMIN_RESET_PASSWORD=true} → recovery: that account's password is reset
	 *       and all its sessions are revoked. Remove the flag again after one restart.</li>
	 * </ul>
	 */
	private void bootstrapAdministrator() {
		String email = normalize(bootstrapAdminProperties.email());
		String password = bootstrapAdminProperties.password();
		boolean adminExists = adminUserRepository.existsByRole(AdminRole.ADMIN);

		if (!adminExists) {
			if (email == null || password == null || password.isBlank()) {
				throw new BeanCreationException(
					"No administrator account exists. Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD "
						+ "(at least " + MIN_BOOTSTRAP_PASSWORD_LENGTH + " characters) to create the first one.");
			}
			requireStrongPassword(password, "BOOTSTRAP_ADMIN_PASSWORD");
			AdminUser admin = adminUserRepository.findByEmailIgnoreCase(email).orElseGet(AdminUser::new);
			admin.setEmail(email);
			admin.setRole(AdminRole.ADMIN);
			admin.setEnabled(true);
			admin.setMustChangePassword(false);
			admin.setUniversityId(TenantContext.DEFAULT_UNIVERSITY_ID);
			admin.setPasswordHash(passwordEncoder.encode(password));
			adminUserRepository.saveAndFlush(admin);
			auditService.recordAfterCommit(new AuditEntry("FIRST_ADMIN_CREATED", AuditCategory.USERS, "USER", null,
				"First administrator created from the server settings", AuditEntry.Outcome.SUCCESS, email));
			LOGGER.warn("[BOOTSTRAP] First administrator CREATED for '{}'. "
				+ "You can now remove BOOTSTRAP_ADMIN_PASSWORD from the environment.", email);
		} else if (bootstrapAdminProperties.resetPassword()) {
			if (email == null || password == null || password.isBlank()) {
				throw new BeanCreationException(
					"BOOTSTRAP_ADMIN_RESET_PASSWORD=true requires BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD.");
			}
			requireStrongPassword(password, "BOOTSTRAP_ADMIN_PASSWORD");
			AdminUser admin = adminUserRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new BeanCreationException("Cannot reset password: no account '" + email + "'."));
			admin.setPasswordHash(passwordEncoder.encode(password));
			admin.setTokenVersion(admin.getTokenVersion() + 1);
			twoFactorService.clear(admin); // recovery must work even when the phone is lost
			admin.setFailedLoginAttempts(0);
			admin.setLockedUntil(null);
			// Recovery must always end with a usable administrator.
			admin.setRole(AdminRole.ADMIN);
			admin.setEnabled(true);
			admin.setMustChangePassword(false);
			adminUserRepository.saveAndFlush(admin);
			auditService.recordAfterCommit(new AuditEntry("ADMIN_PASSWORD_RESET_BY_SERVER", AuditCategory.SECURITY, "USER", null,
				"Password reset from the server settings (BOOTSTRAP_ADMIN_RESET_PASSWORD)", AuditEntry.Outcome.SUCCESS, email));
			LOGGER.warn("[BOOTSTRAP] Password RESET for '{}' and all sessions revoked. "
				+ "Remove BOOTSTRAP_ADMIN_RESET_PASSWORD now.", email);
		}

		int orphansRemoved = adminUserRepository.deleteOrphanedPasswordlessAccounts(email == null ? "" : email);
		if (orphansRemoved > 0) {
			LOGGER.warn("[BOOTSTRAP] Removed {} admin row(s) without a password hash.", orphansRemoved);
		}
	}

	private static void requireStrongPassword(String password, String variable) {
		if (password.length() < MIN_BOOTSTRAP_PASSWORD_LENGTH) {
			throw new BeanCreationException(
				variable + " must be at least " + MIN_BOOTSTRAP_PASSWORD_LENGTH + " characters.");
		}
		if (PasswordPolicy.exceedsMaximum(password)) {
			throw new BeanCreationException(
				variable + " must be at most " + PasswordPolicy.MAX_BYTES + " bytes.");
		}
	}

	/**
	 * Creates the platform owner (super-admin) from {@code BOOTSTRAP_OWNER_EMAIL}/{@code BOOTSTRAP_OWNER_PASSWORD}.
	 *
	 * <ul>
	 *   <li>Optional: without both variables no owner is created (the app works without an owner panel).</li>
	 *   <li>Only when no owner exists yet. Restarts never overwrite the owner's password.</li>
	 *   <li>The email must not belong to a university account: turning a university user into the
	 *       owner silently would hand them every university. Start-up stops with a clear message instead.</li>
	 *   <li>{@code BOOTSTRAP_OWNER_RESET_PASSWORD=true} → recovery for one restart (password reset, sessions ended).</li>
	 * </ul>
	 */
	private void bootstrapOwner() {
		String email = normalize(bootstrapOwnerProperties.email());
		String password = bootstrapOwnerProperties.password();
		boolean configured = email != null && password != null && !password.isBlank();

		if (!adminUserRepository.existsByRole(AdminRole.SUPER_ADMIN)) {
			if (!configured) {
				LOGGER.info("[BOOTSTRAP] No platform owner configured (BOOTSTRAP_OWNER_EMAIL / BOOTSTRAP_OWNER_PASSWORD); owner panel unused.");
				return;
			}
			requireStrongPassword(password, "BOOTSTRAP_OWNER_PASSWORD");
			if (adminUserRepository.findByEmailIgnoreCase(email).isPresent()) {
				throw new BeanCreationException("BOOTSTRAP_OWNER_EMAIL '" + email + "' already belongs to a university account. "
					+ "Use a separate email for the platform owner.");
			}
			AdminUser owner = new AdminUser();
			owner.setEmail(email);
			owner.setDisplayName("Platform owner");
			owner.setRole(AdminRole.SUPER_ADMIN);
			owner.setEnabled(true);
			owner.setMustChangePassword(false);
			owner.setUniversityId(null);
			owner.setPasswordHash(passwordEncoder.encode(password));
			adminUserRepository.saveAndFlush(owner);
			auditService.recordAfterCommit(new AuditEntry("OWNER_CREATED", AuditCategory.PLATFORM, "USER", null,
				"Platform owner created from the server settings", AuditEntry.Outcome.SUCCESS, email));
			LOGGER.warn("[BOOTSTRAP] Platform owner CREATED for '{}'. You can now remove BOOTSTRAP_OWNER_PASSWORD.", email);
		} else if (bootstrapOwnerProperties.resetPassword()) {
			if (!configured) {
				throw new BeanCreationException(
					"BOOTSTRAP_OWNER_RESET_PASSWORD=true requires BOOTSTRAP_OWNER_EMAIL and BOOTSTRAP_OWNER_PASSWORD.");
			}
			requireStrongPassword(password, "BOOTSTRAP_OWNER_PASSWORD");
			AdminUser owner = adminUserRepository.findByEmailIgnoreCase(email)
				.filter(found -> found.getRole() == AdminRole.SUPER_ADMIN)
				.orElseThrow(() -> new BeanCreationException("Cannot reset password: '" + email + "' is not the platform owner."));
			owner.setPasswordHash(passwordEncoder.encode(password));
			owner.setTokenVersion(owner.getTokenVersion() + 1);
			twoFactorService.clear(owner); // recovery must work even when the phone is lost
			owner.setFailedLoginAttempts(0);
			owner.setLockedUntil(null);
			owner.setEnabled(true);
			owner.setMustChangePassword(false);
			adminUserRepository.saveAndFlush(owner);
			auditService.recordAfterCommit(new AuditEntry("OWNER_PASSWORD_RESET_BY_SERVER", AuditCategory.SECURITY, "USER", null,
				"Platform owner password reset from the server settings (BOOTSTRAP_OWNER_RESET_PASSWORD)", AuditEntry.Outcome.SUCCESS, email));
			LOGGER.warn("[BOOTSTRAP] Platform owner password RESET for '{}'. Remove BOOTSTRAP_OWNER_RESET_PASSWORD now.", email);
		}
	}

	private static String normalize(String email) {
		return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Seeds the demo account with a random password and DEMO_ADMIN role.
	 * Idempotent — skips if the account already exists.
	 * Authentication bypasses password check via DemoAuthService.
	 */
	private void seedDemoAccount() {
		String demoEmail = DemoProperties.DEMO_EMAIL;

		if (adminUserRepository.findByEmailIgnoreCase(demoEmail).isPresent()) {
			LOGGER.warn("[DEMO] Demo account '{}' already exists — skipping seed.", demoEmail);
			return;
		}

		// Generate a random password — never exposed; demo login bypasses it.
		byte[] randomBytes = new byte[32];
		new SecureRandom().nextBytes(randomBytes);
		String randomPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

		AdminUser demoUser = new AdminUser();
		demoUser.setEmail(demoEmail.toLowerCase(Locale.ROOT));
		demoUser.setPasswordHash(passwordEncoder.encode(randomPassword));
		demoUser.setRole(AdminRole.DEMO_ADMIN);
		demoUser.setEnabled(true);
		demoUser.setUniversityId(TenantContext.DEFAULT_UNIVERSITY_ID);
		adminUserRepository.saveAndFlush(demoUser);

		LOGGER.warn("[DEMO] Demo account CREATED for email='{}' with role=DEMO_ADMIN. " +
			"Use POST /api/auth/demo-login to authenticate as this account.", demoEmail);
	}
}