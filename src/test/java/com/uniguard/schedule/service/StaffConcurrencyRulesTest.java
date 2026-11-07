package com.uniguard.schedule.service;

import org.junit.jupiter.api.Test;
import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.PersonUnavailability;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.ScheduleGroup;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.entity.enums.WeekDay;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests of the conflict rules — no Spring, no database. */
class StaffConcurrencyRulesTest {

	private static final LocalDate DAY = LocalDate.of(2027, 1, 10); // Sunday

	private final ScheduleGroup groupA = group("Undergraduate");
	private final ScheduleGroup groupB = group("Postgraduate");

	@Test
	void slotsOverlapOnlyWhenTimesIntersect() {
		assertThat(StaffConcurrencyRules.overlaps(slot(groupA, 9, 12), slot(groupA, 11, 13))).isTrue();
		assertThat(StaffConcurrencyRules.overlaps(slot(groupA, 9, 12), slot(groupA, 12, 14))).isFalse(); // back-to-back
		assertThat(StaffConcurrencyRules.overlaps(slot(groupA, 9, 12), slot(groupA, 9, 12))).isTrue();
	}

	@Test
	void invigilatorCannotBeInTwoScheduleGroupsAtOverlappingTimes() {
		Person ali = invigilator("Ali");
		RoomAssignment inGroupB = assignment(groupB, slot(groupB, 10, 13), room("B-101"), null, ali);
		RoomAssignment inGroupA = assignment(groupA, slot(groupA, 9, 12), room("A-201"), null, ali);

		SchedulingContext context = context(List.of(inGroupB, inGroupA));

		assertThat(StaffConcurrencyRules.findViolations(inGroupA, context))
			.singleElement().asString().contains("Ali").contains("overlapping");
	}

	@Test
	void roomCannotBeBookedTwiceAtOverlappingTimesEvenWithDifferentSlots() {
		Room hall = room("Main Hall");
		RoomAssignment morning = assignment(groupA, slot(groupA, 8, 11), hall, null);
		RoomAssignment midMorning = assignment(groupA, slot(groupA, 9, 12), hall, null);

		SchedulingContext context = context(List.of(morning, midMorning));

		assertThat(StaffConcurrencyRules.findViolations(midMorning, context))
			.singleElement().asString().contains("Main Hall").contains("08:00–11:00");
	}

	@Test
	void sameRoomBackToBackIsAllowed() {
		Room hall = room("Main Hall");
		RoomAssignment first = assignment(groupA, slot(groupA, 8, 11), hall, null);
		RoomAssignment second = assignment(groupA, slot(groupA, 11, 14), hall, null);

		assertThat(StaffConcurrencyRules.findViolations(second, context(List.of(first, second)))).isEmpty();
	}

	@Test
	void chiefMayCoverUpToTwoParallelRoomsButNotThree() {
		Person chief = chief("Dr. Sara");
		TimeSlot slot = slot(groupA, 9, 12);
		RoomAssignment r1 = assignment(groupA, slot, room("R1"), chief);
		RoomAssignment r2 = assignment(groupA, slot, room("R2"), chief);
		RoomAssignment r3 = assignment(groupA, slot, room("R3"), chief);

		assertThat(StaffConcurrencyRules.findViolations(r2, context(List.of(r1, r2)))).isEmpty();
		assertThat(StaffConcurrencyRules.findViolations(r3, context(List.of(r1, r2, r3))))
			.singleElement().asString().contains("maximum number of rooms");
	}

	@Test
	void weeklyDayOffBlocksAssignment() {
		Person omar = invigilator("Omar");
		omar.setAvailableDays(EnumSet.of(WeekDay.MON));
		RoomAssignment ra = assignment(groupA, slot(groupA, 9, 12), room("R1"), null, omar);

		assertThat(StaffConcurrencyRules.findViolations(ra, context(List.of(ra))))
			.singleElement().asString().contains("unavailable on Sun");
	}

	@Test
	void dateSpecificLeaveBlocksAssignment() {
		Person mona = invigilator("Mona");
		RoomAssignment ra = assignment(groupA, slot(groupA, 9, 12), room("R1"), null, mona);
		PersonUnavailability leave = PersonUnavailability.builder()
			.person(mona).startDate(DAY.minusDays(1)).endDate(DAY.plusDays(2)).reason("Conference").build();

		SchedulingContext context = new SchedulingContext(List.of(ra), Map.of(mona.getId(), List.of(leave)), 0);

		assertThat(StaffConcurrencyRules.findViolations(ra, context))
			.singleElement().asString().contains("on leave").contains("Conference");
		assertThat(StaffConcurrencyRules.isAvailable(mona, DAY.plusDays(3), context)).isTrue();
	}

