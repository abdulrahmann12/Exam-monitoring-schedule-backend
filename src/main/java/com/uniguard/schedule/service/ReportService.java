package com.uniguard.schedule.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.repository.PersonRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.util.CsvExport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reports for one exam period: sessions and hours per person (also as CSV for finance), workload per
 * department, fairness, and coverage gaps (exam rooms missing staff).
 *
 * <p>Counting rules are the same as automatic assignment uses: a <em>session</em> is one sitting
 * (date + start + end); a chief covering two rooms in the same sitting works one session, not two.
 * Hours are the length of the sittings. Staff without any duty are included (with zero), because
 * they matter for fairness.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

	private final ScheduleGroupSupport scheduleGroupSupport;
	private final RoomAssignmentRepository assignments;
	private final PersonRepository people;
	private final SchedulingContextLoader contextLoader;

	public ReportService(ScheduleGroupSupport scheduleGroupSupport, RoomAssignmentRepository assignments, PersonRepository people,
		SchedulingContextLoader contextLoader) {
		this.scheduleGroupSupport = scheduleGroupSupport;
		this.assignments = assignments;
		this.people = people;
		this.contextLoader = contextLoader;
	}

	// ─────────────────────────── Workload per person ───────────────────────────

	public record PersonWorkload(UUID personId, String name, String email, String department, String role, boolean active,
		int sessions, BigDecimal hours, int roomsAsChief, int roomsAsInvigilator, int days, LocalDate firstDuty, LocalDate lastDuty) {
	}

	public record WorkloadReport(UUID scheduleGroupId, String scheduleGroupName, int staff, int sessions, BigDecimal hours,
		List<PersonWorkload> people) {
	}

	public WorkloadReport workload(UUID scheduleGroupId) {
		Snapshot snapshot = snapshot(scheduleGroupId);
		List<PersonWorkload> rows = snapshot.tallies.values().stream()
			.map(Tally::toRow)
			.sorted(Comparator.comparing((PersonWorkload row) -> nullToEmpty(row.department()), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(PersonWorkload::name, String.CASE_INSENSITIVE_ORDER))
			.toList();
		int sessions = rows.stream().mapToInt(PersonWorkload::sessions).sum();
		BigDecimal hours = rows.stream().map(PersonWorkload::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new WorkloadReport(snapshot.group.getId(), snapshot.group.getName(), rows.size(), sessions, hours, rows);
	}

	/** The workload report as CSV for Excel (finance). */
	public String workloadCsv(UUID scheduleGroupId) {
		WorkloadReport report = workload(scheduleGroupId);
		StringBuilder csv = new StringBuilder(CsvExport.BOM);
		csv.append(CsvExport.row("Exam period", report.scheduleGroupName()));
		csv.append(CsvExport.row("Name", "Email", "Department", "Role", "Sessions", "Hours", "Rooms as chief", "Rooms as invigilator",
			"Days", "First duty", "Last duty", "Active"));
		for (PersonWorkload row : report.people()) {
			csv.append(CsvExport.row(row.name(), row.email(), row.department(), roleName(row.role()), String.valueOf(row.sessions()),
				row.hours().toPlainString(), String.valueOf(row.roomsAsChief()), String.valueOf(row.roomsAsInvigilator()),
				String.valueOf(row.days()), dateOrEmpty(row.firstDuty()), dateOrEmpty(row.lastDuty()), row.active() ? "Yes" : "No"));
		}
		csv.append(CsvExport.row("Total", "", "", "", String.valueOf(report.sessions()), report.hours().toPlainString()));
		return csv.toString();
	}

	public String scheduleGroupName(UUID scheduleGroupId) {
		return scheduleGroupSupport.requireGroup(scheduleGroupId).getName();
	}

	// ─────────────────────────── Analytics ───────────────────────────

	/**
	 * @param score   100 = everybody of this role has the same hours, 0 = all hours on one person
	 *                (100 × (1 − Gini coefficient)); null when nobody of this role has duties yet
	 */
	public record Fairness(String role, int staff, int withoutDuty, BigDecimal minHours, BigDecimal averageHours, BigDecimal maxHours,
		Integer score) {
	}

	public record DepartmentLoad(String department, int staff, int sessions, BigDecimal hours, BigDecimal hoursPerPerson,
		BigDecimal shareOfHours) {
	}

	public record CoverageGap(UUID assignmentId, LocalDate examDate, String slot, String room, String subject, int requiredInvigilators,
		int assignedInvigilators, boolean chiefMissing) {
	}

	public record Coverage(int examRooms, int fullyStaffed, int withGaps, int missingInvigilators, int missingChiefs, List<CoverageGap> gaps) {
	}

	public record Analytics(UUID scheduleGroupId, String scheduleGroupName, List<Fairness> fairness, List<DepartmentLoad> departments,
		Coverage coverage) {
	}

	public Analytics analytics(UUID scheduleGroupId) {
		Snapshot snapshot = snapshot(scheduleGroupId);
		List<Tally> staff = new ArrayList<>(snapshot.tallies.values());

		List<Fairness> fairness = new ArrayList<>();
		for (PersonRole role : PersonRole.values()) {
			List<Tally> ofRole = staff.stream().filter(t -> t.person.getRole() == role && (t.person.isActive() || t.minutes > 0)).toList();
			fairness.add(fairness(role.name(), ofRole));
		}

		long totalMinutes = staff.stream().mapToLong(t -> t.minutes).sum();
		Map<String, List<Tally>> byDepartment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (Tally tally : staff) {
			String department = tally.person.getDepartment() == null || tally.person.getDepartment().isBlank() ? "(no department)" : tally.person.getDepartment().trim();
			byDepartment.computeIfAbsent(department, key -> new ArrayList<>()).add(tally);
		}
		List<DepartmentLoad> departments = new ArrayList<>();
		byDepartment.forEach((department, members) -> {
			long minutes = members.stream().mapToLong(t -> t.minutes).sum();
			departments.add(new DepartmentLoad(department, members.size(), members.stream().mapToInt(t -> t.sittings.size()).sum(),
				hours(minutes), hours(members.isEmpty() ? 0 : minutes / (double) members.size()),
				totalMinutes == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(minutes * 100.0 / totalMinutes).setScale(1, RoundingMode.HALF_UP)));
		});
		departments.sort(Comparator.comparing(DepartmentLoad::hours).reversed().thenComparing(DepartmentLoad::department));

		return new Analytics(snapshot.group.getId(), snapshot.group.getName(), fairness, departments, coverage(snapshot.rooms));
	}

	private Coverage coverage(List<RoomAssignment> rooms) {
		SchedulingSettings settings = contextLoader.settings();
		List<CoverageGap> gaps = new ArrayList<>();
		int missingInvigilators = 0;
		int missingChiefs = 0;
		for (RoomAssignment room : rooms) {
			int required = room.getRoom() == null ? 0 : settings.requiredInvigilators(room.getRoom(), room.getStudentCount());
			int assigned = (int) room.getInvigilatorAssignments().stream().filter(ia -> ia.getInvigilator() != null).count();
			boolean chiefMissing = room.getChiefInvigilator() == null;
			int missing = Math.max(required - assigned, 0);
			if (missing > 0 || chiefMissing) {
				missingInvigilators += missing;
				missingChiefs += chiefMissing ? 1 : 0;
				TimeSlot slot = room.getTimeSlot();
				gaps.add(new CoverageGap(room.getId(), room.getExamDate(),
					slot == null ? "" : nullToEmpty(slot.getLabel()) + " " + slot.getStartTime() + "–" + slot.getEndTime(),
					room.getRoom() == null ? "" : room.getRoom().getName(),
					subject(room), required, assigned, chiefMissing));
			}
		}
		gaps.sort(Comparator.comparing(CoverageGap::examDate, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(CoverageGap::slot).thenComparing(CoverageGap::room));
		return new Coverage(rooms.size(), rooms.size() - gaps.size(), gaps.size(), missingInvigilators, missingChiefs, gaps);
	}

	private static Fairness fairness(String role, List<Tally> staff) {
		if (staff.isEmpty()) {
			return new Fairness(role, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
		}
		long[] minutes = staff.stream().mapToLong(t -> t.minutes).sorted().toArray();
		long total = 0;
		for (long value : minutes) {
			total += value;
		}
		Integer score = total == 0 ? null : (int) Math.round(100 * (1 - gini(minutes, total)));
		int withoutDuty = (int) staff.stream().filter(t -> t.minutes == 0).count();
		return new Fairness(role, staff.size(), withoutDuty, hours(minutes[0]), hours(total / (double) minutes.length),
			hours(minutes[minutes.length - 1]), score);
	}

	/** Gini coefficient of sorted, non-negative values: 0 = perfectly even, close to 1 = all on one person. */
	static double gini(long[] sortedValues, long total) {
		int n = sortedValues.length;
		if (n == 0 || total == 0) {
			return 0;
		}
		double weighted = 0;
		for (int i = 0; i < n; i++) {
			weighted += (i + 1) * (double) sortedValues[i];
		}
		return Math.max(0, (2 * weighted) / (n * (double) total) - (n + 1) / (double) n);
	}

	// ─────────────────────────── Counting ───────────────────────────

	private record Snapshot(ScheduleGroup group, List<RoomAssignment> rooms, Map<UUID, Tally> tallies) {
	}

	private Snapshot snapshot(UUID scheduleGroupId) {
		// requireGroup is filtered to the user's university: another university's period answers 404.
		ScheduleGroup group = scheduleGroupSupport.requireGroup(scheduleGroupId);
		List<RoomAssignment> rooms = assignments.findAllDetailedByScheduleGroupId(group.getId());
		Map<UUID, Tally> tallies = new LinkedHashMap<>();
		for (Person person : people.findAll()) {
			if (person.isActive()) {
				tallies.put(person.getId(), new Tally(person));
			}
		}
		for (RoomAssignment room : rooms) {
			if (room.getChiefInvigilator() != null) {
				tallies.computeIfAbsent(room.getChiefInvigilator().getId(), id -> new Tally(room.getChiefInvigilator())).add(room, true);
			}
			for (InvigilatorAssignment seat : room.getInvigilatorAssignments()) {
				if (seat.getInvigilator() != null) {
					tallies.computeIfAbsent(seat.getInvigilator().getId(), id -> new Tally(seat.getInvigilator())).add(room, false);
				}
			}
		}
		return new Snapshot(group, rooms, tallies);
	}

	private static final class Tally {
		private final Person person;
		private final Set<String> sittings = new HashSet<>();
		private final Set<LocalDate> days = new HashSet<>();
		private long minutes;
		private int roomsAsChief;
		private int roomsAsInvigilator;
		private LocalDate first;
		private LocalDate last;

		private Tally(Person person) {
			this.person = person;
		}

		private void add(RoomAssignment room, boolean chief) {
			if (chief) {
				roomsAsChief++;
			} else {
				roomsAsInvigilator++;
			}
			TimeSlot slot = room.getTimeSlot();
			LocalDate date = room.getExamDate();
			if (slot == null || date == null) {
				return;
			}
			LocalTime start = slot.getStartTime();
			LocalTime end = slot.getEndTime();
			// One sitting counts once, even with two rooms at the same time.
			if (sittings.add(date + "@" + start + "-" + end)) {
				minutes += Math.max(Duration.between(start, end).toMinutes(), 0);
			}
			days.add(date);
			first = first == null || date.isBefore(first) ? date : first;
			last = last == null || date.isAfter(last) ? date : last;
		}

		private PersonWorkload toRow() {
			return new PersonWorkload(person.getId(), person.getName(), person.getEmail(), person.getDepartment(),
				person.getRole() == null ? null : person.getRole().name(), person.isActive(), sittings.size(), hours(minutes),
				roomsAsChief, roomsAsInvigilator, days.size(), first, last);
		}
	}

	private static BigDecimal hours(double minutes) {
		return BigDecimal.valueOf(minutes / 60.0).setScale(2, RoundingMode.HALF_UP);
	}

	private static String subject(RoomAssignment room) {
		String name = nullToEmpty(room.getSubjectName());
		String code = nullToEmpty(room.getSubjectCode());
		if (name.isBlank()) {
			return code;
		}
		return code.isBlank() ? name : name + " (" + code + ")";
	}

	private static String roleName(String role) {
		if (role == null) {
			return "";
		}
		return PersonRole.CHIEF_INVIGILATOR.name().equals(role) ? "Chief invigilator" : "Invigilator";
	}

	private static String dateOrEmpty(LocalDate date) {
		return date == null ? "" : date.toString();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
