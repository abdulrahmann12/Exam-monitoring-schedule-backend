package com.uniguard.schedule.service;

import com.uniguard.schedule.entity.PersonUnavailability;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.repository.PersonDutyWindow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Everything the conflict rules need to judge an assignment, loaded once per operation.
 *
 * <ul>
 *   <li>{@link #universe()} — every assignment on the relevant dates across <em>all</em> schedule
 *       groups (people and rooms are shared), plus the in-memory assignments being created.</li>
 *   <li>Date-specific leave per person.</li>
 *   <li>Institution-wide limits ({@link SchedulingSettings}).</li>
 *   <li>Lazily, all duty windows of a schedule group — only needed for the per-period limit.</li>
 * </ul>
 *
 * <p>The universe list is mutable on purpose: the auto-assigner adds each new assignment as it is
 * staffed so later picks in the same run see it.
 */
public final class SchedulingContext {

	private final List<RoomAssignment> universe;
	private final Map<UUID, List<PersonUnavailability>> leaveByPerson;
	private final SchedulingSettings settings;
	private final Function<UUID, List<PersonDutyWindow>> groupWindowsLoader;
	private final Set<UUID> supersededAssignmentIds;
	private final Map<UUID, List<PersonDutyWindow>> groupWindowsCache = new HashMap<>();

	public SchedulingContext(
		Collection<RoomAssignment> universe,
		Map<UUID, List<PersonUnavailability>> leaveByPerson,
		SchedulingSettings settings,
		Function<UUID, List<PersonDutyWindow>> groupWindowsLoader,
		Set<UUID> supersededAssignmentIds
	) {
		this.universe = new ArrayList<>(universe);
		this.leaveByPerson = Map.copyOf(leaveByPerson);
		this.settings = settings == null ? SchedulingSettings.NONE : settings;
		this.groupWindowsLoader = groupWindowsLoader == null ? groupId -> List.of() : groupWindowsLoader;
		this.supersededAssignmentIds = Set.copyOf(supersededAssignmentIds);
	}

	public SchedulingContext(Collection<RoomAssignment> universe, Map<UUID, List<PersonUnavailability>> leaveByPerson, SchedulingSettings settings) {
		this(universe, leaveByPerson, settings, null, Set.of());
	}

	/** Convenience for tests: only a daily limit. */
	public SchedulingContext(Collection<RoomAssignment> universe, Map<UUID, List<PersonUnavailability>> leaveByPerson, int maxDutiesPerDay) {
		this(universe, leaveByPerson, new SchedulingSettings(maxDutiesPerDay, 0, 0, 0));
	}

	public static SchedulingContext empty() {
		return new SchedulingContext(List.of(), Map.of(), SchedulingSettings.NONE);
	}

	public List<RoomAssignment> universe() {
		return universe;
	}

	public SchedulingSettings settings() {
		return settings;
	}

	/** {@code 0} = unlimited. */
	public int maxDutiesPerDay() {
		return settings.maxDutiesPerDay();
	}

	public void add(RoomAssignment assignment) {
		if (universe.stream().noneMatch(existing -> existing == assignment)) {
			universe.add(assignment);
		}
	}

	public void addAll(Collection<RoomAssignment> assignments) {
		assignments.forEach(this::add);
	}

	/** The leave period covering {@code date} for this person, if any. */
	public Optional<PersonUnavailability> leaveOn(UUID personId, LocalDate date) {
		return leaveByPerson.getOrDefault(personId, List.of()).stream()
			.filter(leave -> leave.covers(date))
			.findFirst();
	}

	/**
	 * Persisted duty windows of a schedule group whose assignment is <em>not</em> represented in the
	 * universe (the universe copy wins — it may contain unsaved edits) and not being replaced.
	 */
	List<PersonDutyWindow> persistedGroupWindows(UUID groupId) {
		List<PersonDutyWindow> all = groupWindowsCache.computeIfAbsent(groupId, groupWindowsLoader);
		Set<UUID> universeIds = new java.util.HashSet<>();
		for (RoomAssignment assignment : universe) {
			if (assignment.getId() != null) {
				universeIds.add(assignment.getId());
			}
		}
		List<PersonDutyWindow> result = new ArrayList<>(all.size());
		for (PersonDutyWindow window : all) {
			UUID assignmentId = window.getAssignmentId();
			if (assignmentId != null && (supersededAssignmentIds.contains(assignmentId) || universeIds.contains(assignmentId))) {
				continue;
			}
			result.add(window);
		}
		return result;
	}
}
