package schedule.example.schedule.tenant;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.University;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.security.JwtTokenProvider;
import schedule.example.schedule.service.SessionService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 step 5: two universities on one server. Everything a user of university B does must be
 * blind to university A — lists, direct ids, dashboards, settings, users, the activity log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiUniversityIsolationTest {

	private static final String A_ADMIN = "admin-a@isolation.test";
	private static final String B_ADMIN = "admin-b@isolation.test";
	private static final String PASSWORD = "isolation-pass-123";
	private static final String CSRF = "X-Requested-With";
	private static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private UniversityProvisioning provisioning;
	@Autowired private UniversityRepository universities;
	@Autowired private AdminUserRepository users;
	@Autowired private RoomRepository rooms;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private schedule.example.schedule.repository.SettingsRepository settingsRepository;
	@Autowired private schedule.example.schedule.service.ScheduleGroupSupport groupSupport;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

	private UUID universityB;
	private String a;
	private String b;

	@BeforeEach
	void setUp() {
		University created = provisioning.create("University B", "uni-b-" + UUID.randomUUID().toString().substring(0, 8));
		universityB = created.getId();
		save(A_ADMIN, TenantContext.DEFAULT_UNIVERSITY_ID);
		save(B_ADMIN, universityB);
		a = token(A_ADMIN);
		b = token(B_ADMIN);
	}

	@AfterEach
	void cleanUp() {
		for (String email : new String[] {A_ADMIN, B_ADMIN, "b-scheduler@isolation.test"}) {
			users.findByEmailIgnoreCase(email).ifPresent(u -> {
				sessionService.deleteAllForUser(u.getId());
				users.delete(u);
			});
		}
	}

	@Test
	void anotherUniversitysDataIsInvisibleInListsAndByDirectId() throws Exception {
		String roomId = id(created(post("/api/rooms"), a, room("Isolation Hall A")));
		String personId = id(created(post("/api/people"), a, Map.of("name", "Isolation Sara A", "department", "X",
			"role", "INVIGILATOR", "availableDays", new String[] {"Sun"})));
		String groupId = id(created(post("/api/schedule-groups"), a, Map.of("name", "Isolation Period A")));

		String bRooms = body(mockMvc.perform(authed(get("/api/rooms?size=100"), b)).andExpect(status().isOk()));
		String bPeople = body(mockMvc.perform(authed(get("/api/people?size=100"), b)).andExpect(status().isOk()));
		String bGroups = body(mockMvc.perform(authed(get("/api/schedule-groups?size=100"), b)).andExpect(status().isOk()));
		assertThat(bRooms).doesNotContain("Isolation Hall A");
		assertThat(bPeople).doesNotContain("Isolation Sara A");
		assertThat(bGroups).doesNotContain("Isolation Period A");

		// Guessing the ids does not help: 404, exactly as if they did not exist.
		mockMvc.perform(authed(put("/api/rooms/" + roomId), b).contentType(MediaType.APPLICATION_JSON).content(json(room("Hacked"))))
			.andExpect(status().isNotFound());
		mockMvc.perform(authed(delete("/api/rooms/" + roomId), b)).andExpect(status().isNotFound());
		mockMvc.perform(authed(delete("/api/people/" + personId), b)).andExpect(status().isNotFound());
		mockMvc.perform(authed(get("/api/schedule-groups/" + groupId), b)).andExpect(status().isNotFound());
		mockMvc.perform(authed(delete("/api/schedule-groups/" + groupId), b)).andExpect(status().isNotFound());
		mockMvc.perform(authed(get("/api/people/" + personId + "/unavailability"), b)).andExpect(status().isNotFound());

		// Still intact for university A.
		assertThat(body(mockMvc.perform(authed(get("/api/rooms?size=100"), a)))).contains("Isolation Hall A");
		mockMvc.perform(authed(delete("/api/rooms/" + roomId), a)).andExpect(status().isNoContent());
		mockMvc.perform(authed(delete("/api/people/" + personId), a)).andExpect(status().isNoContent());
		mockMvc.perform(authed(delete("/api/schedule-groups/" + groupId), a)).andExpect(status().isNoContent());
	}

	@Test
	void anotherUniversitysRoomsCannotBeUsedInSchedules() throws Exception {
		String roomA = id(created(post("/api/rooms"), a, room("Isolation Shared Hall")));
		String groupB = body(mockMvc.perform(authed(get("/api/schedule-groups?size=100"), b)));
		String bGroupId = objectMapper.readTree(groupB).get("content").get(0).get("id").asText();
		String slotB = id(created(post("/api/slots"), b, Map.of("scheduleGroupId", bGroupId, "label", "Morning",
			"startTime", "09:00:00", "endTime", "12:00:00", "sortOrder", 1)));

		mockMvc.perform(authed(post("/api/assignments"), b).contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
				"scheduleGroupId", bGroupId, "examDate", "2027-01-10", "roomId", roomA, "timeSlotId", slotB,
				"locked", false, "invigilatorIds", new String[0]))))
			.andExpect(status().isNotFound());
		mockMvc.perform(authed(post("/api/assignments/generate-preview"), b).contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
				"scheduleGroupId", bGroupId, "examDate", "2027-01-10", "slotId", slotB, "roomIds", new String[] {roomA},
				"regenerateUnlocked", false, "current", new Object[0]))))
			.andExpect(status().is4xxClientError());

		mockMvc.perform(authed(delete("/api/rooms/" + roomA), a)).andExpect(status().isNoContent());
	}

	@Test
	void namesOnlyHaveToBeUniqueWithinOneUniversity() throws Exception {
		String inA = id(created(post("/api/rooms"), a, room("Isolation Same Name")));
		String inB = id(created(post("/api/rooms"), b, room("Isolation Same Name")));
		mockMvc.perform(authed(post("/api/rooms"), a).contentType(MediaType.APPLICATION_JSON).content(json(room("Isolation Same Name"))))
			.andExpect(status().is4xxClientError());

		mockMvc.perform(authed(delete("/api/rooms/" + inA), a)).andExpect(status().isNoContent());
		mockMvc.perform(authed(delete("/api/rooms/" + inB), b)).andExpect(status().isNoContent());
	}

	@Test
	void settingsAndDashboardsAreSeparateIncludingTheirCaches() throws Exception {
		mockMvc.perform(authed(get("/api/settings"), a)).andExpect(status().isOk()); // warm A's cache entry
		JsonNode settingsA = objectMapper.readTree(body(mockMvc.perform(authed(get("/api/settings"), a))));
		var changed = objectMapper.convertValue(settingsA, java.util.Map.class);
		changed.put("systemName", "Isolation A System");
		changed.remove("id");
		mockMvc.perform(authed(put("/api/settings"), a).contentType(MediaType.APPLICATION_JSON).content(json(changed)))
			.andExpect(status().isOk());

		mockMvc.perform(authed(get("/api/settings"), b))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.systemName").value(org.hamcrest.Matchers.not("Isolation A System")))
			.andExpect(jsonPath("$.universityName").value("University B"));
		mockMvc.perform(authed(get("/api/settings"), a)).andExpect(jsonPath("$.systemName").value("Isolation A System"));

		String roomA = id(created(post("/api/rooms"), a, room("Isolation Dashboard Hall")));
		mockMvc.perform(authed(get("/api/dashboard/summary"), b)).andExpect(status().isOk()).andExpect(jsonPath("$.totalRooms").value(0));
		String groupA = objectMapper.readTree(body(mockMvc.perform(authed(get("/api/schedule-groups?size=100"), a))))
			.get("content").get(0).get("id").asText();
		mockMvc.perform(authed(get("/api/dashboard/summary?scheduleGroupId=" + groupA), b)).andExpect(status().isNotFound());

		mockMvc.perform(authed(delete("/api/rooms/" + roomA), a)).andExpect(status().isNoContent());
		changed.put("systemName", settingsA.get("systemName").asText());
		mockMvc.perform(authed(put("/api/settings"), a).contentType(MediaType.APPLICATION_JSON).content(json(changed)));
	}

	@Test
	void administratorsOnlySeeAndManageTheirOwnUniversitysUsers() throws Exception {
		String bUsers = body(mockMvc.perform(authed(get("/api/users"), b)).andExpect(status().isOk()));
		assertThat(bUsers).contains(B_ADMIN).doesNotContain(A_ADMIN).doesNotContain("admin@test.local");

		UUID aAdminId = users.findByEmailIgnoreCase(A_ADMIN).orElseThrow().getId();
		mockMvc.perform(authed(put("/api/users/" + aAdminId), b).contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"VIEWER\",\"enabled\":false}"))
			.andExpect(status().isNotFound());
		mockMvc.perform(authed(delete("/api/users/" + aAdminId), b)).andExpect(status().isNotFound());
		mockMvc.perform(authed(post("/api/users/" + aAdminId + "/unlock"), b)).andExpect(status().isNotFound());

		mockMvc.perform(authed(post("/api/users"), b).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"b-scheduler@isolation.test\",\"role\":\"SCHEDULER\",\"temporaryPassword\":\"temporary-pass-1\"}"))
			.andExpect(status().isCreated());
		assertThat(users.findByEmailIgnoreCase("b-scheduler@isolation.test").orElseThrow().getUniversityId()).isEqualTo(universityB);
		assertThat(users.findByEmailIgnoreCase(A_ADMIN).orElseThrow().isEnabled()).isTrue();
	}

	@Test
	void theActivityLogShowsOnlyYourUniversity() throws Exception {
		String roomA = id(created(post("/api/rooms"), a, room("Isolation Logged Hall")));
		assertThat(body(mockMvc.perform(authed(get("/api/audit-events?size=100"), a)))).contains("Isolation Logged Hall");
		assertThat(body(mockMvc.perform(authed(get("/api/audit-events?size=100"), b)).andExpect(status().isOk())))
			.doesNotContain("Isolation Logged Hall");
		mockMvc.perform(authed(delete("/api/rooms/" + roomA), a)).andExpect(status().isNoContent());
	}

	@Test
	void aUniversityWithoutSettingsGetsItsOwnNotAnotherOnes() throws Exception {
		UUID bare = UUID.randomUUID();
		provisioning.ensureUniversity(bare, "Bare University", "bare-" + bare.toString().substring(0, 8));
		save("admin-bare@isolation.test", bare);
		// Like a live install: the exam period exists already, only the settings row is missing.
		TenantContext.runAs(bare, () -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
			.executeWithoutResult(status -> groupSupport.getOrCreateDefaultGroup()));
		try {
			mockMvc.perform(authed(get("/api/settings"), a)).andExpect(status().isOk()); // A's settings are cached
			mockMvc.perform(authed(get("/api/settings"), token("admin-bare@isolation.test")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.universityName").value("Bare University"));
			assertThat(settingsRepository.existsById(bare)).isTrue();
		} finally {
			users.findByEmailIgnoreCase("admin-bare@isolation.test").ifPresent(u -> {
				sessionService.deleteAllForUser(u.getId());
				users.delete(u);
			});
		}
	}

	@Test
	void meTellsTheUserTheirUniversity() throws Exception {
		mockMvc.perform(authed(get("/api/auth/me"), b))
			.andExpect(jsonPath("$.universityId").value(universityB.toString()))
			.andExpect(jsonPath("$.universityName").value("University B"));
	}

	@Test
	void aDeactivatedUniversityCannotSignInOrKeepWorking() throws Exception {
		mockMvc.perform(authed(get("/api/rooms"), b)).andExpect(status().isOk());
		University uni = universities.findById(universityB).orElseThrow();
		uni.setActive(false);
		universities.save(uni);

		mockMvc.perform(authed(get("/api/rooms"), b)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("192.0.2." + IP.incrementAndGet()); return r; })
				.header(CSRF, "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("email", B_ADMIN, "password", PASSWORD))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("paused")));
		// University A is not affected.
		mockMvc.perform(authed(get("/api/rooms"), a)).andExpect(status().isOk());
	}

	@Test
	void systemCodeCannotSaveUniversityDataWithoutAUniversity() {
		Room orphan = new Room();
		orphan.setName("Isolation Orphan");
		orphan.setCapacity(10);
		orphan.setType(RoomType.SMALL);
		orphan.setMinInvigilators(1);
		orphan.setActive(true);
		assertThatThrownBy(() -> rooms.saveAndFlush(orphan)).hasStackTraceContaining("without a university");
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private void save(String email, UUID universityId) {
		users.save(AdminUser.builder().email(email).passwordHash(passwordEncoder.encode(PASSWORD))
			.role(AdminRole.ADMIN).universityId(universityId).build());
	}

	private String token(String email) {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private static Map<String, Object> room(String name) {
		return Map.of("name", name, "capacity", 30, "type", "SMALL", "minInvigilators", 1, "hallGender", "MIXED");
	}

	private ResultActions created(MockHttpServletRequestBuilder request, String token, Object payload) throws Exception {
		return mockMvc.perform(authed(request, token).contentType(MediaType.APPLICATION_JSON).content(json(payload)))
			.andExpect(status().isCreated());
	}

	private String id(ResultActions actions) throws Exception {
		return objectMapper.readTree(body(actions)).get("id").asText();
	}

	private static String body(ResultActions actions) throws Exception {
		return actions.andReturn().getResponse().getContentAsString();
	}

	private String json(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}
}
