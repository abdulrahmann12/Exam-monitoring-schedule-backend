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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.security.JwtTokenProvider;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.tenant.UniversityProvisioning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports: sessions and hours per person (and the CSV for finance), workload per department,
 * fairness and coverage gaps. Each test runs in its own new university, so the numbers are exact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportTest {

	private static final String PASSWORD = "report-password-1";
	private static final String CSRF = "X-Requested-With";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private UniversityProvisioning provisioning;

	private final List<String> createdUsers = new ArrayList<>();
	private UUID university;
	private String admin;
	private String groupId;
	private Map<String, String> people;
	private String gapRoomAssignment;

	@BeforeEach
	void setUp() throws Exception {
		university = provisioning.create("Report University", "report-" + UUID.randomUUID().toString().substring(0, 8)).getId();
		admin = token(newUser(AdminRole.ADMIN, university));
		buildSchedule();
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

	/**
	 * Morning 09:00–12:00 (3 h), afternoon 13:00–14:30 (1.5 h) on 2027-01-10.
	 * Chief Amal: Hall A + Hall B in the morning (one sitting) and Hall C in the afternoon.
	 * Invigilator Basem (Maths dept.): Hall A morning + Hall C afternoon. Invigilator Hoda: Hall B morning.
	 * Invigilator Karim: no duty. Hall C needs 2 invigilators but has 1 (a coverage gap); Hall D has no chief.
	 */
	private void buildSchedule() throws Exception {
		groupId = id(perform(post("/api/schedule-groups"), Map.of("name", "Report Period")));
		String morning = id(perform(post("/api/slots"), Map.of("scheduleGroupId", groupId, "label", "Morning",
			"startTime", "09:00:00", "endTime", "12:00:00", "sortOrder", 1)));
		String afternoon = id(perform(post("/api/slots"), Map.of("scheduleGroupId", groupId, "label", "Afternoon",
			"startTime", "13:00:00", "endTime", "14:30:00", "sortOrder", 2)));
		String hallA = id(perform(post("/api/rooms"), room("Hall A", 1)));
		String hallB = id(perform(post("/api/rooms"), room("Hall B", 1)));
		String hallC = id(perform(post("/api/rooms"), room("Hall C", 2)));
		String hallD = id(perform(post("/api/rooms"), room("Hall D", 1)));
		people = new HashMap<>();
		people.put("amal", id(perform(post("/api/people"), person("Amal", "Physics", "CHIEF_INVIGILATOR", "amal@report.test"))));
		people.put("basem", id(perform(post("/api/people"), person("Basem", "Maths", "INVIGILATOR", "basem@report.test"))));
		people.put("hoda", id(perform(post("/api/people"), person("Hoda", "Physics", "INVIGILATOR", null))));
		people.put("karim", id(perform(post("/api/people"), person("=HYPERLINK(\"http://evil\")", "Maths", "INVIGILATOR", null))));
		people.put("chief2", id(perform(post("/api/people"), person("Zaid", "Physics", "CHIEF_INVIGILATOR", null))));

		perform(post("/api/assignments"), assignment(hallA, morning, people.get("amal"), List.of(people.get("basem"))));
		perform(post("/api/assignments"), assignment(hallB, morning, people.get("amal"), List.of(people.get("hoda"))));
		gapRoomAssignment = id(perform(post("/api/assignments"), assignment(hallC, afternoon, people.get("amal"), List.of(people.get("basem")))));
		perform(post("/api/assignments"), assignment(hallD, afternoon, null, List.of(people.get("hoda"))));
	}

	@Test
	void sessionsAndHoursPerPersonCountASittingOnceEvenWithTwoRooms() throws Exception {
		JsonNode report = json(mockMvc.perform(authed(get("/api/reports/workload?scheduleGroupId=" + groupId), admin)).andExpect(status().isOk()));
		JsonNode amal = row(report, "Amal");
		assertThat(amal.get("sessions").asInt()).isEqualTo(2);          // morning (2 rooms at once) + afternoon
		assertThat(amal.get("hours").decimalValue()).isEqualByComparingTo("4.50");
		assertThat(amal.get("roomsAsChief").asInt()).isEqualTo(3);
		assertThat(amal.get("days").asInt()).isEqualTo(1);
		JsonNode basem = row(report, "Basem");
		assertThat(basem.get("sessions").asInt()).isEqualTo(2);
		assertThat(basem.get("hours").decimalValue()).isEqualByComparingTo("4.50");
		assertThat(basem.get("roomsAsInvigilator").asInt()).isEqualTo(2);
		JsonNode hoda = row(report, "Hoda");
		assertThat(hoda.get("hours").decimalValue()).isEqualByComparingTo("4.50");
		JsonNode karim = row(report, "=HYPERLINK(\"http://evil\")");
		assertThat(karim.get("sessions").asInt()).isZero();             // staff without duty are listed too
		assertThat(report.get("staff").asInt()).isEqualTo(5);
		assertThat(report.get("hours").decimalValue()).isEqualByComparingTo("13.50");
	}

	@Test
	void theFinanceExportOpensInExcelAndCannotRunFormulas() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(authed(get("/api/reports/workload/export?scheduleGroupId=" + groupId), admin))
			.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(response.getContentType()).startsWith("text/csv");
		assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("workload-report-period.csv");
		String csv = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿");
		assertThat(csv).contains("\"Name\",\"Email\",\"Department\",\"Role\",\"Sessions\",\"Hours\"");
		assertThat(csv).contains("\"Amal\",\"amal@report.test\",\"Physics\",\"Chief invigilator\",\"2\",\"4.50\",\"3\",\"0\",\"1\",\"2027-01-10\",\"2027-01-10\"");
		assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");   // stays text in Excel
		assertThat(csv).contains("\"Total\",\"\",\"\",\"\",\"6\",\"13.50\"");
		mockMvc.perform(authed(get("/api/audit-events?size=20"), admin))
			.andExpect(jsonPath("$.content[0].summary").value("Exported sessions and hours per person for 'Report Period'"));
	}

	@Test
	void analyticsShowFairnessDepartmentsAndCoverageGaps() throws Exception {
		JsonNode analytics = json(mockMvc.perform(authed(get("/api/reports/analytics?scheduleGroupId=" + groupId), admin)).andExpect(status().isOk()));

		// Invigilators: Basem 4.5 h, Hoda 4.5 h, Karim 0 h -> Gini 1/3 -> score 67.
		JsonNode invigilators = find(analytics.get("fairness"), "role", "INVIGILATOR");
		assertThat(invigilators.get("staff").asInt()).isEqualTo(3);
		assertThat(invigilators.get("withoutDuty").asInt()).isEqualTo(1);
		assertThat(invigilators.get("score").asInt()).isEqualTo(67);
		assertThat(invigilators.get("maxHours").decimalValue()).isEqualByComparingTo("4.50");
		// Chiefs: Amal 4.5 h, Zaid 0 h -> Gini 1/2 -> score 50.
		assertThat(find(analytics.get("fairness"), "role", "CHIEF_INVIGILATOR").get("score").asInt()).isEqualTo(50);

		JsonNode physics = find(analytics.get("departments"), "department", "Physics");
		assertThat(physics.get("staff").asInt()).isEqualTo(3);
		assertThat(physics.get("hours").decimalValue()).isEqualByComparingTo("9.00");
		assertThat(physics.get("shareOfHours").decimalValue()).isEqualByComparingTo("66.7");
		JsonNode maths = find(analytics.get("departments"), "department", "Maths");
		assertThat(maths.get("hoursPerPerson").decimalValue()).isEqualByComparingTo("2.25");

		JsonNode coverage = analytics.get("coverage");
		assertThat(coverage.get("examRooms").asInt()).isEqualTo(4);
		assertThat(coverage.get("withGaps").asInt()).isEqualTo(2);
		assertThat(coverage.get("missingInvigilators").asInt()).isEqualTo(1);
		assertThat(coverage.get("missingChiefs").asInt()).isEqualTo(1);
		JsonNode hallC = find(coverage.get("gaps"), "room", "Hall C");
		assertThat(hallC.get("assignmentId").asText()).isEqualTo(gapRoomAssignment);
		assertThat(hallC.get("requiredInvigilators").asInt()).isEqualTo(2);
		assertThat(hallC.get("assignedInvigilators").asInt()).isEqualTo(1);
		assertThat(find(coverage.get("gaps"), "room", "Hall D").get("chiefMissing").asBoolean()).isTrue();
	}

	@Test
	void reportsAreReadableByViewersButNeverAcrossUniversities() throws Exception {
		String viewer = token(newUser(AdminRole.VIEWER, university));
		mockMvc.perform(authed(get("/api/reports/workload?scheduleGroupId=" + groupId), viewer)).andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/reports/analytics?scheduleGroupId=" + groupId), viewer)).andExpect(status().isOk());

		String otherAdmin = token(newUser(AdminRole.ADMIN, TenantContext.DEFAULT_UNIVERSITY_ID));
		mockMvc.perform(authed(get("/api/reports/workload?scheduleGroupId=" + groupId), otherAdmin)).andExpect(status().isNotFound());
		mockMvc.perform(authed(get("/api/reports/workload/export?scheduleGroupId=" + groupId), otherAdmin)).andExpect(status().isNotFound());
		mockMvc.perform(authed(get("/api/reports/analytics?scheduleGroupId=" + groupId), otherAdmin)).andExpect(status().isNotFound());
		mockMvc.perform(authed(get("/api/reports/workload"), admin)).andExpect(status().isBadRequest()) // the exam period is required
			.andExpect(jsonPath("$.message").value("Missing required parameter: scheduleGroupId"));
	}

	// ─────────────────────────── helpers ───────────────────────────

	private static Map<String, Object> room(String name, int minInvigilators) {
		return Map.of("name", name, "capacity", 40, "type", "MEDIUM", "minInvigilators", minInvigilators, "hallGender", "MIXED");
	}

	private static Map<String, Object> person(String name, String department, String role, String email) {
		Map<String, Object> body = new HashMap<>(Map.of("name", name, "department", department, "role", role,
			"availableDays", List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")));
		if (email != null) {
			body.put("email", email);
		}
		return body;
	}

	private Map<String, Object> assignment(String roomId, String slotId, String chiefId, List<String> invigilators) {
		Map<String, Object> body = new HashMap<>(Map.of("scheduleGroupId", groupId, "examDate", "2027-01-10", "roomId", roomId,
			"timeSlotId", slotId, "subjectName", "Exam", "locked", false, "invigilatorIds", invigilators));
		if (chiefId != null) {
			body.put("chiefInvigilatorId", chiefId);
		}
		return body;
	}

	private static JsonNode row(JsonNode report, String name) {
		return find(report.get("people"), "name", name);
	}

	private static JsonNode find(JsonNode array, String field, String value) {
		for (JsonNode node : array) {
			if (value.equals(node.get(field).asText())) {
				return node;
			}
		}
		throw new AssertionError("No " + field + "=" + value + " in " + array);
	}

	private ResultActions perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
		ResultActions actions = mockMvc.perform(authed(builder, admin).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
		int status = actions.andReturn().getResponse().getStatus();
		assertThat(status).as(actions.andReturn().getResponse().getContentAsString()).isBetween(200, 299);
		return actions;
	}

	private String id(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private String newUser(AdminRole role, UUID universityId) {
		String email = "report-" + UUID.randomUUID().toString().substring(0, 8) + "@report.test";
		users.save(AdminUser.builder().email(email).passwordHash(passwordEncoder.encode(PASSWORD)).role(role).universityId(universityId).build());
		createdUsers.add(email);
		return email;
	}

	private String token(String email) {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}
}
