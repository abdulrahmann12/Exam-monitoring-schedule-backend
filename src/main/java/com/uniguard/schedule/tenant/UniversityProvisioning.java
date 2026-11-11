package com.uniguard.schedule.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uniguard.schedule.config.ApplicationDefaults;
import com.uniguard.schedule.entity.Settings;
import com.uniguard.schedule.entity.University;
import com.uniguard.schedule.entity.enums.ThemeMode;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.repository.UniversityRepository;
import com.uniguard.schedule.service.ScheduleGroupSupport;

import java.time.Instant;
import java.util.UUID;

/**
 * Sets up a university: its row, its own settings (the settings row id equals the university id)
 * and its first exam period. Safe to run again: it only adds what is missing.
 */
@Service
public class UniversityProvisioning {

	private static final Logger LOGGER = LoggerFactory.getLogger(UniversityProvisioning.class);

	private final UniversityRepository universities;
	private final SettingsRepository settingsRepository;
	private final ScheduleGroupSupport scheduleGroupSupport;
	private final com.uniguard.schedule.service.RoomCategoryService roomCategoryService;
	private final TransactionTemplate transaction;

	public UniversityProvisioning(
		UniversityRepository universities,
		SettingsRepository settingsRepository,
		ScheduleGroupSupport scheduleGroupSupport,
		com.uniguard.schedule.service.RoomCategoryService roomCategoryService,
		PlatformTransactionManager transactionManager
	) {
		this.roomCategoryService = roomCategoryService;
		this.universities = universities;
		this.settingsRepository = settingsRepository;
		this.scheduleGroupSupport = scheduleGroupSupport;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** Creates the university row if it does not exist yet (no university data is touched). */
	public University ensureUniversity(UUID id, String name, String slug) {
		return transaction.execute(status -> universities.findById(id).orElseGet(() -> {
			University university = new University();
			university.setId(id);
			university.setName(name);
			university.setSlug(slug);
			university.setActive(true);
			university.setCreatedAt(Instant.now());
			LOGGER.warn("[UNIVERSITY] Created university '{}' ({}).", name, slug);
			return universities.saveAndFlush(university);
		}));
	}

	/** Creates the university's settings and first exam period if missing. */
	public void ensureData(UUID universityId, String universityName) {
		// The transaction starts inside runAs, so its database session belongs to this university.
		TenantContext.runAs(universityId, () -> transaction.executeWithoutResult(status -> {
			if (!settingsRepository.existsById(universityId)) {
				Settings settings = new Settings();
				settings.setId(universityId);
				settings.setSystemName("Uni-Guard Schedules");
				settings.setAppTagline("Exam Invigilation Planning");
				settings.setTheme(ThemeMode.LIGHT);
				settings.setUniversityName(universityName);
				settings.setExamPeriod(ApplicationDefaults.DEFAULT_EXAM_PERIOD);
				settingsRepository.save(settings);
			}
			scheduleGroupSupport.ensureDefaultGroupAndAttachOrphans();
			roomCategoryService.ensureDefaults();
		}));
	}

	/** Default room types for a university that already exists (run on every start, does nothing twice). */
	public void ensureRoomTypes(UUID universityId) {
		TenantContext.runAs(universityId, () -> transaction.executeWithoutResult(status -> roomCategoryService.ensureDefaults()));
	}

	/** A new, empty university ready to use. */
	public University create(String name, String slug) {
		University university = ensureUniversity(UUID.randomUUID(), name, slug);
		ensureData(university.getId(), name);
		return university;
	}
}
