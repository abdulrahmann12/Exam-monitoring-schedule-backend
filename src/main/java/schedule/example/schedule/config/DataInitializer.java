package schedule.example.schedule.config;

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

import java.util.Locale;

@Component
public class DataInitializer implements ApplicationRunner {

	private final AdminUserRepository adminUserRepository;
	private final SettingsRepository settingsRepository;
	private final BootstrapAdminProperties bootstrapAdminProperties;
	private final PasswordEncoder passwordEncoder;

	public DataInitializer(
		AdminUserRepository adminUserRepository,
		SettingsRepository settingsRepository,
		BootstrapAdminProperties bootstrapAdminProperties,
		PasswordEncoder passwordEncoder
	) {
		this.adminUserRepository = adminUserRepository;
		this.settingsRepository = settingsRepository;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
		this.passwordEncoder = passwordEncoder;
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

		adminUserRepository.findByEmailIgnoreCase(normalizedEmail)
			.orElseGet(() -> {
				AdminUser adminUser = new AdminUser();
				adminUser.setEmail(normalizedEmail);
				adminUser.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
				return adminUserRepository.save(adminUser);
			});

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