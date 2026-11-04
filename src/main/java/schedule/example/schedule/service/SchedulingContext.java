package schedule.example.schedule.service;

import schedule.example.schedule.entity.PersonUnavailability;
import schedule.example.schedule.entity.RoomAssignment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the conflict rules need to judge an assignment, loaded once per operation.
 *
 * <ul>
 *   <li>{@link #universe()} — every assignment on the relevant dates across <em>all</em> schedule
 *       groups (people and rooms are shared), plus the in-memory assignments being created.</li>
 *   <li>Date-specific leave per person.</li>
 *   <li>Institution-wide limits (max duty sessions per day).</li>
 * </ul>
 *
 * <p>The universe list is mutable on purpose: the auto-assigner adds each new assignment as it is
 * staffed so later picks in the same run see it.
 */
public final class SchedulingContext {

	private final List<RoomAssignment> universe;
	private final Map<UUID, List<PersonUnavailability>> leaveByPerson;
	private final int maxDutiesPerDay;

	public SchedulingContext(
		Collection<RoomAssignment> universe,
		Map<UUID, List<PersonUnavailability>> leaveByPerson,
		int maxDutiesPerDay
	) {
		this.universe = new ArrayList<>(universe);
		this.leaveByPerson = Map.copyOf(leaveByPerson);
		this.maxDutiesPerDay = Math.max(maxDutiesPerDay, 0);
	}

	/** Context with no existing assignments, no leave and no daily limit — handy for tests. */
	public static SchedulingContext empty() {
		return new SchedulingContext(List.of(), Map.of(), 0);
	}

	public List<RoomAssignment> universe() {
		return universe;
	}

	/** {@code 0} = unlimited. */
	public int maxDutiesPerDay() {
		return maxDutiesPerDay;
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
}
