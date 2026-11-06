package schedule.example.schedule.audit;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.AuditEvent;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.AuditEventRepository;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.security.JwtTokenProvider;
import schedule.example.schedule.service.SessionService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 2 step 4: the activity log records who changed what, and only administrators can read it. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.junit.jupiter.api.extension.ExtendWith(schedule.example.schedule.tenant.InDefaultUniversity.class)
class AuditLogTest {

	private static final String DOMAIN = "@audit.test";
	private static final String ADMIN = "admin" + DOMAIN;
	private static final String SCHEDULER = "scheduler" + DOMAIN;
	private static final String VIEWER = "viewer" + DOMAIN;
	private static final String PASSWORD = "audit-password-123";
	private static final String CSRF = "X-Requested-With";
	private static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private AuditEventRepository events;
	@Autowired private PersonRepository people;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private AuditService auditService;

	@BeforeEach
	void setUp() {
		events.deleteAll();
		save(ADMIN, AdminRole.ADMIN);
		save(SCHEDULER, AdminRole.SCHEDULER);
		save(VIEWER, AdminRole.VIEWER);
	}

	@AfterEach
	void cleanUp() {
		users.findAll().stream().filter(u -> u.getEmail().endsWith(DOMAIN)).forEach(u -> {
			sessionService.deleteAllForUser(u.getId());
			users.delete(u);
		});
		people.findAll().stream().filter(p -> p.getName().startsWith("Audit ") || p.getName().startsWith("=")).forEach(people::delete);
		events.deleteAll();
	}

	// ── Data changes ────────────────────────────────────────────────────────

