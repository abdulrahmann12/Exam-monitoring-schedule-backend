package com.uniguard.schedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import com.uniguard.schedule.repository.TimeSlotRepository;
import com.uniguard.schedule.security.JwtTokenProvider;
import com.uniguard.schedule.tenant.UniversityProvisioning;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exam timetable import: our template and exports from student-information systems (Banner,
 * PeopleSoft, Odoo), as Excel or CSV. Each test runs in its own new university.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimetableImportTest {

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

	private final List<String> createdUsers = new ArrayList<>();
	private String admin;
	private String groupId;

	@BeforeEach
	void setUp() throws Exception {
		UUID university = provisioning.create("Import University", "import-" + UUID.randomUUID().toString().substring(0, 8)).getId();
		String email = "import-" + UUID.randomUUID().toString().substring(0, 8) + "@import.test";
		users.save(AdminUser.builder().email(email).passwordHash(passwordEncoder.encode("import-password-1")).role(AdminRole.ADMIN).universityId(university).build());
		createdUsers.add(email);
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		admin = jwt.generateToken(email, user.getTokenVersion(), sessionService.start(user, null).session().getId()).token();
		groupId = id(json(post("/api/schedule-groups"), Map.of("name", "Import Period")));
		for (String room : List.of("SCI 101", "Hall A", "LAB-2")) {
			json(post("/api/rooms"), Map.of("name", room, "capacity", 60, "type", "LARGE", "minInvigilators", 1, "hallGender", "MIXED"));
		}
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
	void bannerCsvWithBuildingRoomSubjectNumberAndAmPmTimes() throws Exception {
		String csv = """
			CRN,Subject,Course Number,Title,Exam Date,Exam Start Time,Exam End Time,Building,Room,Enrollment
			20101,MATH,101,"Calculus I, Section A",10-JAN-2027,9:00 AM,12:00 PM,SCI,101,48
			20102,PHYS,210,Mechanics,11-jan-2027,1:30 PM,3:00 PM,,Hall A,30
			""";
		JsonNode result = upload("banner.csv", csv.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString().transform(this::readTree);
		assertThat(result.get("roomAssignmentsCreated").asInt()).isEqualTo(2);
		assertThat(result.get("timeSlotsCreated")).hasSize(2);
		assertThat(result.get("timeSlotsCreated").get(0).asText()).isEqualTo("Exam 09:00–12:00");
		assertThat(result.get("columnsRecognised").toString()).contains("Exam Start Time → start time").contains("Course Number → course number");

		List<RoomAssignment> rows = imported();
		RoomAssignment maths = rows.get(0);
		assertThat(maths.getExamDate()).isEqualTo(LocalDate.of(2027, 1, 10));
		assertThat(maths.getRoom().getName()).isEqualTo("SCI 101");           // building + room
		assertThat(maths.getSubjectCode()).isEqualTo("MATH101");              // subject + course number
		assertThat(maths.getSubjectName()).isEqualTo("Calculus I, Section A"); // comma inside quotes
		assertThat(maths.getStudentCount()).isEqualTo(48);
		assertThat(maths.getTimeSlot().getStartTime()).isEqualTo(LocalTime.of(9, 0));
		RoomAssignment physics = rows.get(1);
		assertThat(physics.getTimeSlot().getStartTime()).isEqualTo(LocalTime.of(13, 30));
		assertThat(physics.getTimeSlot().getEndTime()).isEqualTo(LocalTime.of(15, 0));
	}

	@Test
	void peopleSoftExcelWithRealDateAndTimeCells() throws Exception {
		byte[] workbook;
		try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = book.createSheet("PS_EXAMS");
			String[] headers = {"Subject", "Catalog Nbr", "Descr", "Exam Date", "Exam Start Time", "Exam End Time", "Facility ID", "Enrl Tot"};
			Row header = sheet.createRow(0);
			for (int i = 0; i < headers.length; i++) {
				header.createCell(i).setCellValue(headers[i]);
			}
			CellStyle dateStyle = book.createCellStyle();
			dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("m/d/yy")); // US display, real date value
			CellStyle timeStyle = book.createCellStyle();
			timeStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("h:mm AM/PM"));
			Row row = sheet.createRow(1);
			row.createCell(0).setCellValue("CHEM");
			row.createCell(1).setCellValue("1A");
			row.createCell(2).setCellValue("General Chemistry");
			row.createCell(3).setCellValue(LocalDateTime.of(2027, 2, 3, 0, 0));
			row.getCell(3).setCellStyle(dateStyle);
			row.createCell(4).setCellValue(LocalTime.of(8, 0).toSecondOfDay() / 86400.0);
			row.getCell(4).setCellStyle(timeStyle);
			row.createCell(5).setCellValue(LocalTime.of(10, 0).toSecondOfDay() / 86400.0);
			row.getCell(5).setCellStyle(timeStyle);
			row.createCell(6).setCellValue("LAB-2");
			row.createCell(7).setCellValue(25);
			book.write(out);
			workbook = out.toByteArray();
		}
		upload("peoplesoft.xlsx", workbook, null).andExpect(status().isOk()).andExpect(jsonPath("$.roomAssignmentsCreated").value(1));
		RoomAssignment chem = imported().get(0);
		assertThat(chem.getExamDate()).isEqualTo(LocalDate.of(2027, 2, 3)); // not 2027-03-02, whatever the display format
		assertThat(chem.getTimeSlot().getStartTime()).isEqualTo(LocalTime.of(8, 0));
		assertThat(chem.getSubjectCode()).isEqualTo("CHEM1A");
		assertThat(chem.getSubjectName()).isEqualTo("General Chemistry");
		assertThat(chem.getStudentCount()).isEqualTo(25);
	}

	@Test
	void odooCsvWithSemicolonsUsesAnExistingTimeSlot() throws Exception {
		json(post("/api/slots"), Map.of("scheduleGroupId", groupId, "label", "Morning", "startTime", "09:00:00", "endTime", "12:00:00", "sortOrder", 1));
		String csv = "﻿Subject;Exam Date;Start Time;End Time;Classroom;Students\r\nBiology;2027-01-12;09:00;12:00;Hall A;40\r\n";
		upload("odoo.csv", csv.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.timeSlotsCreated.length()").value(0));
		RoomAssignment biology = imported().get(0);
		assertThat(biology.getTimeSlot().getLabel()).isEqualTo("Morning");
		assertThat(biology.getSubjectName()).isEqualTo("Biology");
		assertThat(biology.getSubjectCode()).isNull();
	}

	@Test
	void ourOwnTemplateStillWorks() throws Exception {
		json(post("/api/slots"), Map.of("scheduleGroupId", groupId, "label", "Morning", "startTime", "09:00:00", "endTime", "12:00:00", "sortOrder", 1));
		String csv = "Date (YYYY-MM-DD),Time Slot,Room,Subject Name,Subject Code,Students (optional)\n2027-01-13,09:00 - 12:00,Hall A,Algebra,MATH201,35\n";
		upload("template.csv", csv.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isOk());
		RoomAssignment algebra = imported().get(0);
		assertThat(algebra.getSubjectCode()).isEqualTo("MATH201");
		assertThat(algebra.getTimeSlot().getLabel()).isEqualTo("Morning");
	}

	@Test
	void datesLike0304AreOnlyAcceptedWhenTheOrderIsKnown() throws Exception {
		String ambiguous = "Exam Date,Start Time,End Time,Room\n03/04/2027,09:00,11:00,Hall A\n";
		upload("ambiguous.csv", ambiguous.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.validationErrors[0].message").value(org.hamcrest.Matchers.containsString("can mean two dates")));
		assertThat(imported()).isEmpty();

		upload("ambiguous.csv", ambiguous.getBytes(StandardCharsets.UTF_8), "DAY_FIRST").andExpect(status().isOk());
		assertThat(imported().get(0).getExamDate()).isEqualTo(LocalDate.of(2027, 4, 3));

		// The file itself shows the order: 25/03 can only be day first.
		String dayFirst = "Exam Date,Start Time,End Time,Room\n25/03/2027,09:00,11:00,Hall A\n04/05/2027,09:00,11:00,Hall A\n";
		upload("day-first.csv", dayFirst.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isOk());
		assertThat(imported()).extracting(RoomAssignment::getExamDate).contains(LocalDate.of(2027, 3, 25), LocalDate.of(2027, 5, 4));
	}

	@Test
	void oneBadRowMeansNothingIsSavedNotEvenNewTimeSlots() throws Exception {
		int slotsBefore = slots.findByActiveTrueOrderBySortOrderAsc().size();
		String csv = """
			Exam Date,Exam Start Time,Exam End Time,Room,Course Code,Enrollment
			2027-01-14,08:00,10:00,Hall A,ENG101,20
			2027-01-14,08:00,10:00,Unknown Room,ENG102,20
			2027-01-14,10:00,09:00,Hall A,ENG103,20
			2027-01-15,8 o'clock,10:00,Hall A,ENG104,20
			2027-01-16,08:00,10:00,Hall A,ENG105,500
			""";
		String body = upload("bad.csv", csv.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertThat(body).contains("Unknown Room").contains("must end after it starts").contains("8 o'clock").contains("500");
		assertThat(imported()).isEmpty();
		assertThat(slots.findByActiveTrueOrderBySortOrderAsc()).hasSize(slotsBefore);
	}

	@Test
	void aFileWithoutTheEssentialColumnsIsRefusedWithAClearMessage() throws Exception {
		upload("wrong.csv", "Name,Email\nSara,sara@x.test\n".getBytes(StandardCharsets.UTF_8), null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
				org.hamcrest.Matchers.containsString("date"), org.hamcrest.Matchers.containsString("room"), org.hamcrest.Matchers.containsString("Found: Name, Email"))));
		upload("notes.pdf", new byte[] {1, 2, 3}, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Upload an Excel (.xlsx) or CSV (.csv) file."));
	}

	// ─────────────────────────── helpers ───────────────────────────

	private List<RoomAssignment> imported() {
		return assignments.findAllDetailedByScheduleGroupId(UUID.fromString(groupId)).stream()
			.sorted(Comparator.comparing(RoomAssignment::getExamDate).thenComparing(a -> a.getTimeSlot().getStartTime()))
			.toList();
	}

	private ResultActions upload(String filename, byte[] content, String dateOrder) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream", content);
		var request = MockMvcRequestBuilders.multipart("/api/schedule-groups/" + groupId + "/bulk-upload").file(file);
		if (dateOrder != null) {
			request.param("dateOrder", dateOrder);
		}
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest"));
	}

	private ResultActions json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
		ResultActions actions = mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).header(CSRF, "XMLHttpRequest")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
		assertThat(actions.andReturn().getResponse().getStatus()).as(actions.andReturn().getResponse().getContentAsString()).isBetween(200, 299);
		return actions;
	}

	private String id(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private JsonNode readTree(String text) {
		try {
			return objectMapper.readTree(text);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
