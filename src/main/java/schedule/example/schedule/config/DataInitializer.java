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

	public DataInitializer(
		AdminUserRepository adminUserRepository,
		SettingsRepository settingsRepository,
		BootstrapAdminProperties bootstrapAdminProperties,
		DemoProperties demoProperties,
		PasswordEncoder passwordEncoder,
		AdminUserDetailsService adminUserDetailsService
	) {
		this.adminUserRepository = adminUserRepository;
		this.settingsRepository = settingsRepository;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
		this.demoProperties = demoProperties;
		this.passwordEncoder = passwordEncoder;
		this.adminUserDetailsService = adminUserDetailsService;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		String bootstrapEmail = requireConfiguredValue(
			bootstrapAdminProperties.email(),
			"app.bootstrap-admin.email"
		);
		String bootstrapPassword = requireConfiguredValue(
			bootstrapAdminProperties.password(),
			"app.bootstrap-admin.password"
		);
		String normalizedEmail = bootstrapEmail.trim().toLowerCase(Locale.ROOT);

		boolean[] isNew = {false};
		AdminUser adminUser = adminUserRepository.findByEmailIgnoreCase(normalizedEmail)
			.orElseGet(() -> {
				isNew[0] = true;
				AdminUser newAdmin = new AdminUser();
				newAdmin.setEmail(normalizedEmail);
				return newAdmin;
			});

		// Sync password on every restart so changes to the config take effect immediately.
		adminUser.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
		AdminUser saved = adminUserRepository.saveAndFlush(adminUser);

		// Fail fast if the hash wasn't persisted — catches mapping or DB constraint issues.
		if (saved.getPasswordHash() == null || saved.getPasswordHash().isBlank()) {
			throw new IllegalStateException(
				"[BOOTSTRAP] FATAL: password_hash was not persisted for admin '" + normalizedEmail +
				"'. Check DB constraints and JPA column mapping on AdminUser.passwordHash."
			);
		}

		// Evict cached UserDetails so the new password hash is picked up immediately.
		adminUserDetailsService.evictUserCache(normalizedEmail);

		// Remove ghost rows with null/empty password_hash left by previous failed deployments.
		int orphansRemoved = adminUserRepository.deleteOrphanedPasswordlessAccounts(normalizedEmail);
		if (orphansRemoved > 0) {
			adminUserDetailsService.evictAllUserCache();
			LOGGER.warn("[BOOTSTRAP] Removed {} orphaned admin row(s) with null/empty password_hash. " +
				"These were ghost rows from a previous deployment. Login should now work correctly.", orphansRemoved);
		}

		// Log at WARN so this event is visible even when root log level is WARN.
		if (isNew[0]) {
			LOGGER.warn("[BOOTSTRAP] Admin account CREATED for email='{}'. " +
				"Source: env var BOOTSTRAP_ADMIN_EMAIL or default in application.properties.", normalizedEmail);
		} else {
			LOGGER.warn("[BOOTSTRAP] Admin account password SYNCED for email='{}'. " +
				"If login still fails, verify the BOOTSTRAP_ADMIN_EMAIL env var matches this email " +
				"and that BOOTSTRAP_ADMIN_PASSWORD matches the password you are using.", normalizedEmail);
		}

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
			settings.setExamPeriod("Current Semester");
			settingsRepository.save(settings);
		}
	}

	private String requireConfiguredValue(String value, String propertyName) {
		if (value == null || value.isBlank()) {
			throw new BeanCreationException(
				"Missing required configuration property '" + propertyName + "'"
			);
		}
		return value;
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
		adminUserRepository.saveAndFlush(demoUser);

		LOGGER.warn("[DEMO] Demo account CREATED for email='{}' with role=DEMO_ADMIN. " +
			"Use POST /api/auth/demo-login to authenticate as this account.", demoEmail);
	}
}