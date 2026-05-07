package schedule.example.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.entity.enums.ThemeMode;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.SettingsRepository;
import schedule.example.schedule.security.AdminUserDetailsService;

import java.util.Locale;

@Component
public class DataInitializer implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(DataInitializer.class);

	private final AdminUserRepository adminUserRepository;
	private final SettingsRepository settingsRepository;
	private final BootstrapAdminProperties bootstrapAdminProperties;
	private final PasswordEncoder passwordEncoder;
	private final AdminUserDetailsService adminUserDetailsService;

	public DataInitializer(
		AdminUserRepository adminUserRepository,
		SettingsRepository settingsRepository,
		BootstrapAdminProperties bootstrapAdminProperties,
		PasswordEncoder passwordEncoder,
		AdminUserDetailsService adminUserDetailsService
	) {
		this.adminUserRepository = adminUserRepository;
		this.settingsRepository = settingsRepository;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
		this.passwordEncoder = passwordEncoder;
		this.adminUserDetailsService = adminUserDetailsService;
	}

	@Override
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

		AdminUser adminUser = adminUserRepository.findByEmailIgnoreCase(normalizedEmail)
			.orElseGet(() -> {
				AdminUser newAdmin = new AdminUser();
				newAdmin.setEmail(normalizedEmail);
				return newAdmin;
			});

		// Always sync the password so changing app.bootstrap-admin.password takes effect on restart.
		adminUser.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
		adminUserRepository.save(adminUser);

		// Evict any stale cached UserDetails so the new password hash is used immediately.
		adminUserDetailsService.evictUserCache(normalizedEmail);
		LOGGER.info("Bootstrap admin account synced for: {}", normalizedEmail);

		if (!settingsRepository.existsById(ApplicationDefaults.DEFAULT_SETTINGS_ID)) {
			Settings settings = new Settings();
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
}