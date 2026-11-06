package schedule.example.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import schedule.example.schedule.audit.AuditCategory;
import schedule.example.schedule.audit.AuditEntry;
import schedule.example.schedule.audit.AuditService;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.entity.enums.ThemeMode;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.SettingsRepository;
import schedule.example.schedule.security.AdminUserDetailsService;
import schedule.example.schedule.security.PasswordPolicy;
import schedule.example.schedule.service.ScheduleGroupSupport;
import schedule.example.schedule.tenant.TenantContext;
import schedule.example.schedule.tenant.UniversityProvisioning;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Component
public class DataInitializer implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

	private final AdminUserRepository adminUserRepository;
	private final SettingsRepository settingsRepository;
	private final BootstrapAdminProperties bootstrapAdminProperties;
	private final DemoProperties demoProperties;
	private final PasswordEncoder passwordEncoder;
	private final AdminUserDetailsService adminUserDetailsService;
	private final ScheduleGroupSupport scheduleGroupSupport;
	private final AuditService auditService;
	private final UniversityProvisioning universityProvisioning;
	private final TransactionTemplate transaction;

	public DataInitializer(
		AdminUserRepository adminUserRepository,
		SettingsRepository settingsRepository,
		BootstrapAdminProperties bootstrapAdminProperties,
		DemoProperties demoProperties,
		PasswordEncoder passwordEncoder,
		AdminUserDetailsService adminUserDetailsService,
		ScheduleGroupSupport scheduleGroupSupport,
		AuditService auditService,
		UniversityProvisioning universityProvisioning,
		PlatformTransactionManager transactionManager
	) {
		this.adminUserRepository = adminUserRepository;
		this.settingsRepository = settingsRepository;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
		this.demoProperties = demoProperties;
		this.passwordEncoder = passwordEncoder;
		this.adminUserDetailsService = adminUserDetailsService;
		this.scheduleGroupSupport = scheduleGroupSupport;
		this.auditService = auditService;
		this.universityProvisioning = universityProvisioning;
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
			requireStrongPassword(password);
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
			requireStrongPassword(password);
			AdminUser admin = adminUserRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new BeanCreationException("Cannot reset password: no account '" + email + "'."));
			admin.setPasswordHash(passwordEncoder.encode(password));
			admin.setTokenVersion(admin.getTokenVersion() + 1);
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

	private static void requireStrongPassword(String password) {
		if (password.length() < MIN_BOOTSTRAP_PASSWORD_LENGTH) {
			throw new BeanCreationException(
				"BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_BOOTSTRAP_PASSWORD_LENGTH + " characters.");
		}
		if (PasswordPolicy.exceedsMaximum(password)) {
			throw new BeanCreationException(
				"BOOTSTRAP_ADMIN_PASSWORD must be at most " + PasswordPolicy.MAX_BYTES + " bytes.");
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