package com.uniguard.schedule.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uniguard.schedule.config.ApplicationDefaults;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.entity.Settings;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.ScheduleGroupRepository;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;
import com.uniguard.schedule.tenant.TenantContext;

import java.text.MessageFormat;
import java.util.UUID;

/**
 * Resolves the default/active schedule group and attaches legacy rows that have no group.
 */
@Service
@RequiredArgsConstructor
public class ScheduleGroupSupport {

	private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleGroupSupport.class);

	private final ScheduleGroupRepository scheduleGroupRepository;
	private final SettingsRepository settingsRepository;
	private final TimeSlotRepository timeSlotRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;

	@Transactional
	public void ensureDefaultGroupAndAttachOrphans() {
		ScheduleGroup group = getOrCreateDefaultGroup();
		int slots = timeSlotRepository.attachOrphansToGroup(group);
		int assignments = roomAssignmentRepository.attachOrphansToGroup(group);
		if (slots > 0 || assignments > 0) {
			LOGGER.warn("[SCHEDULE-GROUP] Attached orphan rows to group '{}' (id={}): {} time slot(s), {} assignment(s).",
					group.getName(), group.getId(), slots, assignments);
		}
	}

	@Transactional
	public ScheduleGroup getOrCreateDefaultGroup() {
		// Tenant-filtered: always the current university's oldest exam period.
		return scheduleGroupRepository.findFirstByOrderByCreatedAtAsc()
				.orElseGet(this::createDefaultGroup);
	}

	/**
	 * Resolves a group by id, or the default group when {@code scheduleGroupId} is omitted.
	 * Existing clients that do not send a group stay on the default/active period.
	 */
	@Transactional
	public ScheduleGroup requireGroup(UUID scheduleGroupId) {
		if (scheduleGroupId == null) {
			return getOrCreateDefaultGroup();
		}
		return scheduleGroupRepository.findById(scheduleGroupId)
				.orElseThrow(() -> new NotFoundException(
						MessageFormat.format(Messages.SCHEDULE_GROUP_NOT_FOUND, scheduleGroupId)));
	}

	@Transactional
	public void syncDefaultGroupName(String examPeriod) {
		String normalized = normalizeGroupName(examPeriod);
		ScheduleGroup group = getOrCreateDefaultGroup();
		if (group.getName().equals(normalized)) {
			return;
		}

		scheduleGroupRepository.findByNameIgnoreCase(normalized)
				.filter(existing -> !existing.getId().equals(group.getId()))
				.ifPresent(existing -> {
					throw new ConflictException(MessageFormat.format(
							Messages.SCHEDULE_GROUP_NAME_EXISTS, normalized));
				});

		group.setName(normalized);
		scheduleGroupRepository.save(group);
	}

	private ScheduleGroup createDefaultGroup() {
		String name = settingsRepository.findById(TenantContext.require())
				.map(Settings::getExamPeriod)
				.map(this::normalizeGroupName)
				.orElse(ApplicationDefaults.DEFAULT_EXAM_PERIOD);

		name = ensureUniqueName(name);

		// A fresh id per university (existing installs keep their original default period row).
		ScheduleGroup group = ScheduleGroup.builder()
				.id(UUID.randomUUID())
				.name(name)
				.active(true)
				.build();
		ScheduleGroup saved = scheduleGroupRepository.saveAndFlush(group);
		LOGGER.warn("[SCHEDULE-GROUP] Default group CREATED name='{}' id={}.", saved.getName(), saved.getId());
		return saved;
	}

	private String ensureUniqueName(String desiredName) {
		if (scheduleGroupRepository.findByNameIgnoreCase(desiredName).isEmpty()) {
			return desiredName;
		}
		return desiredName + " (" + UUID.randomUUID().toString().substring(0, 8) + ")";
	}

	private String normalizeGroupName(String name) {
		if (name == null || name.isBlank()) {
			return ApplicationDefaults.DEFAULT_EXAM_PERIOD;
		}
		return name.trim();
	}
}