	@Test
	void changesAreRecordedWithTheUserAndReadableNamesEvenAfterDeletion() throws Exception {
		String scheduler = token(SCHEDULER);
		JsonNode person = json(mockMvc.perform(authed(post("/api/people"), scheduler).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Audit Person\",\"department\":\"X\",\"role\":\"INVIGILATOR\",\"availableDays\":[\"Sun\"]}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		String id = person.get("id").asText();

		mockMvc.perform(authed(put("/api/people/" + id), scheduler).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Audit Person Renamed\",\"department\":\"X\",\"role\":\"INVIGILATOR\",\"availableDays\":[\"Sun\",\"Mon\"]}"))
			.andExpect(status().isOk());
		mockMvc.perform(authed(delete("/api/people/" + id), scheduler)).andExpect(status().isNoContent());

		List<AuditEvent> log = newestFirst();
		assertThat(log).extracting(AuditEvent::getAction).containsSubsequence("PERSON_DELETED", "PERSON_UPDATED", "PERSON_CREATED");
		AuditEvent deleted = byAction(log, "PERSON_DELETED");
		assertThat(deleted.getSummary()).isEqualTo("Deleted person 'Audit Person Renamed'");
		assertThat(deleted.getActorEmail()).isEqualTo(SCHEDULER);
		assertThat(deleted.getTargetId()).isEqualTo(id);
		assertThat(deleted.getCategory()).isEqualTo("PEOPLE");
		assertThat(deleted.getIpAddress()).isNotBlank();
		assertThat(byAction(log, "PERSON_CREATED").getTargetId()).isEqualTo(id);
	}

	@Test
	void refusedOrFailedRequestsAreNotRecordedAsChangesAndReadsAreNotRecorded() throws Exception {
		String scheduler = token(SCHEDULER);
		mockMvc.perform(authed(post("/api/people"), scheduler).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(authed(get("/api/people"), scheduler)).andExpect(status().isOk());
		mockMvc.perform(authed(post("/api/assignments/generate-preview"), scheduler).contentType(MediaType.APPLICATION_JSON).content("{}"));

		assertThat(newestFirst()).isEmpty();
	}

	// ── Sign-in and security ────────────────────────────────────────────────

	@Test
	void signInsFailuresAndLockoutsAreRecorded() throws Exception {
		login(SCHEDULER, PASSWORD);
		for (int i = 0; i < 5; i++) {
			login(SCHEDULER, "wrong-password-" + i);
		}
		login("nobody" + DOMAIN, "whatever-123");

		List<AuditEvent> log = newestFirst();
		assertThat(byAction(log, "LOGIN_SUCCEEDED").getActorEmail()).isEqualTo(SCHEDULER);
		assertThat(log.stream().filter(e -> e.getAction().equals("LOGIN_FAILED")).count()).isEqualTo(6);
		assertThat(byAction(log, "ACCOUNT_LOCKED").getOutcome()).isEqualTo("DENIED");
		assertThat(log).anyMatch(e -> "nobody@audit.test".equals(e.getActorEmail()) && e.getSummary().contains("no account"));
		// Passwords never end up in the log.
		assertThat(log).noneMatch(e -> e.getSummary().contains("wrong-password") || e.getSummary().contains(PASSWORD));
	}

	@Test
	void refusedAccessAndForgedRequestsAreRecorded() throws Exception {
		mockMvc.perform(authed(post("/api/people"), token(VIEWER)).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/people").cookie(new jakarta.servlet.http.Cookie("ug_access", "x")).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());

		List<AuditEvent> log = newestFirst();
		AuditEvent denied = byAction(log, "ACCESS_DENIED");
		assertThat(denied.getActorEmail()).isEqualTo(VIEWER);
		assertThat(denied.getSummary()).isEqualTo("Access denied: POST /api/people");
		assertThat(byAction(log, "FORGED_REQUEST_BLOCKED").getCategory()).isEqualTo("SECURITY");
	}

	@Test
	void userManagementRecordsBeforeAndAfter() throws Exception {
		AdminUser viewer = users.findByEmailIgnoreCase(VIEWER).orElseThrow();
		mockMvc.perform(authed(put("/api/users/" + viewer.getId()), token(ADMIN)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"Vera\",\"role\":\"SCHEDULER\",\"enabled\":false}"))
			.andExpect(status().isOk());

		AuditEvent change = byAction(newestFirst(), "USER_DISABLED");
		assertThat(change.getSummary()).isEqualTo("Changed user " + VIEWER + ": role Viewer → Scheduler, disabled, name '-' → 'Vera'");
		assertThat(change.getActorEmail()).isEqualTo(ADMIN);
		assertThat(change.getCategory()).isEqualTo("USERS");
	}

	@Test
	void aRefusedUserChangeLeavesNoUserEvent() throws Exception {
		AdminUser admin = users.findByEmailIgnoreCase(ADMIN).orElseThrow();
		mockMvc.perform(authed(put("/api/users/" + admin.getId()), token(ADMIN)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"VIEWER\",\"enabled\":true}"))
			.andExpect(status().isUnprocessableEntity());
		assertThat(newestFirst()).noneMatch(e -> e.getCategory().equals("USERS"));
	}

	// ── Reading the log ─────────────────────────────────────────────────────

	@Test
	void onlyAdministratorsCanReadTheLogAndFiltersWork() throws Exception {
		auditService.record(AuditEntry.success("ROOM_CREATED", AuditCategory.ROOMS, "ROOM", UUID.randomUUID(), "Added room 'Hall 50%_off'"));
		auditService.record(AuditEntry.success("PERSON_CREATED", AuditCategory.PEOPLE, "PERSON", UUID.randomUUID(), "Added person 'Sara'"));

		mockMvc.perform(authed(get("/api/audit-events"), token(SCHEDULER))).andExpect(status().isForbidden());
		mockMvc.perform(authed(get("/api/audit-events"), token(VIEWER))).andExpect(status().isForbidden());

		String admin = token(ADMIN);
		mockMvc.perform(authed(get("/api/audit-events").param("category", "rooms"), admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].summary").value("Added room 'Hall 50%_off'"));
		// "%" and "_" typed by the user are matched literally, not as wildcards.
		mockMvc.perform(authed(get("/api/audit-events").param("q", "50%_"), admin)).andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(authed(get("/api/audit-events").param("q", "%"), admin)).andExpect(jsonPath("$.totalElements").value(1));
		mockMvc.perform(authed(get("/api/audit-events").param("from", Instant.now().plus(1, ChronoUnit.HOURS).toString()), admin))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void theLogCannotBeChangedThroughTheApi() throws Exception {
		auditService.record(AuditEntry.success("ROOM_CREATED", AuditCategory.ROOMS, "ROOM", null, "Added room 'A'"));
		String id = events.findAll().get(0).getId().toString();
		String admin = token(ADMIN);
		mockMvc.perform(authed(delete("/api/audit-events/" + id), admin)).andExpect(status().is4xxClientError());
		mockMvc.perform(authed(put("/api/audit-events/" + id), admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().is4xxClientError());
		assertThat(events.findById(UUID.fromString(id))).isPresent();
	}

	@Test
	void csvExportNeutralisesSpreadsheetFormulasAndIsItselfRecorded() throws Exception {
		mockMvc.perform(authed(post("/api/people"), token(SCHEDULER)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"=HYPERLINK(\\\"http://evil\\\",\\\"x\\\")\",\"department\":\"X\",\"role\":\"INVIGILATOR\",\"availableDays\":[\"Sun\"]}"))
			.andExpect(status().isCreated());

		byte[] bytes = mockMvc.perform(authed(get("/api/audit-events/export"), token(ADMIN)))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment")))
			.andReturn().getResponse().getContentAsByteArray();
		String csv = new String(bytes, StandardCharsets.UTF_8);

		assertThat(csv).startsWith("﻿\"Time (UTC)\"");
		assertThat(csv).contains("\"Added person '=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")'\"");
		assertThat(AuditController.cell("=1+1")).isEqualTo("\"'=1+1\"");
		assertThat(AuditController.cell("-2")).isEqualTo("\"'-2\"");
		assertThat(AuditController.cell("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
		assertThat(AuditController.cell("Hall A")).isEqualTo("\"Hall A\"");
		assertThat(newestFirst()).anyMatch(e -> e.getAction().equals("ACTIVITY_LOG_EXPORTED") && ADMIN.equals(e.getActorEmail()));
	}

	@Test
	void oldEntriesAreRemovedByTheRetentionJob() {
		auditService.record(AuditEntry.success("ROOM_CREATED", AuditCategory.ROOMS, null, null, "old"));
		AuditEvent old = events.findAll().get(0);
		old.setOccurredAt(Instant.now().minus(400, ChronoUnit.DAYS));
		events.save(old);
		auditService.record(AuditEntry.success("ROOM_CREATED", AuditCategory.ROOMS, null, null, "recent"));

		auditService.applyRetention();

		assertThat(events.findAll()).extracting(AuditEvent::getSummary).containsExactly("recent");
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private void save(String email, AdminRole role) {
		users.save(AdminUser.builder().universityId(schedule.example.schedule.tenant.TenantContext.DEFAULT_UNIVERSITY_ID).email(email).passwordHash(passwordEncoder.encode(PASSWORD)).role(role).build());
	}

	private String token(String email) {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private void login(String email, String password) throws Exception {
		String ip = "192.0.2." + (IP.getAndIncrement() % 250 + 1);
		mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
			.header(CSRF, "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password))));
	}

	private List<AuditEvent> newestFirst() {
		return events.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "occurredAt"));
	}

	private static AuditEvent byAction(List<AuditEvent> log, String action) {
		return log.stream().filter(e -> e.getAction().equals(action)).findFirst()
			.orElseThrow(() -> new AssertionError("no " + action + " in " + log.stream().map(AuditEvent::getAction).toList()));
	}

	private JsonNode json(String body) throws Exception {
		return objectMapper.readTree(body);
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}
}
