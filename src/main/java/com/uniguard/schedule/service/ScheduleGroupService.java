package com.uniguard.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.uniguard.schedule.config.ApplicationDefaults;
import com.uniguard.schedule.config.CacheConfig;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupRequest;
import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupResponse;
import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.mapper.ScheduleGroupMapper;
import com.uniguard.schedule.repository.GroupCountProjection;
import com.uniguard.schedule.repository.PersonRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.ScheduleGroupRepository;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.repository.TimeSlotRepository;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleGroupService {

	private final ScheduleGroupRepository scheduleGroupRepository;
	private final TimeSlotRepository timeSlotRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final PersonRepository personRepository;
	private final SettingsRepository settingsRepository;
	private final ScheduleGroupMapper scheduleGroupMapper;
	private final ScheduleGroupSupport scheduleGroupSupport;
	private final DemoModeService demoModeService;
	private final CacheManager cacheManager;
	private final com.uniguard.schedule.repository.RoomAllocationBatchRepository allocationBatchRepository;

	public PageResponse<ScheduleGroupResponse> getScheduleGroups(String name, Boolean activeOnly, Pageable pageable) {
		String namePrefix = (name != null && !name.isBlank()) ? name.trim() : null;
		Page<ScheduleGroup> page = scheduleGroupRepository.search(namePrefix, activeOnly, pageable);
		List<UUID> groupIds = page.getContent().stream().map(ScheduleGroup::getId).toList();
		long sharedSlots = timeSlotRepository.count(); // time slots are shared by every exam period
		Map<UUID, Long> assignmentCounts = groupIds.isEmpty()
			? Map.of()
			: toCountMap(roomAssignmentRepository.countByScheduleGroupIdIn(groupIds));

		Page<ScheduleGroupResponse> mapped = page.map(group -> scheduleGroupMapper.toResponse(
			group,
			sharedSlots,
			assignmentCounts.getOrDefault(group.getId(), 0L)
		));
		return PageResponse.from(mapped);
	}

	public ScheduleGroupResponse getScheduleGroup(UUID id) {
		ScheduleGroup group = getGroupEntity(id);
		return scheduleGroupMapper.toResponse(
			group,
			timeSlotRepository.count(),
			roomAssignmentRepository.countByScheduleGroupId(id)
		);
	}

	@Transactional
	public ScheduleGroupResponse createScheduleGroup(@Valid ScheduleGroupRequest request) {
		String name = request.name().trim();
		validateUniqueName(name, null);

		ScheduleGroup group = scheduleGroupMapper.toEntity(request);
		group.setName(name);
		group.setActive(request.active() == null || request.active());
		group.setArchived(false);
		ScheduleGroup saved = scheduleGroupRepository.save(group);
		return scheduleGroupMapper.toResponse(saved, timeSlotRepository.count(), 0);
	}

	@Transactional
	public ScheduleGroupResponse updateScheduleGroup(UUID id, @Valid ScheduleGroupRequest request) {
		ScheduleGroup group = getGroupEntity(id);
		String name = request.name().trim();
		validateUniqueName(name, id);

		scheduleGroupMapper.updateEntity(request, group);
		group.setName(name);
		if (request.active() != null) {
			group.setActive(request.active());
		}

		ScheduleGroup saved = scheduleGroupRepository.save(group);
		syncExamPeriodIfDefault(saved);
		return scheduleGroupMapper.toResponse(
			saved,
			timeSlotRepository.count(),
			roomAssignmentRepository.countByScheduleGroupId(id)
		);
	}

	/**
	 * Deletes a group and that group's assignments only.
	 * People, rooms and time slots are shared by every exam period and are left untouched. The last remaining group cannot be removed.
	 */
	@Transactional
	public void deleteScheduleGroup(UUID id) {
		demoModeService.rejectIfDemoUser("delete-schedule-group");
		ScheduleGroup group = getGroupEntity(id);

		if (scheduleGroupRepository.count() <= 1) {
			throw new ConflictException(Messages.SCHEDULE_GROUP_DELETE_LAST);
		}

		boolean wasDefault = scheduleGroupSupport.getOrCreateDefaultGroup().getId().equals(id);

		List<RoomAssignment> assignments = roomAssignmentRepository.findAllDetailedByScheduleGroupId(id);
		decrementWorkload(assignments);
		roomAssignmentRepository.deleteAll(assignments);
		roomAssignmentRepository.flush();
		// Saved room allocations of this period go too (the database does it on MySQL; not every database does).
		allocationBatchRepository.deleteAll(allocationBatchRepository.findAllByScheduleGroupIdOrderByCreatedAtDesc(id));
		allocationBatchRepository.flush();
		scheduleGroupRepository.delete(group);

		if (wasDefault) {
			syncExamPeriodIfDefault(scheduleGroupSupport.getOrCreateDefaultGroup());
		}
		evictCache(CacheConfig.CACHE_DASHBOARD);
	}

	private ScheduleGroup getGroupEntity(UUID id) {
		return scheduleGroupRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.SCHEDULE_GROUP_NOT_FOUND, id)));
	}

	private void validateUniqueName(String name, UUID existingId) {
		scheduleGroupRepository.findByNameIgnoreCase(name)
			.filter(existing -> existingId == null || !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(MessageFormat.format(Messages.SCHEDULE_GROUP_NAME_EXISTS, name));
			});
	}

	private void syncExamPeriodIfDefault(ScheduleGroup group) {
		ScheduleGroup defaultGroup = scheduleGroupSupport.getOrCreateDefaultGroup();
		if (!defaultGroup.getId().equals(group.getId())) {
			return;
		}

		settingsRepository.findById(TenantContext.require()).ifPresent(settings -> {
			settings.setExamPeriod(group.getName());
			settingsRepository.save(settings);
		});
		evictCache(CacheConfig.CACHE_SETTINGS);
	}

	private void decrementWorkload(List<RoomAssignment> assignments) {
		Map<UUID, Integer> deltas = new HashMap<>();
		for (RoomAssignment assignment : assignments) {
			if (assignment.getChiefInvigilator() != null) {
				deltas.merge(assignment.getChiefInvigilator().getId(), -1, Integer::sum);
			}
			for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
				if (ia.getInvigilator() != null) {
					deltas.merge(ia.getInvigilator().getId(), -1, Integer::sum);
				}
			}
		}
		if (deltas.isEmpty()) {
			return;
		}

		List<Person> people = personRepository.findAllById(deltas.keySet());
		for (Person person : people) {
			Integer delta = deltas.get(person.getId());
			if (delta != null) {
				person.setTotalAssignments(Math.max(0, person.getTotalAssignments() + delta));
			}
		}
		personRepository.saveAll(people);
	}

	private Map<UUID, Long> toCountMap(Collection<GroupCountProjection> rows) {
		return rows.stream().collect(Collectors.toMap(GroupCountProjection::getGroupId, GroupCountProjection::getTotal));
	}

	private void evictCache(String cacheName) {
		Cache cache = cacheManager.getCache(cacheName);
		if (cache != null) {
			cache.clear();
		}
	}
}