	@Test
	void dailyLimitCountsSeparateSessionsAcrossGroups() {
		Person hana = invigilator("Hana");
		RoomAssignment morningB = assignment(groupB, slot(groupB, 8, 10), room("B1"), null, hana);
		RoomAssignment noonA = assignment(groupA, slot(groupA, 11, 13), room("A1"), null, hana);
		RoomAssignment afternoonA = assignment(groupA, slot(groupA, 14, 16), room("A2"), null, hana);
		List<RoomAssignment> universe = List.of(morningB, noonA, afternoonA);

		assertThat(StaffConcurrencyRules.findViolations(afternoonA, new SchedulingContext(universe, Map.of(), 3))).isEmpty();
		assertThat(StaffConcurrencyRules.findViolations(afternoonA, new SchedulingContext(universe, Map.of(), 2)))
			.singleElement().asString().contains("limit of 2");
		assertThat(StaffConcurrencyRules.findViolations(afternoonA, new SchedulingContext(universe, Map.of(), 0))).isEmpty();
	}

	@Test
	void workloadTrackerCountsMinutesAndParallelRoomsOnce() {
		Person chief = chief("Dr. Sara");
		TimeSlot threeHours = slot(groupA, 9, 12);
		WorkloadTracker tracker = new WorkloadTracker();
		tracker.recordAll(assignment(groupA, threeHours, room("R1"), chief));
		tracker.recordAll(assignment(groupA, threeHours, room("R2"), chief)); // same sitting
		tracker.recordAll(assignment(groupA, slot(groupA, 13, 14), room("R1"), chief));

		assertThat(tracker.minutesFor(chief.getId())).isEqualTo(180 + 60);
	}

	@Test
	void genderSeparatedHallNeedsStaffOfThatGender() {
		Room femaleHall = room("Female Hall");
		femaleHall.setHallGender(com.uniguard.schedule.entity.enums.HallGender.FEMALE);
		Person ali = invigilator("Ali");
		ali.setGender(com.uniguard.schedule.entity.enums.Gender.MALE);
		Person unknown = invigilator("No gender set");
		Person mona = invigilator("Mona");
		mona.setGender(com.uniguard.schedule.entity.enums.Gender.FEMALE);

		RoomAssignment ra = assignment(groupA, slot(groupA, 9, 12), femaleHall, null);
		SchedulingContext context = context(List.of(ra));

		assertThat(StaffConcurrencyRules.canTakeSeat(ali, false, ra, context)).isFalse();
		assertThat(StaffConcurrencyRules.canTakeSeat(unknown, false, ra, context)).isFalse();
		assertThat(StaffConcurrencyRules.canTakeSeat(mona, false, ra, context)).isTrue();
		assertThat(StaffConcurrencyRules.canTakeSeat(ali, false, assignment(groupA, slot(groupA, 9, 12), room("Mixed"), null), context))
			.isTrue();
	}

	@Test
	void teacherCannotInvigilateTheirOwnSubject() {
		Person sara = invigilator("Sara");
		sara.setSubjectCodes(java.util.Set.of("math101 "));
		RoomAssignment maths = assignment(groupA, slot(groupA, 9, 12), room("R1"), null, sara);
		maths.setSubjectCode("MATH101");

		assertThat(StaffConcurrencyRules.findViolations(maths, context(List.of(maths))))
			.singleElement().asString().contains("teaches MATH101");

		maths.setSubjectCode("PHYS101");
		assertThat(StaffConcurrencyRules.findViolations(maths, context(List.of(maths)))).isEmpty();
	}

	@Test
	void minimumRestBetweenSessionsIsEnforced() {
		Person omar = invigilator("Omar");
		RoomAssignment morning = assignment(groupA, slot(groupA, 9, 11), room("R1"), null, omar);
		RoomAssignment noon = assignment(groupA, slot(groupA, 11, 13), room("R2"), null, omar);    // 0 min gap
		List<RoomAssignment> universe = List.of(morning, noon);

		SchedulingContext oneHourRest = new SchedulingContext(universe, Map.of(), new SchedulingSettings(0, 0, 60, 0));
		assertThat(StaffConcurrencyRules.findViolations(noon, oneHourRest))
			.singleElement().asString().contains("60 minutes of rest");

		RoomAssignment afternoon = assignment(groupA, slot(groupA, 12, 14), room("R3"), null, omar); // 60 min after 11:00
		SchedulingContext ok = new SchedulingContext(List.of(morning, afternoon), Map.of(), new SchedulingSettings(0, 0, 60, 0));
		assertThat(StaffConcurrencyRules.findViolations(afternoon, ok)).isEmpty();
	}

