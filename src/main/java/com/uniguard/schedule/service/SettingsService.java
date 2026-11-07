package com.uniguard.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.uniguard.schedule.config.ApplicationDefaults;
import com.uniguard.schedule.config.CacheConfig;
import com.uniguard.schedule.dto.settings.SettingsRequest;
import com.uniguard.schedule.dto.settings.SettingsResponse;
import com.uniguard.schedule.entity.Settings;
import com.uniguard.schedule.entity.enums.ThemeMode;
import com.uniguard.schedule.mapper.SettingsMapper;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.tenant.TenantContext;

import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingsService {

	private final SettingsRepository settingsRepository;
	private final SettingsMapper settingsMapper;
	private final DemoModeService demoModeService;
	private final ScheduleGroupSupport scheduleGroupSupport;
	private final com.uniguard.schedule.repository.UniversityRepository universityRepository;

	/** Cached per university: the key must never be shared between universities. */
	@Cacheable(value = CacheConfig.CACHE_SETTINGS, key = "T(com.uniguard.schedule.tenant.TenantContext).require()")
	// Not read-only: the first call for a university creates its settings (and first exam period).
	// In a read-only transaction those inserts were silently dropped.
	@Transactional
	public SettingsResponse getSettings() {
		Settings settings = getOrCreateSettingsEntity();
		return withActiveGroupExamPeriod(settingsMapper.toResponse(settings));
	}

	@Transactional
	@CachePut(value = CacheConfig.CACHE_SETTINGS, key = "T(com.uniguard.schedule.tenant.TenantContext).require()")
	public SettingsResponse updateSettings(@Valid SettingsRequest request) {
		demoModeService.rejectIfDemoUser("update-settings");
		Settings settings = getOrCreateSettingsEntity();
		settingsMapper.updateEntity(request, settings);
		settings.setId(TenantContext.require());
		scheduleGroupSupport.syncDefaultGroupName(request.examPeriod());
		return withActiveGroupExamPeriod(settingsMapper.toResponse(settingsRepository.save(settings)));
	}

	private Settings getOrCreateSettingsEntity() {
		UUID universityId = TenantContext.require();
		return settingsRepository.findById(universityId)
			.orElseGet(() -> {
				Settings settings = new Settings();
				settings.setId(universityId);
				settings.setSystemName("Uni-Guard Schedules");
				settings.setAppTagline("Exam Invigilation Planning");
				settings.setLogoUrl(null);
				settings.setTheme(ThemeMode.LIGHT);
				// Start from the university's real name (its settings may not exist yet).
				settings.setUniversityName(universityRepository.findById(universityId)
					.map(com.uniguard.schedule.entity.University::getName).orElse("University"));
				settings.setDepartment(null);
				settings.setExamPeriod(ApplicationDefaults.DEFAULT_EXAM_PERIOD);
				return settingsRepository.save(settings);
			});
	}

	private SettingsResponse withActiveGroupExamPeriod(SettingsResponse response) {
		String examPeriod = scheduleGroupSupport.getOrCreateDefaultGroup().getName();
		return new SettingsResponse(
			response.id(),
			response.systemName(),
			response.appTagline(),
			response.logoUrl(),
			response.theme(),
			response.universityName(),
			response.department(),
			examPeriod,
			response.maxDutiesPerDay(),
			response.maxDutiesPerPeriod(),
			response.minRestMinutes(),
			response.studentsPerInvigilator(),
			response.chiefMaxParallelRooms()
		);
	}
}