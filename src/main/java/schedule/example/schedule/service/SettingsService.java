package schedule.example.schedule.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.dto.settings.SettingsRequest;
import schedule.example.schedule.dto.settings.SettingsResponse;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.entity.enums.ThemeMode;
import schedule.example.schedule.mapper.SettingsMapper;
import schedule.example.schedule.repository.SettingsRepository;

@Service
@Transactional(readOnly = true)
public class SettingsService {

	private final SettingsRepository settingsRepository;
	private final SettingsMapper settingsMapper;

	public SettingsService(SettingsRepository settingsRepository, SettingsMapper settingsMapper) {
		this.settingsRepository = settingsRepository;
		this.settingsMapper = settingsMapper;
	}

	public SettingsResponse getSettings() {
		return settingsMapper.toResponse(getOrCreateSettingsEntity());
	}

	@Transactional
	public SettingsResponse updateSettings(SettingsRequest request) {
		Settings settings = getOrCreateSettingsEntity();
		settingsMapper.updateEntity(request, settings);
		settings.setId(ApplicationDefaults.DEFAULT_SETTINGS_ID);
		return settingsMapper.toResponse(settingsRepository.save(settings));
	}

	private Settings getOrCreateSettingsEntity() {
		return settingsRepository.findById(ApplicationDefaults.DEFAULT_SETTINGS_ID)
			.orElseGet(() -> {
				Settings settings = new Settings();
				settings.setId(ApplicationDefaults.DEFAULT_SETTINGS_ID);
				settings.setSystemName("Uni-Guard Schedules");
				settings.setAppTagline("Exam Invigilation Planning");
				settings.setLogoUrl(null);
				settings.setTheme(ThemeMode.LIGHT);
				settings.setUniversityName("University");
				settings.setDepartment(null);
				settings.setExamPeriod("Current Semester");
				return settingsRepository.save(settings);
			});
	}
}