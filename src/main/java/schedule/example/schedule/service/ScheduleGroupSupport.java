package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.ScheduleGroup;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.ScheduleGroupRepository;
import schedule.example.schedule.repository.SettingsRepository;
import schedule.example.schedule.repository.TimeSlotRepository;

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
		return scheduleGroupRepository.findById(ApplicationDefaults.DEFAULT_SCHEDULE_GROUP_ID)
				.or(scheduleGroupRepository::findFirstByOrderByCreatedAtAsc)
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
		String name = settingsRepository.findById(ApplicationDefaults.DEFAULT_SETTINGS_ID)
				.map(Settings::getExamPeriod)
				.map(this::normalizeGroupName)
				.orElse(ApplicationDefaults.DEFAULT_EXAM_PERIOD);

		name = ensureUniqueName(name);

		ScheduleGroup group = ScheduleGroup.builder()
				.id(ApplicationDefaults.DEFAULT_SCHEDULE_GROUP_ID)
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
		return desiredName + " (" + ApplicationDefaults.DEFAULT_SCHEDULE_GROUP_ID.toString().substring(0, 8) + ")";
	}

	private String normalizeGroupName(String name) {
		if (name == null || name.isBlank()) {
			return ApplicationDefaults.DEFAULT_EXAM_PERIOD;
		}
		return name.trim();
	}
}
