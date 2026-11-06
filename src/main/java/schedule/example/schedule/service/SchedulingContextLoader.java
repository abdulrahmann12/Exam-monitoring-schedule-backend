package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.entity.PersonUnavailability;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.repository.PersonDutyWindow;
import schedule.example.schedule.repository.PersonUnavailabilityRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.SettingsRepository;
import schedule.example.schedule.tenant.TenantContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Builds a {@link SchedulingContext} for a set of exam dates. Call inside a transaction. */
@Component
@RequiredArgsConstructor
public class SchedulingContextLoader {

	private final RoomAssignmentRepository roomAssignmentRepository;
	private final PersonUnavailabilityRepository unavailabilityRepository;
	private final SettingsRepository settingsRepository;

	/**
	 * @param examDates dates whose assignments (all schedule groups) form the universe
	 * @param exclude   persisted assignments to leave out — typically the ones being replaced
	 */
	public SchedulingContext load(Collection<LocalDate> examDates, Predicate<RoomAssignment> exclude) {
		SchedulingSettings settings = settings();
		if (examDates.isEmpty()) {
			return new SchedulingContext(List.of(), Map.of(), settings, this::groupWindows, Set.of());
		}

		List<RoomAssignment> universe = new ArrayList<>();
		Set<UUID> superseded = new HashSet<>();
		for (RoomAssignment assignment : roomAssignmentRepository.findAllDetailedByExamDateIn(examDates)) {
			if (exclude.test(assignment)) {
				if (assignment.getId() != null) {
					superseded.add(assignment.getId());
				}
			} else {
				universe.add(assignment);
			}
		}

		LocalDate from = Collections.min(examDates);
		LocalDate to = Collections.max(examDates);
		Map<UUID, List<PersonUnavailability>> leave = unavailabilityRepository.findAllOverlapping(from, to).stream()
			.collect(Collectors.groupingBy(u -> u.getPerson().getId()));

		return new SchedulingContext(universe, leave, settings, this::groupWindows, superseded);
	}

	public SchedulingContext load(Collection<LocalDate> examDates) {
		return load(examDates, assignment -> false);
	}

	/** The current university's limits. */
	public SchedulingSettings settings() {
		return settingsRepository.findById(TenantContext.require())
			.map(SchedulingSettings::from)
			.orElse(SchedulingSettings.NONE);
	}

	private List<PersonDutyWindow> groupWindows(UUID groupId) {
		List<PersonDutyWindow> windows = new ArrayList<>(roomAssignmentRepository.findChiefDutyWindowsByScheduleGroupId(groupId));
		windows.addAll(roomAssignmentRepository.findInvigilatorDutyWindowsByScheduleGroupId(groupId));
		return windows;
	}
}