	@Test
	void maxDutiesPerExamPeriodCountsAllDatesOfTheGroup() {
		Person hana = invigilator("Hana");
		RoomAssignment day1 = assignment(groupA, slot(groupA, 9, 12), room("R1"), null, hana);
		RoomAssignment day2 = assignment(groupA, slot(groupA, 9, 12), room("R1"), null, hana);
		day2.setExamDate(DAY.plusDays(1));
		RoomAssignment day3 = assignment(groupA, slot(groupA, 9, 12), room("R1"), null, hana);
		day3.setExamDate(DAY.plusDays(2));
		RoomAssignment otherGroup = assignment(groupB, slot(groupB, 14, 16), room("B1"), null, hana);
		List<RoomAssignment> universe = List.of(day1, day2, day3, otherGroup);

		SchedulingContext limitTwo = new SchedulingContext(universe, Map.of(), new SchedulingSettings(0, 2, 0, 0));
		assertThat(StaffConcurrencyRules.findViolations(day3, limitTwo))
			.singleElement().asString().contains("limit of 2");

		SchedulingContext limitThree = new SchedulingContext(universe, Map.of(), new SchedulingSettings(0, 3, 0, 0));
		assertThat(StaffConcurrencyRules.findViolations(day3, limitThree)).isEmpty(); // other group not counted
	}

	@Test
	void requiredInvigilatorsFollowStudentCount() {
		Room hall = room("Hall");
		hall.setMinInvigilators(1);
		SchedulingSettings onePer30 = new SchedulingSettings(0, 0, 0, 30);

		assertThat(onePer30.requiredInvigilators(hall, null)).isEqualTo(1);
		assertThat(onePer30.requiredInvigilators(hall, 30)).isEqualTo(1);
		assertThat(onePer30.requiredInvigilators(hall, 31)).isEqualTo(2);
		assertThat(onePer30.requiredInvigilators(hall, 95)).isEqualTo(4);
		assertThat(SchedulingSettings.NONE.requiredInvigilators(hall, 95)).isEqualTo(1);
	}

	// ── builders ────────────────────────────────────────────────────────────

	private static SchedulingContext context(List<RoomAssignment> universe) {
		return new SchedulingContext(universe, Map.of(), 0);
	}

	private static ScheduleGroup group(String name) {
		return ScheduleGroup.builder().id(UUID.randomUUID()).name(name).build();
	}

	private static TimeSlot slot(ScheduleGroup group, int startHour, int endHour) {
		return TimeSlot.builder()
			.id(UUID.randomUUID())
			.startTime(LocalTime.of(startHour, 0))
			.endTime(LocalTime.of(endHour, 0))
			.build();
	}

	private static Room room(String name) {
		Room room = new Room();
		room.setId(UUID.randomUUID());
		room.setName(name);
		room.setMinInvigilators(1);
		return room;
	}

	private static Person person(String name, PersonRole role) {
		Person person = new Person();
		person.setId(UUID.randomUUID());
		person.setName(name);
		person.setRole(role);
		person.setAvailableDays(EnumSet.allOf(WeekDay.class));
		return person;
	}

	private static Person chief(String name) {
		return person(name, PersonRole.CHIEF_INVIGILATOR);
	}

	private static Person invigilator(String name) {
		return person(name, PersonRole.INVIGILATOR);
	}

	private static RoomAssignment assignment(ScheduleGroup group, TimeSlot slot, Room room, Person chief, Person... invigilators) {
		RoomAssignment ra = new RoomAssignment();
		ra.setScheduleGroup(group);
		ra.setTimeSlot(slot);
		ra.setRoom(room);
		ra.setExamDate(DAY);
		ra.setChiefInvigilator(chief);
		for (int i = 0; i < invigilators.length; i++) {
			InvigilatorAssignment seat = new InvigilatorAssignment();
			seat.setPositionIndex(i);
			seat.setInvigilator(invigilators[i]);
			ra.addInvigilatorAssignment(seat);
		}
		return ra;
	}
}
