package schedule.example.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.entity.enums.ThemeMode;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.SettingsRepository;
import schedule.example.schedule.security.AdminUserDetailsService;
import schedule.example.schedule.security.PasswordPolicy;
import schedule.example.schedule.service.ScheduleGroupSupport;

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

	public DataInitializer(
		AdminUserRepository adminUserRepository,
		SettingsRepository settingsRepository,
		BootstrapAdminProperties bootstrapAdminProperties,
		DemoProperties demoProperties,
		PasswordEncoder passwordEncoder,
		AdminUserDetailsService adminUserDetailsService,
		ScheduleGroupSupport scheduleGroupSupport
	) {
		this.adminUserRepository = adminUserRepository;
		this.settingsRepository = settingsRepository;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
		this.demoProperties = demoProperties;
		this.passwordEncoder = passwordEncoder;
		this.adminUserDetailsService = adminUserDetailsService;
		this.scheduleGroupSupport = scheduleGroupSupport;
	}

	private static final int MIN_BOOTSTRAP_PASSWORD_LENGTH = 10;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		bootstrapAdministrator();

		// ── Demo account ──────────────────────────────────────────────────────
		if (demoProperties.enabled()) {
			seedDemoAccount();
		} else {
			LOGGER.warn("[DEMO] Demo mode is disabled (app.demo.enabled=false). Demo account will not be seeded.");
		}

		if (!settingsRepository.existsById(ApplicationDefaults.DEFAULT_SETTINGS_ID)) {			Settings settings = new Settings();
			settings.setId(ApplicationDefaults.DEFAULT_SETTINGS_ID);
			settings.setSystemName("Uni-Guard Schedules");
			settings.setAppTagline("Exam Invigilation Planning");
			settings.setLogoUrl(null);
			settings.setTheme(ThemeMode.LIGHT);
			settings.setUniversityName("University");
			settings.setDepartment(null);
			settings.setExamPeriod(ApplicationDefaults.DEFAULT_EXAM_PERIOD);
			settingsRepository.save(settings);
		}

		scheduleGroupSupport.ensureDefaultGroupAndAttachOrphans();
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
			admin.setPasswordHash(passwordEncoder.encode(password));
			adminUserRepository.saveAndFlush(admin);
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
		adminUserRepository.saveAndFlush(demoUser);

		LOGGER.warn("[DEMO] Demo account CREATED for email='{}' with role=DEMO_ADMIN. " +
			"Use POST /api/auth/demo-login to authenticate as this account.", demoEmail);
	}
}