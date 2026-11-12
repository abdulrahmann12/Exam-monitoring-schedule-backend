package com.uniguard.schedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;
import com.uniguard.schedule.security.JwtTokenProvider;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.tenant.UniversityProvisioning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Room allocation preview: an exam list without rooms goes in, proposed rooms come out, nothing is
 * saved. Each test runs in its own new university.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomAllocationTest {

	private static final String CSRF = "X-Requested-With";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private UniversityProvisioning provisioning;
	@Autowired private RoomAssignmentRepository assignments;
	@Autowired private TimeSlotRepository slots;
	@Autowired private RoomRepository rooms;

	private final List<String> createdUsers = new ArrayList<>();
	private UUID university;
	private String admin;
	private String viewer;
	private String groupId;

	@BeforeEach
	void setUp() throws Exception {
		university = provisioning.create("Allocation University", "alloc-" + UUID.randomUUID().toString().substring(0, 8)).getId();
		admin = signIn(AdminRole.ADMIN);
		viewer = signIn(AdminRole.VIEWER);
		groupId = objectMapper.readTree(json(post("/api/schedule-groups"), Map.of("name", "Finals")).andReturn().getResponse().getContentAsString())
			.get("id").asText();
		room("Hall 100", 100, true);
		room("Room 60", 60, true);
		room("Room 50", 50, true);
		room("Room 40", 40, true);
		room("Closed 500", 500, false); // out of service (e.g. maintenance): never used
		room("M209 (Lab)", 300, true);  // type "Lab" (guessed from the name): only for exams asking for a Lab
	}

	@AfterEach
	void cleanUp() {
		for (String email : createdUsers) {
			users.findByEmailIgnoreCase(email).ifPresent(user -> {
				sessionService.deleteAllForUser(user.getId());
				users.delete(user);
			});
		}
	}

	@Test
	void proposesRoomsPerSittingAndSavesNothing() throws Exception {
		String csv = """
			Date,Day,Time Slot,Subject,Code,Number of Students
			2027-01-10,Sunday,09:00 - 12:00,Calculus,MATH101,150
			2027-01-10,Sunday,09:00 - 12:00,Physics,PHYS110,35
			2027-01-11,Monday,09:00 - 12:00,Chemistry,CHEM100,90
			""";
		JsonNode result = ok(preview(csv));

		assertThat(result.get("exams").asInt()).isEqualTo(3);
		assertThat(result.get("examsFullySeated").asInt()).isEqualTo(3);
		assertThat(result.get("students").asInt()).isEqualTo(275);
		assertThat(result.get("studentsUnplaced").asInt()).isZero();
		assertThat(result.get("sittings")).hasSize(2);
		assertThat(result.get("timeSlotsToCreate").get(0).asText()).isEqualTo("Exam 09:00–12:00");
		assertThat(result.get("columnsRecognised").toString()).contains("Number of Students → students").contains("Day → day");

		JsonNode first = result.get("sittings").get(0);
		assertThat(first.get("date").asText()).isEqualTo("2027-01-10");
		assertThat(first.get("time").asText()).isEqualTo("09:00–12:00");
		assertThat(first.get("freeRooms").asInt()).isEqualTo(4); // neither the out-of-service room nor the lab
		JsonNode calculus = first.get("exams").get(0);
		assertThat(calculus.get("subjectCode").asText()).isEqualTo("MATH101");
		assertThat(roomNames(calculus)).containsExactly("Hall 100", "Room 50"); // 150 = 100 + 50, no empty seat
		assertThat(calculus.get("rooms").get(1).get("students").asInt()).isEqualTo(50);
		assertThat(roomNames(first.get("exams").get(1))).containsExactly("Room 40"); // 35 → smallest room that fits

		JsonNode chemistry = result.get("sittings").get(1).get("exams").get(0);
		assertThat(roomNames(chemistry)).containsExactly("Hall 100"); // next day: all rooms free again
		assertThat(result.get("roomsUsed").asInt()).isEqualTo(4);
		assertThat(result.get("emptySeats").asInt()).isEqualTo(5 + 10);

		TenantContext.runAs(university, () -> {
			assertThat(assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId))).isEmpty();
			assertThat(slots.findByActiveTrueOrderBySortOrderAsc()).noneMatch(slot -> slot.getLabel().equals("Exam 09:00–12:00"));
		});
	}

	@Test
	void roomsBookedAtAnOverlappingTimeAreNotOffered() throws Exception {
		// Book "Hall 100" through the normal timetable import (another exam period would count the same).
		String booked = "Date,Time Slot,Room,Subject Code,Students\n2027-02-01,09:00 - 12:00,Hall 100,BOOKED1,80\n";
		var request = MockMvcRequestBuilders.multipart("/api/schedule-groups/" + groupId + "/bulk-upload")
			.file(new MockMultipartFile("file", "booked.csv", "text/csv", booked.getBytes(StandardCharsets.UTF_8)));
		assertThat(mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest"))
			.andReturn().getResponse().getStatus()).isEqualTo(200);

		// 11:00–13:00 overlaps the 09:00–12:00 booking; 13:00–15:00 starts after it ends.
		String csv = """
			Exam Date,Start Time,End Time,Course Code,Students
			2027-02-01,11:00,13:00,LATE1,90
			2027-02-01,13:00,15:00,AFTER1,90
			""";
		JsonNode result = ok(preview(csv));
		JsonNode late = result.get("sittings").get(0).get("exams").get(0);
		assertThat(roomNames(late)).doesNotContain("Hall 100").containsExactly("Room 50", "Room 40"); // 90 = 50 + 40
		assertThat(result.get("sittings").get(0).get("freeRooms").asInt()).isEqualTo(3);
		JsonNode after = result.get("sittings").get(1).get("exams").get(0);
		assertThat(roomNames(after)).containsExactly("Hall 100"); // free again after 12:00
	}

	@Test
	void overlappingSittingsInTheSameFileDoNotShareRooms() throws Exception {
		String csv = """
			Date,Start Time,End Time,Code,Students
			2027-03-01,09:00,12:00,EARLY,100
			2027-03-01,11:00,13:00,MIDDAY,100
			""";
		JsonNode result = ok(preview(csv));
		Set<String> early = new HashSet<>(roomNames(result.get("sittings").get(0).get("exams").get(0)));
		List<String> midday = roomNames(result.get("sittings").get(1).get("exams").get(0));
		assertThat(early).containsExactly("Hall 100");
		assertThat(midday).doesNotContain("Hall 100").containsExactly("Room 60", "Room 40");
	}

	@Test
	void notEnoughRoomsIsReportedNotFailed() throws Exception {
		String csv = """
			Date,Time Slot,Code,Students
			2027-04-01,09:00 - 12:00,HUGE,300
			2027-04-01,09:00 - 12:00,SMALL,30
			""";
		JsonNode result = ok(preview(csv));
		JsonNode exams = result.get("sittings").get(0).get("exams");
		assertThat(roomNames(exams.get(1))).containsExactly("Room 40"); // the small exam is seated first
		assertThat(exams.get(0).get("unplaced").asInt()).isEqualTo(300 - 210);
		assertThat(result.get("studentsUnplaced").asInt()).isEqualTo(90);
		assertThat(result.get("examsFullySeated").asInt()).isEqualTo(1);
		assertThat(result.get("warnings").get(0).asText()).contains("Row 2").contains("90 of 300 students have no seat");
	}

	@Test
	void wrongDayNameIsOnlyAWarning() throws Exception {
		String csv = "Date,Day,Time Slot,Code,Students\n2027-01-10,Monday,09:00 - 12:00,X1,20\n"; // 10 Jan 2027 is a Sunday
		JsonNode result = ok(preview(csv));
		assertThat(result.get("warnings").get(0).asText()).contains("Row 2").contains("Monday").contains("Sunday");
		assertThat(result.get("sittings").get(0).get("date").asText()).isEqualTo("2027-01-10");
	}

	@Test
	void badRowsAreAllListed() throws Exception {
		String csv = """
			Date,Time Slot,Subject Name,Code,Students
			2027-01-10,09:00 - 12:00,Algebra,MATH1,
			2027-01-10,09:00 - 12:00,Geometry,MATH2,12.5
			2027-01-10,09:00 - 12:00,Biology,BIO1,0
			2027-01-10,09:00 - 12:00,,,10
			2027-01-10,09:00 - 12:00,Physics,PHY1,10
			2027-01-10,09:00 - 12:00,Physics again,phy1,5
			""";
		String body = preview(csv).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertThat(body)
			.contains("Number of students is required")
			.contains("whole number")
			.contains("between 1 and")
			.contains("Subject name or subject code is required")
			.contains("already listed at the same date and time in row 6");
	}

	@Test
	void fileWithoutStudentsColumnIsRefused() throws Exception {
		String body = preview("Date,Time Slot,Code\n2027-01-10,09:00 - 12:00,X1\n")
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertThat(body).contains("number of students column");
	}

	@Test
	void roomTypeIsGuessedFromTheNameAndCanBeChanged() throws Exception {
		JsonNode lab = objectMapper.readTree(json(post("/api/rooms"), Map.of("name", "Chemistry Laboratory", "capacity", 30, "type", "SMALL",
			"minInvigilators", 1)).andReturn().getResponse().getContentAsString());
		assertThat(lab.get("roomCategoryName").asText()).isEqualTo("Lab");
		assertThat(lab.get("roomCategorySpecial").asBoolean()).isTrue();
		assertThat(lab.get("active").asBoolean()).isTrue();
		String id = lab.get("id").asText();

		// Edit without a room type (an older screen): unchanged.
		JsonNode same = objectMapper.readTree(json(MockMvcRequestBuilders.put("/api/rooms/" + id), Map.of("name", "Chemistry Laboratory",
			"capacity", 35, "type", "SMALL", "minInvigilators", 1)).andReturn().getResponse().getContentAsString());
		assertThat(same.get("roomCategoryName").asText()).isEqualTo("Lab");
		assertThat(same.get("capacity").asInt()).isEqualTo(35);

		// The administrator says it is a classroom: now normal exams can get it.
		JsonNode normal = objectMapper.readTree(json(MockMvcRequestBuilders.put("/api/rooms/" + id), Map.of("name", "Chemistry Laboratory",
			"capacity", 35, "type", "SMALL", "minInvigilators", 1, "roomCategoryId", typeId("Classroom"))).andReturn().getResponse().getContentAsString());
		assertThat(normal.get("roomCategoryName").asText()).isEqualTo("Classroom");
		assertThat(normal.get("roomCategorySpecial").asBoolean()).isFalse();
		JsonNode result = ok(preview("Date,Time Slot,Code,Students\n2027-05-01,09:00 - 12:00,CHEM9,35\n"));
		assertThat(roomNames(result.get("sittings").get(0).get("exams").get(0))).containsExactly("Chemistry Laboratory");

		JsonNode plain = objectMapper.readTree(json(post("/api/rooms"), Map.of("name", "Label Hall", "capacity", 30, "type", "SMALL",
			"minInvigilators", 1)).andReturn().getResponse().getContentAsString());
		assertThat(plain.get("roomCategoryName").asText()).isEqualTo("Classroom"); // "Label" is not a lab
	}

	@Test
	void eachUniversityEditsItsOwnRoomTypes() throws Exception {
		JsonNode defaults = readJson(MockMvcRequestBuilders.get("/api/room-categories"));
		List<String> names = new ArrayList<>();
		defaults.forEach(type -> names.add(type.get("name").asText()));
		assertThat(names).containsExactly("Classroom", "Lecture Hall", "Lab", "Computer Lab", "Chemistry Lab", "Physics Lab", "Drawing Studio");
		assertThat(defaults.get(2).get("rooms").asLong()).isEqualTo(1); // "M209 (Lab)"

		JsonNode created = objectMapper.readTree(json(post("/api/room-categories"), Map.of("name", "Language Lab", "special", true))
			.andReturn().getResponse().getContentAsString());
		assertThat(created.get("special").asBoolean()).isTrue();
		assertThat(send(post("/api/room-categories"), Map.of("name", " language  LAB ", "special", false), admin)).isEqualTo(409); // same name
		assertThat(send(MockMvcRequestBuilders.delete("/api/room-categories/" + typeId("Lab")), null, admin)).isEqualTo(409); // still used
		assertThat(send(MockMvcRequestBuilders.delete("/api/room-categories/" + created.get("id").asText()), null, admin)).isEqualTo(204);
		assertThat(send(post("/api/room-categories"), Map.of("name", "Workshop", "special", true), viewer)).isEqualTo(403);
		JsonNode renamed = objectMapper.readTree(json(MockMvcRequestBuilders.put("/api/room-categories/" + typeId("Drawing Studio")),
			Map.of("name", "Art Studio", "special", true)).andReturn().getResponse().getContentAsString());
		assertThat(renamed.get("name").asText()).isEqualTo("Art Studio");
	}

	@Test
	void outOfServiceRoomComesBackWhenSwitchedOn() throws Exception {
		JsonNode result = ok(preview("Date,Time Slot,Code,Students\n2027-05-02,09:00 - 12:00,BIG,450\n"));
		assertThat(roomNames(result.get("sittings").get(0).get("exams").get(0))).doesNotContain("Closed 500", "M209 (Lab)");
		assertThat(result.get("studentsUnplaced").asInt()).isEqualTo(450 - 250);

		String closedId = objectMapper.readTree(mockMvc.perform(MockMvcRequestBuilders.get("/api/rooms").param("name", "Closed")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn().getResponse().getContentAsString())
			.get("content").get(0).get("id").asText();
		json(MockMvcRequestBuilders.put("/api/rooms/" + closedId), Map.of("name", "Closed 500", "capacity", 500, "type", "LARGE",
			"minInvigilators", 1, "active", true));
		result = ok(preview("Date,Time Slot,Code,Students\n2027-05-02,09:00 - 12:00,BIG,450\n"));
		assertThat(roomNames(result.get("sittings").get(0).get("exams").get(0))).containsExactly("Closed 500");
	}

	@Test
	void templateOffersTheUniversitysTimeSlotsAndCanBeUploadedBack() throws Exception {
		json(post("/api/slots"), Map.of("label", "Afternoon", "startTime", "12:00", "endTime", "14:00", "sortOrder", 2));
		json(post("/api/slots"), Map.of("label", "Morning", "startTime", "09:00", "endTime", "11:00", "sortOrder", 1));

		byte[] bytes = mockMvc.perform(MockMvcRequestBuilders.get("/api/room-allocation/template")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
			var sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Time Slot");
			assertThat(sheet.getRow(0).getCell(6).getStringCellValue()).isEqualTo("Room Type");
			var lists = workbook.getSheet("Lists");
			assertThat(List.of(lists.getRow(1).getCell(0).getStringCellValue(), lists.getRow(2).getCell(0).getStringCellValue()))
				.containsExactly("09:00 - 11:00", "12:00 - 14:00"); // in time order
			assertThat(lists.getRow(4).getCell(1).getStringCellValue()).isEqualTo("Computer Lab"); // the room types
			assertThat(sheet.getDataValidations()).anyMatch(v -> v.getValidationConstraint().getFormula1() != null
				&& v.getValidationConstraint().getFormula1().contains("Lists"));

			// Fill a second row the way a user would (choosing from the dropdown) and upload the file back.
			var row = sheet.getRow(2);
			var date = row.createCell(0);
			date.setCellValue(java.time.LocalDate.of(2027, 6, 6));
			date.setCellStyle(sheet.getRow(1).getCell(0).getCellStyle());
			row.createCell(2).setCellValue("12:00 - 14:00");
			row.createCell(3).setCellValue("Statistics");
			row.createCell(4).setCellValue("STAT200");
			row.createCell(5).setCellValue(90);
			var out = new java.io.ByteArrayOutputStream();
			workbook.write(out);

			var request = MockMvcRequestBuilders.multipart("/api/room-allocation/preview")
				.file(new MockMultipartFile("file", "exams.xlsx", "application/octet-stream", out.toByteArray()))
				.param("scheduleGroupId", groupId);
			JsonNode result = ok(mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
			assertThat(result.get("exams").asInt()).isEqualTo(2); // the example row and ours; the empty formula rows are skipped
			assertThat(result.get("timeSlotsToCreate")).isEmpty();
			assertThat(result.get("warnings").toString()).doesNotContain("the day says"); // the Day formula matches the date
			JsonNode stats = result.get("sittings").get(result.get("sittings").size() - 1).get("exams").get(0);
			assertThat(stats.get("subjectCode").asText()).isEqualTo("STAT200");
			assertThat(roomNames(stats)).containsExactly("Hall 100");
		}
	}

	@Test
	void examsGetExactlyTheRoomTypeTheyAskForAndNormalExamsNeverGetSpecialRooms() throws Exception {
		room("Lab 2", 40, true); // guessed "Lab"
		json(post("/api/rooms"), Map.of("name", "PC Room 1", "capacity", 60, "type", "MEDIUM", "minInvigilators", 1, "roomCategoryId", typeId("Computer Lab")));
		String csv = """
			Date,Time Slot,Code,Students,Room Type
			2027-07-01,09:00 - 12:00,PROG1,280,Lab
			2027-07-01,09:00 - 12:00,MATH1,100,
			2027-07-01,09:00 - 12:00,CS2,50,computer lab
			2027-07-01,09:00 - 12:00,PROG2,30,LAB
			""";
		JsonNode result = ok(preview(csv));
		JsonNode sitting = result.get("sittings").get(0);
		assertThat(sitting.get("freeRooms").asInt()).isEqualTo(4); // normal rooms only
		JsonNode prog1 = sitting.get("exams").get(0);
		assertThat(prog1.get("roomType").asText()).isEqualTo("Lab");
		assertThat(prog1.get("freeOfType").asInt()).isEqualTo(2);
		assertThat(roomNames(prog1)).containsExactly("M209 (Lab)"); // 280 in the 300-seat lab
		JsonNode math = sitting.get("exams").get(1);
		assertThat(math.get("roomType").isNull()).isTrue();
		assertThat(roomNames(math)).containsExactly("Hall 100");
		assertThat(roomNames(sitting.get("exams").get(2))).containsExactly("PC Room 1"); // exactly Computer Lab, not "Lab"
		assertThat(roomNames(sitting.get("exams").get(3))).containsExactly("Lab 2");
		assertThat(result.get("columnsRecognised").toString()).contains("Room Type → room type");

		// Not enough labs: no spill into normal rooms or other types; reported instead.
		JsonNode short1 = ok(preview("Date,Time Slot,Code,Students,Room Type\n2027-07-02,09:00 - 12:00,BIGLAB,400,Lab\n"));
		assertThat(short1.get("studentsUnplaced").asInt()).isEqualTo(400 - 340);
		assertThat(short1.get("warnings").get(0).asText()).contains("not enough free \"Lab\" rooms");

		// A type with no rooms at all.
		JsonNode none = ok(preview("Date,Time Slot,Code,Students,Room Type\n2027-07-04,09:00 - 12:00,ART1,20,Drawing Studio\n"));
		JsonNode art = none.get("sittings").get(0).get("exams").get(0);
		assertThat(art.get("freeOfType").asInt()).isZero();
		assertThat(art.get("unplaced").asInt()).isEqualTo(20);

		String bad = "Date,Time Slot,Code,Students,Room Type\n2027-07-03,09:00 - 12:00,X,10,Kitchen\n";
		assertThat(preview(bad).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString())
			.contains("Unknown room type").contains("Kitchen").contains("Computer Lab");
	}

	@Test
	void roomImportReadsTheRoomTypeColumn() throws Exception {
		try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
			var sheet = workbook.createSheet("Rooms");
			String[][] rows = {
				{"Name", "Capacity", "Size", "Min Invigilators", "Hall Gender", "Room Type"},
				{"Chem 1", "25", "SMALL", "1", "MIXED", "Chemistry Lab"},
				{"Room 9", "40", "MEDIUM", "1", "MIXED", ""},
				{"Physics Lab 3", "30", "SMALL", "1", "MIXED", ""},
			};
			for (int r = 0; r < rows.length; r++) {
				var row = sheet.createRow(r);
				for (int c = 0; c < rows[r].length; c++) {
					row.createCell(c).setCellValue(rows[r][c]);
				}
			}
			var out = new java.io.ByteArrayOutputStream();
			workbook.write(out);
			var request = MockMvcRequestBuilders.multipart("/api/bulk/rooms/upload")
				.file(new MockMultipartFile("file", "rooms.xlsx", "application/octet-stream", out.toByteArray()));
			JsonNode result = ok(mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
			assertThat(result.get("successCount").asInt()).as(result.toString()).isEqualTo(3);
		}
		JsonNode rooms = readJson(MockMvcRequestBuilders.get("/api/rooms").param("size", "100"));
		Map<String, String> typeByRoom = new java.util.HashMap<>();
		rooms.get("content").forEach(room -> typeByRoom.put(room.get("name").asText(), room.get("roomCategoryName").asText()));
		assertThat(typeByRoom).containsEntry("Chem 1", "Chemistry Lab").containsEntry("Room 9", "Classroom").containsEntry("Physics Lab 3", "Lab");
	}

	@Test
	void saveRoomsThenCreateScheduleStaffsThemOnce() throws Exception {
		List<String> allDays = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
		for (int i = 1; i <= 3; i++) {
			json(post("/api/people"), Map.of("name", "Chief " + i, "department", "Exams", "role", "CHIEF_INVIGILATOR", "availableDays", allDays));
		}
		for (int i = 1; i <= 8; i++) {
			json(post("/api/people"), Map.of("name", "Invigilator " + i, "department", "Exams", "role", "INVIGILATOR", "availableDays", allDays));
		}
		String csv = """
			Date,Time Slot,Subject Name,Code,Students
			2027-08-01,09:00 - 12:00,Calculus,MATH101,150
			2027-08-01,09:00 - 12:00,Physics,PHYS110,35
			2027-08-01,09:00 - 12:00,Space,BIG9,1000
			""";
		JsonNode saved = ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "exams.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		// 150 → Hall 100 + Room 50, 35 → Room 40, the 1000-student exam gets what is left (Room 60) and is incomplete.
		assertThat(saved.get("roomAssignmentsCreated").asInt()).isEqualTo(4);
		assertThat(saved.get("examsSaved").asInt()).isEqualTo(3);
		assertThat(saved.get("examsIncomplete").asInt()).isEqualTo(1);
		assertThat(saved.get("studentsUnplaced").asInt()).isEqualTo(1000 - 60);
		assertThat(saved.get("timeSlotsCreated").get(0).asText()).isEqualTo("Exam 09:00–12:00");
		assertThat(saved.get("firstDate").asText()).isEqualTo("2027-08-01");

		TenantContext.runAs(university, () -> {
			List<RoomAssignment> rows = assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId));
			assertThat(rows).hasSize(4);
			assertThat(rows).allMatch(row -> row.getChiefInvigilator() == null); // no staff yet
			assertThat(rows).filteredOn(row -> row.getRoom().getName().equals("Hall 100"))
				.singleElement().satisfies(row -> {
					assertThat(row.getSubjectCode()).isEqualTo("MATH101");
					assertThat(row.getStudentCount()).isEqualTo(100);
				});
		});
		// The saved rooms are now booked: a new preview at the same time cannot use them.
		JsonNode again = ok(preview("Date,Time Slot,Code,Students\n2027-08-01,09:00 - 12:00,LATE,30\n"));
		assertThat(again.get("sittings").get(0).get("freeRooms").asInt()).isZero();

		List<String> ids = new ArrayList<>();
		saved.get("assignmentIds").forEach(id -> ids.add(id.asText()));
		JsonNode staffed = objectMapper.readTree(json(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", ids))
			.andReturn().getResponse().getContentAsString());
		assertThat(staffed.get("examRooms").asInt()).isEqualTo(4);
		assertThat(staffed.get("chiefSlotsFilled").asInt()).isEqualTo(staffed.get("chiefSlotsTotal").asInt()).isPositive();
		assertThat(staffed.get("invigilatorSlotsFilled").asInt()).isPositive();

		// Pressing "Create schedule" again staffs nothing twice.
		JsonNode twice = objectMapper.readTree(json(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", ids))
			.andReturn().getResponse().getContentAsString());
		assertThat(twice.get("examRooms").asInt()).isZero();
		assertThat(twice.get("alreadyStaffed").asInt()).isEqualTo(4);

		// Exam rooms of another exam period are refused.
		String otherGroup = objectMapper.readTree(json(post("/api/schedule-groups"), Map.of("name", "Resits")).andReturn().getResponse().getContentAsString())
			.get("id").asText();
		assertThat(send(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", otherGroup, "assignmentIds", ids), admin)).isEqualTo(400);
		assertThat(send(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", List.of(UUID.randomUUID().toString())), admin)).isEqualTo(404);
		assertThat(send(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", ids), viewer)).isEqualTo(403);
	}

	// ── Automatic time slots (date required, empty time = the system chooses on that day) ──

	private void twoSlots() throws Exception {
		json(post("/api/slots"), Map.of("label", "Morning", "startTime", "09:00", "endTime", "11:00", "sortOrder", 1));
		json(post("/api/slots"), Map.of("label", "Noon", "startTime", "12:00", "endTime", "14:00", "sortOrder", 2));
	}

	private void staff(int chiefs, int invigilators) throws Exception {
		List<String> allDays = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
		for (int i = 1; i <= chiefs; i++) {
			json(post("/api/people"), Map.of("name", "Auto Chief " + i, "department", "Exams", "role", "CHIEF_INVIGILATOR", "availableDays", allDays));
		}
		for (int i = 1; i <= invigilators; i++) {
			json(post("/api/people"), Map.of("name", "Auto Invigilator " + i, "department", "Exams", "role", "INVIGILATOR", "availableDays", allDays));
		}
	}

	private static JsonNode exam(JsonNode preview, String code) {
		for (JsonNode sitting : preview.get("sittings")) {
			for (JsonNode exam : sitting.get("exams")) {
				if (exam.get("subjectCode").asText().equals(code)) {
					return ((com.fasterxml.jackson.databind.node.ObjectNode) exam.deepCopy()).put("time", sitting.get("time").asText());
				}
			}
		}
		throw new IllegalStateException("no exam " + code);
	}

	@Test
	void emptyTimeSlotIsChosenOnThatDayAndFixedOnesStay() throws Exception {
		twoSlots();
		staff(4, 12);
		String csv = """
			Date,Time Slot,Code,Students
			2027-09-01,,AUTO1,100
			2027-09-01,,AUTO2,100
			2027-09-01,09:00 - 11:00,FIXED1,40
			""";
		JsonNode result = ok(preview(csv));
		assertThat(exam(result, "FIXED1").get("time").asText()).isEqualTo("09:00–11:00");
		assertThat(exam(result, "FIXED1").get("timeChosen").asBoolean()).isFalse();
		// Spread: the first goes to the empty noon slot, the second to the morning (which only has 40 students).
		assertThat(exam(result, "AUTO1").get("time").asText()).isEqualTo("12:00–14:00");
		assertThat(exam(result, "AUTO2").get("time").asText()).isEqualTo("09:00–11:00");
		assertThat(exam(result, "AUTO1").get("timeChosen").asBoolean()).isTrue();
		assertThat(roomNames(exam(result, "AUTO2"))).containsExactly("Hall 100");
		assertThat(result.get("warnings")).isEmpty();
		assertThat(result.get("timeSlotsToCreate")).isEmpty();

		// Saving keeps the chosen times.
		ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "exams.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		TenantContext.runAs(university, () -> {
			Map<String, String> timeOf = new java.util.HashMap<>();
			assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId))
				.forEach(row -> timeOf.put(row.getSubjectCode(), row.getTimeSlot().getStartTime().toString()));
			assertThat(timeOf).containsEntry("AUTO1", "12:00").containsEntry("AUTO2", "09:00").containsEntry("FIXED1", "09:00");
		});
	}

	@Test
	void chosenTimeAvoidsFullSlotsAndUsesTheRightRoomType() throws Exception {
		twoSlots();
		staff(4, 12);
		String csv = """
			Date,Time Slot,Code,Students,Room Type
			2027-09-02,09:00 - 11:00,FULL,250,
			2027-09-02,09:00 - 11:00,LABFIX,280,Lab
			2027-09-02,12:00 - 14:00,SMALL1,10,
			2027-09-02,,AUTO30,30,
			2027-09-02,,AUTOLAB,200,Lab
			""";
		JsonNode result = ok(preview(csv));
		// The morning has no normal room and no lab left: both go to noon, even though noon is busier.
		assertThat(exam(result, "AUTO30").get("time").asText()).isEqualTo("12:00–14:00");
		assertThat(exam(result, "AUTO30").get("unplaced").asInt()).isZero();
		assertThat(exam(result, "AUTOLAB").get("time").asText()).isEqualTo("12:00–14:00");
		assertThat(roomNames(exam(result, "AUTOLAB"))).containsExactly("M209 (Lab)");
	}

	@Test
	void whenNoSlotFitsTheExamIsReported() throws Exception {
		twoSlots();
		staff(4, 12);
		JsonNode result = ok(preview("Date,Time Slot,Code,Students\n2027-09-03,,HUGE,1000\n"));
		JsonNode huge = exam(result, "HUGE");
		assertThat(huge.get("unplaced").asInt()).isEqualTo(1000 - 250);
		assertThat(result.get("warnings").get(0).asText()).contains("Row 2").contains("no time slot on 2027-09-03 has enough free rooms");
	}

	@Test
	void notEnoughStaffIsAWarningAndTheDateIsRequired() throws Exception {
		twoSlots(); // nobody added: no chiefs or invigilators
		JsonNode result = ok(preview("Date,Time Slot,Code,Students\n2027-09-04,,NOSTAFF,20\n"));
		assertThat(exam(result, "NOSTAFF").get("unplaced").asInt()).isZero();
		assertThat(result.get("warnings").get(0).asText()).contains("no time that day has enough free chiefs and invigilators");

		assertThat(preview("Date,Time Slot,Code,Students\n,,NODATE,20\n").andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString()).contains("Date is required");
	}

	@Test
	void emptyTimeSlotNeedsTimeSlotsToChooseFrom() throws Exception {
		assertThat(preview("Date,Code,Students\n2027-09-05,X,20\n").andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString()).contains("no time slots to choose from");
	}

	// ── Hall gender (Male / Female exams; empty = any hall) ──

	private void hall(String name, int capacity, String gender) throws Exception {
		json(post("/api/rooms"), Map.of("name", name, "capacity", capacity, "type", "LARGE", "minInvigilators", 1, "hallGender", gender));
	}

	private void genderedStaff() throws Exception {
		List<String> allDays = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
		for (String gender : List.of("FEMALE", "MALE")) {
			for (int i = 1; i <= 3; i++) {
				json(post("/api/people"), Map.of("name", gender + " Chief " + i, "department", "Exams", "role", "CHIEF_INVIGILATOR", "availableDays", allDays, "gender", gender));
			}
			for (int i = 1; i <= 8; i++) {
				json(post("/api/people"), Map.of("name", gender + " Invigilator " + i, "department", "Exams", "role", "INVIGILATOR", "availableDays", allDays, "gender", gender));
			}
		}
	}

	@Test
	void genderedExamsUseTheirHallsFirstThenMixedWithAFlag() throws Exception {
		hall("Women Hall", 80, "FEMALE");
		hall("Men Hall", 70, "MALE");
		String csv = """
			Date,Time Slot,Code,Students,Gender
			2027-10-01,09:00 - 12:00,FEM1,60,Female
			2027-10-01,09:00 - 12:00,MAL1,100,male
			2027-10-01,09:00 - 12:00,ANY1,50,
			2027-10-02,09:00 - 12:00,SAME,20,Male
			2027-10-02,09:00 - 12:00,SAME,20,Female
			2027-10-03,09:00 - 12:00,ANY2,330,
			""";
		JsonNode result = ok(preview(csv));
		JsonNode fem = exam(result, "FEM1");
		assertThat(fem.get("gender").asText()).isEqualTo("Female");
		assertThat(roomNames(fem)).containsExactly("Women Hall");
		assertThat(fem.get("mixedHalls").asBoolean()).isFalse();

		JsonNode men = exam(result, "MAL1");
		assertThat(roomNames(men)).containsExactly("Men Hall", "Room 40"); // 70 in the men's hall, 30 in a Mixed hall
		assertThat(men.get("mixedHalls").asBoolean()).isTrue();
		assertThat(men.get("unplaced").asInt()).isZero();
		assertThat(result.get("warnings").toString()).contains("not enough free Male halls").contains("30 students placed in Mixed halls");

		JsonNode any = exam(result, "ANY1");
		assertThat(any.get("gender").isNull()).isTrue();
		assertThat(roomNames(any)).containsExactly("Room 50"); // Mixed first: the gendered halls stay free

		// One subject, men and women at the same time: two rows are allowed.
		List<String> sameRooms = new ArrayList<>();
		for (JsonNode sitting : result.get("sittings")) {
			for (JsonNode exam : sitting.get("exams")) {
				if (exam.get("subjectCode").asText().equals("SAME")) {
					sameRooms.addAll(roomNames(exam));
				}
			}
		}
		assertThat(sameRooms).containsExactlyInAnyOrder("Women Hall", "Men Hall");

		// A mixed exam may use gendered halls when the Mixed halls run out.
		JsonNode big = exam(result, "ANY2");
		assertThat(big.get("unplaced").asInt()).isZero();
		assertThat(roomNames(big)).contains("Hall 100", "Women Hall");
		assertThat(big.get("mixedHalls").asBoolean()).isFalse();

		assertThat(preview("Date,Time Slot,Code,Students,Gender\n2027-10-04,09:00 - 12:00,X,10,Robot\n").andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString()).contains("Gender must be Male or Female");
	}

	@Test
	void chosenTimePrefersAFreeHallOfTheRightGender() throws Exception {
		twoSlots();
		genderedStaff();
		hall("Women Hall", 80, "FEMALE");
		String csv = """
			Date,Time Slot,Code,Students,Gender
			2027-10-05,09:00 - 11:00,FEMFIX,70,Female
			2027-10-05,12:00 - 14:00,NOONBIG,200,
			2027-10-05,,FEMAUTO,50,Female
			""";
		JsonNode result = ok(preview(csv));
		// The morning is less busy, but its women's hall is taken: noon still has it free.
		JsonNode auto = exam(result, "FEMAUTO");
		assertThat(auto.get("time").asText()).isEqualTo("12:00–14:00");
		assertThat(roomNames(auto)).containsExactly("Women Hall");
		assertThat(auto.get("mixedHalls").asBoolean()).isFalse();
	}

	@Test
	void menAndWomenOfOneSubjectGetTheSameTime() throws Exception {
		twoSlots();
		genderedStaff();
		hall("Women Hall", 80, "FEMALE");
		hall("Men Hall", 70, "MALE");
		String csv = """
			Date,Time Slot,Code,Students,Gender
			2027-10-08,,SPLIT,30,Male
			2027-10-08,,SPLIT,40,Female
			2027-10-08,09:00 - 11:00,BUSY,200,
			2027-10-08,12:00 - 14:00,FOLLOW,20,Male
			2027-10-08,,FOLLOW,20,Female
			""";
		JsonNode result = ok(preview(csv));
		Map<String, String> timeOf = new java.util.HashMap<>();
		for (JsonNode sitting : result.get("sittings")) {
			for (JsonNode exam : sitting.get("exams")) {
				timeOf.put(exam.get("subjectCode").asText() + "/" + exam.get("gender").asText(), sitting.get("time").asText());
			}
		}
		assertThat(timeOf.get("SPLIT/Male")).isEqualTo(timeOf.get("SPLIT/Female")); // one exam paper, one time
		assertThat(timeOf.get("FOLLOW/Female")).isEqualTo("12:00–14:00");          // follows the fixed Male row
	}

	@Test
	void aSavedAllocationCanBeUndoneOnce() throws Exception {
		staff(3, 8);
		String csv = "Date,Time Slot,Code,Students\n2027-11-01,09:00 - 12:00,UNDO1,150\n2027-11-01,09:00 - 12:00,UNDO2,35\n";
		var save = MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "finals-nov.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId);
		JsonNode saved = ok(mockMvc.perform(save.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		String batchId = saved.get("batchId").asText();
		assertThat(saved.get("plan").get("sittings").get(0).get("exams")).hasSize(2); // what was saved, for the page to compare
		List<String> ids = new ArrayList<>();
		saved.get("assignmentIds").forEach(id -> ids.add(id.asText()));
		json(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", ids));

		// An exam room made by hand in the same period is not part of the save and must survive the undo.
		String other = "Date,Time Slot,Room,Subject Code,Students\n2027-11-02,09:00 - 12:00,Room 60,HAND1,20\n";
		ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/schedule-groups/" + groupId + "/bulk-upload")
			.file(new MockMultipartFile("file", "hand.csv", "text/csv", other.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));

		JsonNode batches = readJson(MockMvcRequestBuilders.get("/api/room-allocation/batches").param("scheduleGroupId", groupId));
		assertThat(batches).hasSize(1);
		assertThat(batches.get(0).get("fileName").asText()).isEqualTo("finals-nov.csv");
		assertThat(batches.get(0).get("remaining").asInt()).isEqualTo(3);
		assertThat(batches.get(0).get("createdBy").asText()).contains("@alloc.test");

		assertThat(send(post("/api/room-allocation/batches/" + batchId + "/undo"), null, viewer)).isEqualTo(403);
		JsonNode undone = objectMapper.readTree(json(post("/api/room-allocation/batches/" + batchId + "/undo"), Map.of())
			.andReturn().getResponse().getContentAsString());
		assertThat(undone.get("examRoomsDeleted").asInt()).isEqualTo(3);

		TenantContext.runAs(university, () -> {
			List<RoomAssignment> left = assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId));
			assertThat(left).extracting(RoomAssignment::getSubjectCode).containsExactly("HAND1");
		});
		JsonNode after = readJson(MockMvcRequestBuilders.get("/api/room-allocation/batches").param("scheduleGroupId", groupId));
		assertThat(after.get(0).get("undoneAt").isNull()).isFalse();
		assertThat(after.get(0).get("remaining").asInt()).isZero();
		assertThat(send(post("/api/room-allocation/batches/" + batchId + "/undo"), null, admin)).isEqualTo(409); // only once
		assertThat(send(post("/api/room-allocation/batches/" + UUID.randomUUID() + "/undo"), null, admin)).isEqualTo(404);

		// The rooms are free again: the same file can be saved again.
		JsonNode again = ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "finals-nov.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		assertThat(again.get("roomAssignmentsCreated").asInt()).isEqualTo(3);
	}

	// ── How to fix (hints) ──

	@Test
	void problemsComeWithConcreteHints() throws Exception {
		twoSlots();
		staff(4, 12);
		String csv = """
			Date,Time Slot,Code,Students,Room Type
			2027-12-01,09:00 - 11:00,AAA,150,
			2027-12-01,09:00 - 11:00,BBB,60,
			2027-12-01,09:00 - 11:00,CCC,80,
			2027-12-01,09:00 - 11:00,ART,20,Drawing Studio
			""";
		JsonNode result = ok(preview(csv));
		// BBB gets no room at 09:00 (AAA and CCC take all 250 normal seats).
		JsonNode bbb = exam(result, "BBB");
		assertThat(bbb.get("unplaced").asInt()).isEqualTo(60);
		String hints = bbb.get("hints").toString();
		assertThat(hints).contains("Move it to another time on 1 Dec: 12:00–14:00 (4 free rooms, 250 seats)");
		assertThat(hints).contains("Or move CCC (80 students, row 4) to 12:00–14:00");
		assertThat(hints).contains("Out of service now: Closed 500 (500)");

		JsonNode art = exam(result, "ART");
		assertThat(art.get("hints").toString()).contains("No room has the type “Drawing Studio”")
			.doesNotContain("M209") // a lab is no example for a drawing studio
			.doesNotContain("No time on"); // another date cannot help when the type has no rooms
		JsonNode pc = ok(preview("Date,Time Slot,Code,Students,Room Type\n2027-12-02,09:00 - 11:00,PC1,20,Computer Lab\n"));
		assertThat(exam(pc, "PC1").get("hints").toString()).contains("No room has the type “Computer Lab”").contains("M209 (Lab) (300)");

		assertThat(exam(result, "AAA").get("hints")).isEmpty(); // no problem, no hint
	}

	@Test
	void genderAndStaffProblemsComeWithHints() throws Exception {
		hall("Men Hall", 70, "MALE");
		JsonNode result = ok(preview("Date,Time Slot,Code,Students,Gender\n2027-12-05,09:00 - 12:00,MEN1,100,Male\n"));
		assertThat(exam(result, "MEN1").get("hints").toString()).contains("Only 70 seats are in Male halls for 100 students");

		// Staff who don't work on Sundays: Create schedule says so for the empty seats.
		json(post("/api/people"), Map.of("name", "Monday Chief", "department", "Exams", "role", "CHIEF_INVIGILATOR", "availableDays", List.of("MON")));
		json(post("/api/people"), Map.of("name", "Monday Invigilator", "department", "Exams", "role", "INVIGILATOR", "availableDays", List.of("MON")));
		String csv = "Date,Time Slot,Code,Students\n2027-12-05,09:00 - 12:00,SUN1,40\n"; // 5 Dec 2027 is a Sunday
		JsonNode saved = ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "sunday.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		List<String> ids = new ArrayList<>();
		saved.get("assignmentIds").forEach(id -> ids.add(id.asText()));
		JsonNode staffed = objectMapper.readTree(json(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", ids))
			.andReturn().getResponse().getContentAsString());
		assertThat(staffed.get("hints")).hasSize(1);
		assertThat(staffed.get("hints").get(0).asText()).startsWith("5 Dec 09:00").contains("1 don't work on Sunday").contains("People page");
	}

	// ── Buildings ──

	@Test
	void buildingsAreGuessedEditableAndKeepSplitExamsTogether() throws Exception {
		JsonNode a101 = objectMapper.readTree(json(post("/api/rooms"), Map.of("name", "A101", "capacity", 70, "type", "LARGE", "minInvigilators", 1))
			.andReturn().getResponse().getContentAsString());
		assertThat(a101.get("building").asText()).isEqualTo("A"); // from the name
		json(post("/api/rooms"), Map.of("name", "A102", "capacity", 70, "type", "LARGE", "minInvigilators", 1));
		json(post("/api/rooms"), Map.of("name", "Annex Room", "capacity", 50, "type", "LARGE", "minInvigilators", 1, "building", "B"));
		JsonNode none = objectMapper.readTree(json(MockMvcRequestBuilders.put("/api/rooms/" + a101.get("id").asText()),
			Map.of("name", "A101", "capacity", 70, "type", "LARGE", "minInvigilators", 1, "building", "")).andReturn().getResponse().getContentAsString());
		assertThat(none.get("building").isNull()).isTrue(); // "no building" chosen
		json(MockMvcRequestBuilders.put("/api/rooms/" + a101.get("id").asText()),
			Map.of("name", "A101", "capacity", 70, "type", "LARGE", "minInvigilators", 1, "building", "A"));

		// The 250-student exam takes the four rooms without a building; the 120-student exam then stays in building A
		// (70 + 70) instead of 70 + 50 across buildings.
		String csv = "Date,Time Slot,Code,Students\n2028-01-03,09:00 - 12:00,ALL250,250\n2028-01-03,09:00 - 12:00,SPLIT120,120\n";
		JsonNode result = ok(preview(csv));
		assertThat(roomNames(exam(result, "SPLIT120"))).containsExactly("A101", "A102");
	}

	@Test
	void aChiefCoversSeveralRoomsOnlyInOneBuilding() throws Exception {
		for (String name : List.of("A101", "A102", "B201")) {
			json(post("/api/rooms"), Map.of("name", name, "capacity", 30, "type", "SMALL", "minInvigilators", 1));
		}
		List<String> allDays = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
		for (String name : List.of("Chief One", "Chief Two")) {
			json(post("/api/people"), Map.of("name", name, "department", "Exams", "role", "CHIEF_INVIGILATOR", "availableDays", allDays));
		}
		for (int i = 1; i <= 3; i++) {
			json(post("/api/people"), Map.of("name", "Inv " + i, "department", "Exams", "role", "INVIGILATOR", "availableDays", allDays));
		}
		// Each chief may cover 2 rooms at once (default). Three rooms, two chiefs: A101 + A102 share one chief, B201 gets the other.
		String timetable = "Date,Time Slot,Room,Subject Code,Students\n"
			+ "2028-01-04,09:00 - 12:00,A101,X1,20\n2028-01-04,09:00 - 12:00,A102,X2,20\n2028-01-04,09:00 - 12:00,B201,X3,20\n";
		ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/schedule-groups/" + groupId + "/bulk-upload")
			.file(new MockMultipartFile("file", "t.csv", "text/csv", timetable.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		TenantContext.runAs(university, () -> {
			Map<String, String> chiefOf = new java.util.HashMap<>();
			assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId))
				.forEach(row -> chiefOf.put(row.getRoom().getName(), row.getChiefInvigilator() == null ? null : row.getChiefInvigilator().getName()));
			assertThat(chiefOf.values()).doesNotContainNull();
			assertThat(chiefOf.get("A101")).isEqualTo(chiefOf.get("A102"));
			assertThat(chiefOf.get("B201")).isNotEqualTo(chiefOf.get("A101"));
		});
	}

	@Test
	void roomImportReadsTheBuildingColumn() throws Exception {
		try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
			var sheet = workbook.createSheet("Rooms");
			String[][] rows = {
				{"Name", "Capacity", "Size", "Min Invigilators", "Hall Gender", "Room Type", "Building"},
				{"Lecture Theatre", "120", "LARGE", "2", "MIXED", "", "Main"},
				{"C305", "40", "MEDIUM", "1", "MIXED", "", ""},
			};
			for (int r = 0; r < rows.length; r++) {
				var row = sheet.createRow(r);
				for (int c = 0; c < rows[r].length; c++) {
					row.createCell(c).setCellValue(rows[r][c]);
				}
			}
			var out = new java.io.ByteArrayOutputStream();
			workbook.write(out);
			ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/bulk/rooms/upload")
				.file(new MockMultipartFile("file", "rooms.xlsx", "application/octet-stream", out.toByteArray()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		}
		Map<String, String> buildingOf = new java.util.HashMap<>();
		readJson(MockMvcRequestBuilders.get("/api/rooms").param("size", "100")).get("content")
			.forEach(room -> buildingOf.put(room.get("name").asText(), room.get("building").isNull() ? null : room.get("building").asText()));
		assertThat(buildingOf).containsEntry("Lecture Theatre", "Main").containsEntry("C305", "C");
	}

	// ── Changes made on the page before saving ──

	private String roomId(String name) throws Exception {
		for (JsonNode room : readJson(MockMvcRequestBuilders.get("/api/rooms").param("size", "100")).get("content")) {
			if (room.get("name").asText().equals(name)) {
				return room.get("id").asText();
			}
		}
		throw new IllegalStateException("no room " + name);
	}

	private ResultActions preview(String csv, String adjustments, String endpoint) throws Exception {
		var request = MockMvcRequestBuilders.multipart("/api/room-allocation/" + endpoint)
			.file(new MockMultipartFile("file", "exams.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId)
			.param("adjustments", adjustments);
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest"));
	}

	@Test
	void theUserCanMoveAnExamAndChooseItsRooms() throws Exception {
		twoSlots();
		String csv = """
			Date,Time Slot,Code,Students
			2028-02-01,09:00 - 11:00,MOVE,100
			2028-02-01,09:00 - 11:00,PIN,90
			2028-02-01,09:00 - 11:00,OTHER,100
			""";
		// Row 2 to noon; row 3 exactly in Room 50 + Room 40 (the system would have chosen Hall 100).
		String changes = "{\"times\":{\"2\":\"12:00-14:00\"},\"rooms\":{\"3\":[\"" + roomId("Room 50") + "\",\"" + roomId("Room 40") + "\"]}}";
		JsonNode result = ok(preview(csv, changes, "preview"));
		JsonNode moved = exam(result, "MOVE");
		assertThat(moved.get("time").asText()).isEqualTo("12:00–14:00");
		assertThat(moved.get("edited").asBoolean()).isTrue();
		JsonNode pinned = exam(result, "PIN");
		assertThat(roomNames(pinned)).containsExactly("Room 50", "Room 40");
		assertThat(pinned.get("edited").asBoolean()).isTrue();
		assertThat(roomNames(exam(result, "OTHER"))).containsExactly("Hall 100"); // seated around the chosen rooms
		assertThat(exam(result, "OTHER").get("edited").asBoolean()).isFalse();

		// Save keeps the changes.
		ok(preview(csv, changes, "save"));
		TenantContext.runAs(university, () -> {
			Map<String, List<String>> roomsOf = new java.util.HashMap<>();
			Map<String, String> timeOf = new java.util.HashMap<>();
			for (RoomAssignment row : assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId))) {
				roomsOf.computeIfAbsent(row.getSubjectCode(), k -> new ArrayList<>()).add(row.getRoom().getName());
				timeOf.put(row.getSubjectCode(), row.getTimeSlot().getStartTime().toString());
			}
			assertThat(roomsOf.get("PIN")).containsExactlyInAnyOrder("Room 50", "Room 40");
			assertThat(timeOf).containsEntry("MOVE", "12:00").containsEntry("PIN", "09:00");
		});
	}

	@Test
	void droppingAnExamOnAnotherDayOrOnASmallRoomWorks() throws Exception {
		twoSlots();
		String csv = "Date,Time Slot,Code,Students\n2028-04-01,09:00 - 11:00,DAYMOVE,30\n2028-04-01,09:00 - 11:00,DROP,100\n";
		// Row 2 to another day; row 3 dropped on Room 40 at noon: 40 seats there, the rest in more rooms.
		String changes = "{\"dates\":{\"2\":\"2028-04-03\"},\"times\":{\"3\":\"12:00-14:00\"},\"anchors\":{\"3\":\"" + roomId("Room 40") + "\"}}";
		JsonNode result = ok(preview(csv, changes, "preview"));
		Map<String, String> dayOf = new java.util.HashMap<>();
		for (JsonNode sitting : result.get("sittings")) {
			for (JsonNode exam : sitting.get("exams")) {
				dayOf.put(exam.get("subjectCode").asText(), sitting.get("date").asText() + " " + sitting.get("time").asText());
			}
		}
		assertThat(dayOf).containsEntry("DAYMOVE", "2028-04-03 09:00–11:00").containsEntry("DROP", "2028-04-01 12:00–14:00");
		JsonNode drop = exam(result, "DROP");
		assertThat(roomNames(drop).get(0)).isEqualTo("Room 40"); // the room it was dropped on comes first
		assertThat(drop.get("unplaced").asInt()).isZero();       // more rooms were added for the other 60
		assertThat(drop.get("edited").asBoolean()).isTrue();
		assertThat(exam(result, "DAYMOVE").get("edited").asBoolean()).isTrue();
	}

	@Test
	void theRoomViewIsToldWhichRoomsAreAlreadyBooked() throws Exception {
		String booked = "Date,Time Slot,Room,Subject Code,Students\n2028-04-05,09:00 - 12:00,Hall 100,OLD1,80\n";
		ok(mockMvc.perform(MockMvcRequestBuilders.multipart("/api/schedule-groups/" + groupId + "/bulk-upload")
			.file(new MockMultipartFile("file", "old.csv", "text/csv", booked.getBytes(StandardCharsets.UTF_8)))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		JsonNode result = ok(preview("Date,Time Slot,Code,Students\n2028-04-05,13:00 - 15:00,NEW1,20\n"));
		assertThat(result.get("booked")).hasSize(1);
		JsonNode entry = result.get("booked").get(0);
		assertThat(entry.get("date").asText()).isEqualTo("2028-04-05");
		assertThat(entry.get("time").asText()).isEqualTo("09:00–12:00");
		assertThat(entry.get("roomId").asText()).isEqualTo(roomId("Hall 100"));
		assertThat(entry.get("label").asText()).isEqualTo("Booked: OLD1");
	}

	@Test
	void chosenRoomsThatAreTakenOrUnknownRowsAreReported() throws Exception {
		twoSlots();
		String csv = "Date,Time Slot,Code,Students\n2028-02-02,09:00 - 11:00,A1,40\n2028-02-02,09:00 - 11:00,A2,40\n";
		String room40 = roomId("Room 40");
		// Both rows want Room 40: the first gets it, the second is told and seated nowhere else (its choice is respected).
		String clash = "{\"rooms\":{\"2\":[\"" + room40 + "\"],\"3\":[\"" + room40 + "\"]}}";
		JsonNode result = ok(preview(csv, clash, "preview"));
		assertThat(roomNames(exam(result, "A1"))).containsExactly("Room 40");
		assertThat(exam(result, "A2").get("unplaced").asInt()).isEqualTo(40);
		assertThat(result.get("warnings").toString()).contains("room Room 40 is not free");

		assertThat(preview(csv, "{\"times\":{\"9\":\"12:00-14:00\"}}", "preview").andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString()).contains("this file has no exam in that row");
		assertThat(preview(csv, "{not json", "preview").andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString()).contains("could not be read");
		assertThat(preview("Date,Time Slot,Code,Students\n2028-02-02,,A3,40\n", "{\"rooms\":{\"2\":[\"" + room40 + "\"]}}", "preview")
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()).contains("Choose a time for this exam");
	}

	@Test
	void chosenRoomsOfTheWrongTypeOrGenderAreLeftOut() throws Exception {
		twoSlots();
		hall("Women Hall", 50, "FEMALE");
		room("Physics Lab 7", 40, true); // its name makes it a lab: only for exams that ask for a lab
		String csv = "Date,Time Slot,Code,Students,Gender\n2028-02-03,09:00 - 11:00,MEN1,30,Male\n2028-02-03,09:00 - 11:00,ANY1,30,\n";
		// The page should never send these, but the server must not trust it.
		String changes = "{\"anchors\":{\"2\":\"" + roomId("Women Hall") + "\",\"3\":\"" + roomId("Physics Lab 7") + "\"},"
			+ "\"times\":{\"2\":\"09:00-11:00\",\"3\":\"09:00-11:00\"}}";
		JsonNode result = ok(preview(csv, changes, "preview"));
		assertThat(roomNames(exam(result, "MEN1"))).doesNotContain("Women Hall");
		assertThat(roomNames(exam(result, "ANY1"))).doesNotContain("Physics Lab 7");
		assertThat(result.get("warnings").toString())
			.contains("room Women Hall is a Female hall and this exam is Male, so it was left out")
			.contains("room Physics Lab 7 is a “");
	}

	@Test
	void theSchedulerIsToldWhoIsBusyInOtherExamPeriods() throws Exception {
		List<String> allDays = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
		String busy = objectMapper.readTree(json(post("/api/people"), Map.of("name", "Busy Elsewhere", "department", "Exams", "role", "INVIGILATOR",
			"availableDays", allDays)).andReturn().getResponse().getContentAsString()).get("id").asText();
		String free = objectMapper.readTree(json(post("/api/people"), Map.of("name", "Really Free", "department", "Exams", "role", "INVIGILATOR",
			"availableDays", allDays)).andReturn().getResponse().getContentAsString()).get("id").asText();
		String noon = objectMapper.readTree(json(post("/api/slots"), Map.of("label", "Noon", "startTime", "12:00", "endTime", "13:00", "sortOrder", 1))
			.andReturn().getResponse().getContentAsString()).get("id").asText();
		String lateMorning = objectMapper.readTree(json(post("/api/slots"), Map.of("label", "Late morning", "startTime", "11:30", "endTime", "12:30", "sortOrder", 2))
			.andReturn().getResponse().getContentAsString()).get("id").asText();
		String otherGroup = objectMapper.readTree(json(post("/api/schedule-groups"), Map.of("name", "Resits")).andReturn().getResponse().getContentAsString())
			.get("id").asText();
		// In the other exam period, "Busy Elsewhere" invigilates 12:00–13:00 on 2028-05-07.
		json(post("/api/assignments"), Map.of("scheduleGroupId", otherGroup, "examDate", "2028-05-07", "roomId", roomId("Hall 100"), "timeSlotId", noon,
			"subjectCode", "HUM101", "locked", false, "invigilatorIds", List.of(busy))).andExpect(status().isCreated());

		// This exam period, 11:30–12:30 the same day: overlaps → busy; the next day → nothing.
		JsonNode result = readJson(MockMvcRequestBuilders.get("/api/assignments/busy-elsewhere").param("scheduleGroupId", groupId).param("date", "2028-05-07")
			.param("slotId", lateMorning));
		assertThat(result.get("people")).hasSize(1);
		JsonNode person = result.get("people").get(0);
		assertThat(person.get("personId").asText()).isEqualTo(busy).isNotEqualTo(free);
		assertThat(person.get("invigilator").asBoolean()).isTrue();
		assertThat(person.get("reason").asText()).isEqualTo("Busy in Resits at 12:00–13:00");
		assertThat(readJson(MockMvcRequestBuilders.get("/api/assignments/busy-elsewhere").param("scheduleGroupId", groupId).param("date", "2028-05-08")
			.param("slotId", lateMorning)).get("people")).isEmpty();
		// Its own exam period does not count as "elsewhere" (the page already knows it).
		assertThat(readJson(MockMvcRequestBuilders.get("/api/assignments/busy-elsewhere").param("scheduleGroupId", otherGroup).param("date", "2028-05-07")
			.param("slotId", lateMorning)).get("people")).isEmpty();
	}

	@Test
	void anExamPeriodWithSavedAllocationsCanBeDeleted() throws Exception {
		String csv = "Date,Time Slot,Code,Students\n2028-06-01,09:00 - 11:00,GONE1,50\n";
		var save = MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "gone.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId);
		ok(mockMvc.perform(save.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		assertThat(readJson(MockMvcRequestBuilders.get("/api/room-allocation/batches").param("scheduleGroupId", groupId))).hasSize(1);

		mockMvc.perform(MockMvcRequestBuilders.delete("/api/schedule-groups/" + groupId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest"))
			.andExpect(status().is2xxSuccessful());
		mockMvc.perform(MockMvcRequestBuilders.get("/api/schedule-groups/" + groupId).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
			.andExpect(status().isNotFound());
	}

	// ── Door sheets and Excel export ──

	@Test
	void doorSheetsAndExcelOfASavedAllocation() throws Exception {
		staff(3, 8);
		String csv = "Date,Time Slot,Subject Name,Code,Students\n2028-03-01,09:00 - 12:00,Calculus,MATH101,150\n"
			+ "2028-03-01,09:00 - 12:00,=HYPERLINK(\"x\"),EVIL1,35\n";
		var save = MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "march.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId);
		JsonNode saved = ok(mockMvc.perform(save.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")));
		String batchId = saved.get("batchId").asText();
		List<String> ids = new ArrayList<>();
		saved.get("assignmentIds").forEach(id -> ids.add(id.asText()));
		json(post("/api/room-allocation/staff"), Map.of("scheduleGroupId", groupId, "assignmentIds", ids));

		// Door sheets: one page per exam room (Hall 100 + Room 50 for MATH101, Room 40 for EVIL1).
		var pdfResponse = mockMvc.perform(MockMvcRequestBuilders.get("/api/room-allocation/batches/" + batchId + "/door-sheets")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer)).andExpect(status().isOk()).andReturn().getResponse();
		assertThat(pdfResponse.getContentType()).isEqualTo("application/pdf");
		assertThat(pdfResponse.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("door-sheets-finals.pdf");
		var reader = new com.lowagie.text.pdf.PdfReader(pdfResponse.getContentAsByteArray());
		assertThat(reader.getNumberOfPages()).isEqualTo(3);
		var text = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
		String first = text.getTextFromPage(1);
		assertThat(first).contains("Hall 100").contains("Wednesday 1 March 2028").contains("09:00 – 12:00")
			.contains("Calculus (MATH101)").contains("100 of 100 seats").contains("Auto Chief");
		reader.close();

		// Excel: one row per exam room, staff names, and no formula from a subject name.
		byte[] xlsx = mockMvc.perform(MockMvcRequestBuilders.get("/api/room-allocation/batches/" + batchId + "/export")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
			var sheet = workbook.getSheetAt(0);
			assertThat(sheet.getLastRowNum()).isEqualTo(3);
			assertThat(sheet.getRow(0).getCell(10).getStringCellValue()).isEqualTo("Chief");
			assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("Hall 100");
			assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).startsWith("Auto Chief");
			assertThat(sheet.getRow(1).getCell(11).getStringCellValue()).contains("Auto Invigilator");
			for (int r = 1; r <= 3; r++) {
				var cell = sheet.getRow(r).getCell(6);
				assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
				if (cell.getStringCellValue().contains("HYPERLINK")) {
					assertThat(cell.getStringCellValue()).startsWith("'=");
				}
			}
		}

		// Undone: nothing to print.
		json(post("/api/room-allocation/batches/" + batchId + "/undo"), Map.of());
		assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/api/room-allocation/batches/" + batchId + "/door-sheets")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn().getResponse().getStatus()).isEqualTo(409);
	}

	@Test
	void viewersCannotSaveRooms() throws Exception {
		var request = MockMvcRequestBuilders.multipart("/api/room-allocation/save")
			.file(new MockMultipartFile("file", "x.csv", "text/csv", "Date,Time Slot,Code,Students\n2027-01-10,09:00 - 12:00,X1,5\n".getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId);
		mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer).header(CSRF, "XMLHttpRequest"))
			.andExpect(status().isForbidden());
		TenantContext.runAs(university, () -> assertThat(assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId))).isEmpty());
	}

	@Test
	void viewersCannotUseIt() throws Exception {
		var request = MockMvcRequestBuilders.multipart("/api/room-allocation/preview")
			.file(new MockMultipartFile("file", "x.csv", "text/csv", "Date,Time Slot,Code,Students\n2027-01-10,09:00 - 12:00,X1,5\n".getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId);
		mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer).header(CSRF, "XMLHttpRequest"))
			.andExpect(status().isForbidden());
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	private String signIn(AdminRole role) {
		String email = "alloc-" + UUID.randomUUID().toString().substring(0, 8) + "@alloc.test";
		users.save(AdminUser.builder().email(email).passwordHash(passwordEncoder.encode("alloc-password-1")).role(role).universityId(university).build());
		createdUsers.add(email);
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		return jwt.generateToken(email, user.getTokenVersion(), sessionService.start(user, null).session().getId()).token();
	}

	private void room(String name, int capacity, boolean active) throws Exception {
		String id = objectMapper.readTree(json(post("/api/rooms"), Map.of("name", name, "capacity", capacity, "type", "LARGE",
			"minInvigilators", 1, "hallGender", "MIXED")).andReturn().getResponse().getContentAsString()).get("id").asText();
		if (!active) { // "out of service" switch on the Rooms page
			json(MockMvcRequestBuilders.put("/api/rooms/" + id), Map.of("name", name, "capacity", capacity, "type", "LARGE",
				"minInvigilators", 1, "hallGender", "MIXED", "active", false));
		}
	}

	private String typeId(String name) throws Exception {
		for (JsonNode type : readJson(MockMvcRequestBuilders.get("/api/room-categories"))) {
			if (type.get("name").asText().equals(name)) {
				return type.get("id").asText();
			}
		}
		throw new IllegalStateException("no room type " + name);
	}

	private JsonNode readJson(MockHttpServletRequestBuilder builder) throws Exception {
		return ok(mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)));
	}

	private int send(MockHttpServletRequestBuilder builder, Object body, String token) throws Exception {
		builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return mockMvc.perform(builder).andReturn().getResponse().getStatus();
	}

	private ResultActions preview(String csv) throws Exception {
		var request = MockMvcRequestBuilders.multipart("/api/room-allocation/preview")
			.file(new MockMultipartFile("file", "exams.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
			.param("scheduleGroupId", groupId);
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest"));
	}

	private JsonNode ok(ResultActions actions) throws Exception {
		String body = actions.andReturn().getResponse().getContentAsString();
		assertThat(actions.andReturn().getResponse().getStatus()).as(body).isEqualTo(200);
		return objectMapper.readTree(body);
	}

	private ResultActions json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
		ResultActions actions = mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
		assertThat(actions.andReturn().getResponse().getStatus()).as(actions.andReturn().getResponse().getContentAsString()).isBetween(200, 299);
		return actions;
	}

	private static List<String> roomNames(JsonNode exam) {
		List<String> names = new ArrayList<>();
		exam.get("rooms").forEach(room -> names.add(room.get("name").asText()));
		return names;
	}
}
