package schedule.example.schedule.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.dto.assignment.BulkRoomAssignmentRequest;
import schedule.example.schedule.dto.assignment.GeneratePreviewRequest;
import schedule.example.schedule.dto.assignment.GeneratePreviewResponse;
import schedule.example.schedule.dto.assignment.PreviewAssignment;
import schedule.example.schedule.dto.assignment.RoomAssignmentRequest;
import schedule.example.schedule.dto.timeslot.TimeSlotRequest;
import schedule.example.schedule.dto.unavailability.UnavailabilityRequest;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.ScheduleGroup;
import schedule.example.schedule.entity.TimeSlot;
import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.entity.enums.WeekDay;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.PersonUnavailabilityRepository;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.repository.ScheduleGroupRepository;
import schedule.example.schedule.repository.SettingsRepository;
import schedule.example.schedule.repository.TimeSlotRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end scheduling rules against a real (H2, MySQL-mode) database through the real services.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchedulingIntegrationTest {

	private static final LocalDate DAY = LocalDate.of(2027, 1, 10);

	@Autowired private AssignmentService assignmentService;
	@Autowired private TimeSlotService timeSlotService;
	@Autowired private UnavailabilityService unavailabilityService;
	@Autowired private RoomAssignmentRepository roomAssignmentRepository;
	@Autowired private PersonRepository personRepository;
	@Autowired private PersonUnavailabilityRepository unavailabilityRepository;
	@Autowired private RoomRepository roomRepository;
	@Autowired private TimeSlotRepository timeSlotRepository;
	@Autowired private ScheduleGroupRepository scheduleGroupRepository;
	@Autowired private SettingsRepository settingsRepository;
	@Autowired private MockMvc mockMvc;
	@Autowired private PeopleService peopleService;
	@Autowired private AutoAssignmentService autoAssignmentService;

	@Test
	void previewUsesMatchingToStaffAGenderSeparatedHall() {
		ScheduleGroup g = group("Halls");
		TimeSlot slot = slot(g, 9, 12);
		Room mixed = room("A mixed");
		Room femaleHall = room("B female");
		femaleHall.setHallGender(schedule.example.schedule.entity.enums.HallGender.FEMALE);
		roomRepository.save(femaleHall);
		Person femaleChief = chief("Chief F");
		femaleChief.setGender(schedule.example.schedule.entity.enums.Gender.FEMALE);
		personRepository.save(femaleChief);
		Person aisha = invigilator("Aisha");   // first by name, the only woman
		aisha.setGender(schedule.example.schedule.entity.enums.Gender.FEMALE);
		personRepository.save(aisha);
		Person bob = invigilator("Bob");
		bob.setGender(schedule.example.schedule.entity.enums.Gender.MALE);
		personRepository.save(bob);

		GeneratePreviewResponse preview = assignmentService.generatePreview(new GeneratePreviewRequest(
			g.getId(), DAY, slot.getId(), List.of(mixed.getId(), femaleHall.getId()), false, List.of()));

		assertThat(preview.conflicts()).isEmpty();
		assertThat(preview.assignments()).filteredOn(a -> a.roomId().equals(femaleHall.getId()))
			.singleElement().satisfies(a -> assertThat(a.invigilatorIds()).containsExactly(aisha.getId()));
		assertThat(preview.assignments()).filteredOn(a -> a.roomId().equals(mixed.getId()))
			.singleElement().satisfies(a -> assertThat(a.invigilatorIds()).containsExactly(bob.getId()));
	}

	@Test
	void excelUploadPathCreatesAndFillsSeatsFromStudentCount() {
		setStudentsPerInvigilator(30);
		ScheduleGroup g = group("Excel");
		TimeSlot slot = slot(g, 9, 12);
		Room hall = room("Big hall");
		hall.setCapacity(100);
		roomRepository.save(hall);
		chief("C1");
		for (String name : List.of("I1", "I2", "I3", "I4")) {
			invigilator(name);
		}
		schedule.example.schedule.entity.RoomAssignment uploaded = new schedule.example.schedule.entity.RoomAssignment();
		uploaded.setScheduleGroup(g);
		uploaded.setTimeSlot(slot);
		uploaded.setRoom(hall);
		uploaded.setExamDate(DAY);
		uploaded.setStudentCount(70); // needs ceil(70 / 30) = 3 invigilators
		uploaded = roomAssignmentRepository.save(uploaded);

		AutoAssignmentService.StaffingResult result = autoAssignmentService.assignStaff(g.getId(), List.of(uploaded));

		assertThat(result.invigilatorSlotsTotal()).isEqualTo(3);
		assertThat(result.invigilatorSlotsFilled()).isEqualTo(3);
		var saved = roomAssignmentRepository.findDetailedById(uploaded.getId()).orElseThrow();
		assertThat(saved.getInvigilatorAssignments()).hasSize(3).allSatisfy(seat -> assertThat(seat.getInvigilator()).isNotNull());
		assertThat(saved.getChiefInvigilator()).isNotNull();
	}

	@Test
	void studentCountAboveRoomCapacityIsRejected() {
		ScheduleGroup g = group("Capacity");
		Room small = room("Small");
		assertThatThrownBy(() -> assignmentService.createAssignment(new RoomAssignmentRequest(
				g.getId(), DAY, small.getId(), slot(g, 9, 12).getId(), null, null, null, false, null, List.of(), 31)))
			.isInstanceOf(schedule.example.schedule.exception.ValidationException.class)
			.hasMessageContaining("holds 30 students");
	}

	private void setStudentsPerInvigilator(int value) {
		settingsRepository.findById(ApplicationDefaults.DEFAULT_SETTINGS_ID).ifPresent(settings -> {
			settings.setStudentsPerInvigilator(value);
			settingsRepository.save(settings);
		});
	}

	private final List<UUID> createdGroups = new ArrayList<>();

	@Test
	void chiefCreatedThroughTheApiMayCoverTwoParallelRooms() {
		var created = peopleService.createPerson(new schedule.example.schedule.dto.person.PersonRequest(
			"API Chief", "Exams", null, PersonRole.CHIEF_INVIGILATOR, EnumSet.allOf(WeekDay.class), null, null));
		assertThat(created.maxParallelRooms()).isEqualTo(2);

		ScheduleGroup g = group("Parallel");
		TimeSlot slot = slot(g, 9, 12);
		Person chief = personRepository.findById(created.id()).orElseThrow();
		create(g, slot, room("P1"), chief);
		create(g, slot, room("P2"), chief); // second parallel room must be allowed
		assertThat(roomAssignmentRepository.count()).isEqualTo(2);
	}

	@AfterEach
	void cleanUp() {
		roomAssignmentRepository.deleteAll();
		unavailabilityRepository.deleteAll();
		timeSlotRepository.deleteAll();
		personRepository.deleteAll();
		roomRepository.deleteAll();
		createdGroups.forEach(scheduleGroupRepository::deleteById);
		createdGroups.clear();
		setMaxDutiesPerDay(0);
		setStudentsPerInvigilator(0);
	}

	// ── Cross-group & room conflicts ────────────────────────────────────────

	@Test
	void personBusyInAnotherScheduleGroupCannotBeDoubleBooked() {
		ScheduleGroup undergrad = group("Undergrad");
		ScheduleGroup postgrad = group("Postgrad");
		Person ali = invigilator("Ali");
		Person chiefA = chief("Chief A");
		Person chiefB = chief("Chief B");

		create(postgrad, slot(postgrad, 10, 13), room("PG-1"), chiefB, ali);

		assertThatThrownBy(() -> create(undergrad, slot(undergrad, 9, 12), room("UG-1"), chiefA, ali))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("Ali");
	}

	@Test
	void roomCannotHostOverlappingExamsAcrossSlots() {
		ScheduleGroup g = group("Main");
		Room hall = room("Hall");
		create(g, slot(g, 8, 11), hall, chief("C1"));

		assertThatThrownBy(() -> create(g, slot(g, 9, 12), hall, chief("C2")))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("Hall");
	}

	@Test
	void concurrentConflictingSavesAreSerialisedByTheScheduleLock() throws Exception {
		ScheduleGroup g = group("Concurrent");
		TimeSlot slot = slot(g, 9, 12);
		Person shared = invigilator("Shared");
		Room r1 = room("R1");
		Room r2 = room("R2");
		Person c1 = chief("C1");
		Person c2 = chief("C2");

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		List<Future<?>> futures = new ArrayList<>();
		for (Object[] job : List.of(new Object[]{r1, c1}, new Object[]{r2, c2})) {
			futures.add(pool.submit(() -> {
				start.await();
				try {
					create(g, slot, (Room) job[0], (Person) job[1], shared);
					successes.incrementAndGet();
				} catch (ConflictException expected) {
					conflicts.incrementAndGet();
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> future : futures) {
			future.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(successes.get()).isEqualTo(1);
		assertThat(conflicts.get()).isEqualTo(1);
		assertThat(roomAssignmentRepository.count()).isEqualTo(1);
	}

	@Test
	void changingSlotTimesIsRejectedWhenItCreatesAnOverlap() {
		ScheduleGroup g = group("Slots");
		Room hall = room("Hall");
		TimeSlot morning = slot(g, 8, 10);
		create(g, morning, hall, chief("C1"));
		create(g, slot(g, 10, 12), hall, chief("C2"));

		TimeSlotRequest stretch = new TimeSlotRequest(g.getId(), morning.getLabel(), LocalTime.of(8, 0), LocalTime.of(11, 0), morning.getSortOrder());
		assertThatThrownBy(() -> timeSlotService.updateTimeSlot(morning.getId(), stretch))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("Hall");
		assertThat(timeSlotRepository.findById(morning.getId()).orElseThrow().getEndTime()).isEqualTo(LocalTime.of(10, 0));
	}

	// ── Locked assignments ──────────────────────────────────────────────────

	@Test
	void bulkSaveRefusesToSilentlyDeleteLockedRooms() {
		ScheduleGroup g = group("Locks");
		TimeSlot slot = slot(g, 9, 12);
		Room locked = room("Locked room");
		Room other = room("Other room");
		Person c1 = chief("C1");
		assignmentService.saveAssignmentsBulk(List.of(
			bulk(g, slot, locked, c1, true),
			bulk(g, slot, other, c1, false)));

		assertThatThrownBy(() -> assignmentService.saveAssignmentsBulk(List.of(bulk(g, slot, other, c1, false))))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("Locked room");
		assertThat(roomAssignmentRepository.count()).isEqualTo(2);
	}

	// ── Leave & daily limit ─────────────────────────────────────────────────

	@Test
	void leaveBlocksNewAssignmentsAndCannotOverlapExistingDuties() {
		ScheduleGroup g = group("Leave");
		Person mona = invigilator("Mona");
		Person omar = invigilator("Omar");
		create(g, slot(g, 9, 12), room("R1"), chief("C1"), omar);

		unavailabilityService.create(mona.getId(), new UnavailabilityRequest(DAY, DAY, "Conference"));
		assertThatThrownBy(() -> create(g, slot(g, 13, 15), room("R2"), chief("C2"), mona))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("on leave");

		assertThatThrownBy(() -> unavailabilityService.create(omar.getId(), new UnavailabilityRequest(DAY.minusDays(1), DAY, null)))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining(DAY.toString());
	}

	@Test
	void dailyDutyLimitIsEnforced() {
		setMaxDutiesPerDay(1);
		ScheduleGroup g = group("Limit");
		Person hana = invigilator("Hana");
		create(g, slot(g, 8, 10), room("R1"), chief("C1"), hana);

		assertThatThrownBy(() -> create(g, slot(g, 13, 15), room("R2"), chief("C2"), hana))
			.isInstanceOf(ConflictException.class)
			.hasMessageContaining("limit of 1");
	}

	// ── Generation preview (single engine) ──────────────────────────────────

	@Test
	void previewSkipsPeopleBusyInOtherGroupsAndPrefersLowerWorkload() {
		ScheduleGroup undergrad = group("UG");
		ScheduleGroup postgrad = group("PG");
		Person busyElsewhere = invigilator("Aaron");   // alphabetically first, but busy
		Person loaded = invigilator("Badr");           // already worked 3h this period
		Person fresh = invigilator("Zaid");            // no duties yet
		Person chief = chief("Chief");

		Person pgChief = chief("PG chief");
		create(postgrad, slot(postgrad, 9, 12), room("PG-1"), pgChief, busyElsewhere);
		create(undergrad, slot(undergrad, 13, 16), room("UG-0"), chief, loaded);

		TimeSlot morning = slot(undergrad, 9, 12);
		Room target = room("UG-1");
		long before = roomAssignmentRepository.count();

		GeneratePreviewResponse preview = assignmentService.generatePreview(new GeneratePreviewRequest(
			undergrad.getId(), DAY, morning.getId(), List.of(target.getId()), false, List.of()));

		PreviewAssignment result = preview.assignments().getFirst();
		assertThat(result.invigilatorIds()).containsExactly(fresh.getId());
		// Chiefs may cover two parallel rooms, and fairness only counts minutes in THIS exam period:
		// "PG chief" (0 min in UG) beats "Chief" (180 min in UG).
		assertThat(result.chiefInvigilatorId()).isEqualTo(pgChief.getId());
		assertThat(roomAssignmentRepository.count()).as("preview must not persist").isEqualTo(before);
	}

	@Test
	void previewKeepsLockedRoomsUntouched() {
		ScheduleGroup g = group("Keep");
		TimeSlot slot = slot(g, 9, 12);
		Room lockedRoom = room("Locked");
		Room open = room("Open");
		Person keptChief = chief("Kept chief");
		Person keptInvigilator = invigilator("Kept inv");
		chief("Other chief");
		invigilator("Other inv");

		PreviewAssignment locked = new PreviewAssignment(lockedRoom.getId(), keptChief.getId(),
			List.of(keptInvigilator.getId()), true, "Math", "M101", null);
		GeneratePreviewResponse preview = assignmentService.generatePreview(new GeneratePreviewRequest(
			g.getId(), DAY, slot.getId(), List.of(lockedRoom.getId(), open.getId()), false, List.of(locked)));

		assertThat(preview.assignments()).hasSize(2);
		assertThat(preview.assignments()).filteredOn(a -> a.roomId().equals(lockedRoom.getId()))
			.singleElement().isEqualTo(locked);
		PreviewAssignment generated = preview.assignments().stream()
			.filter(a -> a.roomId().equals(open.getId())).findFirst().orElseThrow();
		assertThat(generated.invigilatorIds()).doesNotContain(keptInvigilator.getId());
	}

	@Test
	@WithMockUser(roles = "SCHEDULER")
	void previewEndpointAndConflictsAreExposedOverHttp() throws Exception {
		ScheduleGroup g = group("Http");
		TimeSlot slot = slot(g, 9, 12);
		Room r = room("R1");
		chief("C1");
		invigilator("I1");

		mockMvc.perform(post("/api/assignments/generate-preview")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"scheduleGroupId":"%s","examDate":"%s","slotId":"%s","roomIds":["%s"],"regenerateUnlocked":false,"current":[]}
					""".formatted(g.getId(), DAY, slot.getId(), r.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assignments[0].roomId").value(r.getId().toString()))
			.andExpect(jsonPath("$.conflicts").isEmpty());
	}

	// ── Optimistic locking ──────────────────────────────────────────────────

	@Test
	void staleWriteIsRejectedByVersionCheck() {
		Person person = invigilator("Versioned");
		Person staleCopy = personRepository.findById(person.getId()).orElseThrow();

		Person fresh = personRepository.findById(person.getId()).orElseThrow();
		fresh.setDepartment("Physics");
		personRepository.save(fresh);

		staleCopy.setDepartment("Chemistry");
		assertThatThrownBy(() -> personRepository.save(staleCopy))
			.isInstanceOf(ObjectOptimisticLockingFailureException.class);
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private void create(ScheduleGroup group, TimeSlot slot, Room room, Person chief, Person... invigilators) {
		List<UUID> ids = new ArrayList<>();
		for (Person p : invigilators) {
			ids.add(p.getId());
		}
		assignmentService.createAssignment(new RoomAssignmentRequest(
			group.getId(), DAY, room.getId(), slot.getId(), null, null,
			chief == null ? null : chief.getId(), false, null, ids, null));
	}

	private BulkRoomAssignmentRequest bulk(ScheduleGroup group, TimeSlot slot, Room room, Person chief, boolean locked) {
		return new BulkRoomAssignmentRequest(group.getId(), DAY, room.getId(), slot.getId(), null, null,
			chief.getId(), locked, Collections.singletonList(null), null);
	}

	private ScheduleGroup group(String name) {
		ScheduleGroup group = scheduleGroupRepository.save(ScheduleGroup.builder()
			.name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
			.build());
		createdGroups.add(group.getId());
		return group;
	}

	private TimeSlot slot(ScheduleGroup group, int startHour, int endHour) {
		return timeSlotRepository.save(TimeSlot.builder()
			.scheduleGroup(group)
			.label(startHour + "-" + endHour)
			.startTime(LocalTime.of(startHour, 0))
			.endTime(LocalTime.of(endHour, 0))
			.build());
	}

	private Room room(String name) {
		Room room = new Room();
		room.setName(name + "-" + UUID.randomUUID().toString().substring(0, 4));
		room.setCapacity(30);
		room.setType(RoomType.MEDIUM);
		room.setMinInvigilators(1);
		return roomRepository.save(room);
	}

	private Person person(String name, PersonRole role) {
		Person person = new Person();
		person.setName(name);
		person.setDepartment("Exams");
		person.setRole(role);
		person.setAvailableDays(EnumSet.allOf(WeekDay.class));
		return personRepository.save(person);
	}

	private Person chief(String name) {
		return person(name, PersonRole.CHIEF_INVIGILATOR);
	}

	private Person invigilator(String name) {
		return person(name, PersonRole.INVIGILATOR);
	}

	private void setMaxDutiesPerDay(int value) {
		settingsRepository.findById(ApplicationDefaults.DEFAULT_SETTINGS_ID).ifPresent(settings -> {
			settings.setMaxDutiesPerDay(value);
			settingsRepository.save(settings);
		});
	}
}
