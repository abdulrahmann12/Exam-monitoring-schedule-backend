package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.entity.PersonUnavailability;
import schedule.example.schedule.entity.RoomAssignment;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.repository.PersonUnavailabilityRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.SettingsRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
		if (examDates.isEmpty()) {
			return new SchedulingContext(List.of(), Map.of(), maxDutiesPerDay());
		}

		List<RoomAssignment> universe = roomAssignmentRepository.findAllDetailedByExamDateIn(examDates).stream()
			.filter(exclude.negate())
			.toList();

		LocalDate from = Collections.min(examDates);
		LocalDate to = Collections.max(examDates);
		Map<UUID, List<PersonUnavailability>> leave = unavailabilityRepository.findAllOverlapping(from, to).stream()
			.collect(Collectors.groupingBy(u -> u.getPerson().getId()));

		return new SchedulingContext(universe, leave, maxDutiesPerDay());
	}

	public SchedulingContext load(Collection<LocalDate> examDates) {
		return load(examDates, assignment -> false);
	}

	private int maxDutiesPerDay() {
		return settingsRepository.findById(ApplicationDefaults.DEFAULT_SETTINGS_ID)
			.map(Settings::getMaxDutiesPerDay)
			.orElse(0);
	}
}
