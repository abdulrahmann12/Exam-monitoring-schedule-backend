package schedule.example.schedule.owner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.security.JwtTokenProvider;
import schedule.example.schedule.service.SessionService;
import schedule.example.schedule.tenant.TenantContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 step 6: the platform owner (super-admin). The owner runs universities from its own panel,
 * but never sees a university's data; university users never reach the owner panel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OwnerPanelTest {

	private static final String OWNER = "owner@test.local";
	private static final String OWNER_PASSWORD = "owner-password-1";
	private static final String DEFAULT_ADMIN = "admin@test.local";
	private static final String CSRF = "X-Requested-With";
	private static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private UniversityRepository universities;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private PlatformTransactionManager transactionManager;

	@Test
	void theOwnerIsCreatedFromTheServerSettingsWithoutAUniversity() throws Exception {
		AdminUser owner = users.findByEmailIgnoreCase(OWNER).orElseThrow();
		assertThat(owner.getRole()).isEqualTo(AdminRole.SUPER_ADMIN);
		assertThat(owner.getUniversityId()).isNull();

		login(OWNER, OWNER_PASSWORD).andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/auth/me"), token(OWNER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
			.andExpect(jsonPath("$.universityId").doesNotExist());
	}

	@Test
	void ownerCreatesAUniversityAndItsFirstAdministratorStartsWorkingInIt() throws Exception {
		String slug = slug();
		String email = "first-admin-" + slug + "@owner.test";
		JsonNode created = json(mockMvc.perform(ownerPost("/api/owner/universities", Map.of(
				"name", "Owner Test University " + slug, "slug", slug, "adminEmail", email, "adminName", "Dr. First")))
			.andExpect(status().isCreated()));
		assertThat(created.get("inviteLink").asText()).contains("#token=");
		JsonNode university = created.get("university");
		assertThat(university.get("active").asBoolean()).isTrue();
		assertThat(university.get("accounts").asLong()).isEqualTo(1);
		assertThat(university.get("admins").get(0).get("email").asText()).isEqualTo(email);
		assertThat(university.get("admins").get(0).get("invitationPending").asBoolean()).isTrue();

		// The administrator chooses a password from the link and signs in to an empty university.
		acceptInvite(created.get("inviteLink").asText(), "first-admin-pass-1");
		login(email, "first-admin-pass-1").andExpect(status().isOk());
		String admin = token(email);
		mockMvc.perform(authed(get("/api/auth/me"), admin))
			.andExpect(jsonPath("$.universityName").value("Owner Test University " + slug));
		mockMvc.perform(authed(get("/api/settings"), admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.universityName").value("Owner Test University " + slug));
		assertThat(json(mockMvc.perform(authed(get("/api/rooms?size=100"), admin))).get("content")).isEmpty();
		mockMvc.perform(authed(post("/api/rooms"), admin).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Owner Hall", "capacity", 30, "type", "SMALL",
					"minInvigilators", 1, "hallGender", "MIXED"))))
			.andExpect(status().isCreated());

		// The owner sees the usage (counts), not the data.
		JsonNode summary = universityInOverview(university.get("id").asText());
		assertThat(summary.get("rooms").asLong()).isEqualTo(1);
		assertThat(summary.get("lastSignInAt").isNull()).isFalse();
		assertThat(summary.get("admins").get(0).get("signedIn").asBoolean()).isTrue();
		assertThat(summary.toString()).doesNotContain("Owner Hall");
	}

	@Test
	void refusedRequestsCreateNothing() throws Exception {
		String slug = slug();
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "Taken Name " + slug, "slug", slug,
			"adminEmail", "a-" + slug + "@owner.test"))).andExpect(status().isCreated());
		long before = universities.count();

		// Bad short code, short code taken, name taken (any case), email already used by an account.
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "X " + slug, "slug", "Bad Slug!", "adminEmail", "b-" + slug + "@owner.test")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "Y " + slug, "slug", slug, "adminEmail", "c-" + slug + "@owner.test")))
			.andExpect(status().isConflict());
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "taken name " + slug, "slug", slug + "-2", "adminEmail", "d-" + slug + "@owner.test")))
			.andExpect(status().isConflict());
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "Z " + slug, "slug", slug + "-3", "adminEmail", DEFAULT_ADMIN)))
			.andExpect(status().isConflict());
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "W " + slug, "slug", slug + "-4", "adminEmail", OWNER)))
			.andExpect(status().isConflict());

		assertThat(universities.count()).isEqualTo(before);
		assertThat(users.findByEmailIgnoreCase(DEFAULT_ADMIN).orElseThrow().getUniversityId()).isEqualTo(TenantContext.DEFAULT_UNIVERSITY_ID);
	}

	@Test
	void switchingAUniversityOffSignsItsUsersOutAndBlocksSignInUntilSwitchedOn() throws Exception {
		String slug = slug();
		String email = "paused-" + slug + "@owner.test";
		JsonNode created = json(mockMvc.perform(ownerPost("/api/owner/universities", Map.of(
			"name", "Paused University " + slug, "slug", slug, "adminEmail", email))).andExpect(status().isCreated()));
		String id = created.get("university").get("id").asText();
		acceptInvite(created.get("inviteLink").asText(), "paused-admin-pass-1");
		String cookie = login(email, "paused-admin-pass-1").andExpect(status().isOk())
			.andReturn().getResponse().getCookie("ug_access").getValue();
		mockMvc.perform(get("/api/rooms").cookie(new Cookie("ug_access", cookie))).andExpect(status().isOk());

		mockMvc.perform(ownerPut("/api/owner/universities/" + id, Map.of("name", "Paused University " + slug, "active", false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));
		// The open session stops at once; signing in is refused with a clear message.
		mockMvc.perform(get("/api/rooms").cookie(new Cookie("ug_access", cookie))).andExpect(status().isUnauthorized());
		login(email, "paused-admin-pass-1").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("paused")));
		// Other universities are not affected.
		login(DEFAULT_ADMIN, "test-password").andExpect(status().isOk());

		mockMvc.perform(ownerPut("/api/owner/universities/" + id, Map.of("name", "Renamed University " + slug, "active", true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renamed University " + slug));
		login(email, "paused-admin-pass-1").andExpect(status().isOk());
	}

	@Test
	void theOwnerSeesNoUniversityData() throws Exception {
		String owner = token(OWNER);
		for (String path : new String[] {"/api/people", "/api/rooms", "/api/slots", "/api/schedule-groups", "/api/settings",
			"/api/users", "/api/audit-events", "/api/dashboard/summary"}) {
			mockMvc.perform(authed(get(path), owner)).andExpect(status().isForbidden());
		}
		mockMvc.perform(authed(post("/api/rooms"), owner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Owner Room\",\"capacity\":10,\"type\":\"SMALL\",\"minInvigilators\":1,\"hallGender\":\"MIXED\"}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(authed(put("/api/settings"), owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void universityUsersCannotReachTheOwnerPanel() throws Exception {
		String admin = token(DEFAULT_ADMIN);
		mockMvc.perform(authed(get("/api/owner/universities"), admin)).andExpect(status().isForbidden());
		mockMvc.perform(authed(get("/api/owner/activity"), admin)).andExpect(status().isForbidden());
		mockMvc.perform(authed(post("/api/owner/universities"), admin).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Sneaky", "slug", "sneaky", "adminEmail", "x@sneaky.test"))))
			.andExpect(status().isForbidden());
		mockMvc.perform(authed(put("/api/owner/universities/" + TenantContext.DEFAULT_UNIVERSITY_ID), admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"University\",\"active\":false}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/owner/universities").header(CSRF, "XMLHttpRequest")).andExpect(status().isUnauthorized());
		assertThat(universities.findById(TenantContext.DEFAULT_UNIVERSITY_ID).orElseThrow().isActive()).isTrue();
	}

	@Test
	void universityAdministratorsCannotSeeChangeOrCreateTheOwner() throws Exception {
		String admin = token(DEFAULT_ADMIN);
		UUID ownerId = users.findByEmailIgnoreCase(OWNER).orElseThrow().getId();
		assertThat(json(mockMvc.perform(authed(get("/api/users"), admin)).andExpect(status().isOk())).toString()).doesNotContain(OWNER);
		mockMvc.perform(authed(put("/api/users/" + ownerId), admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"x\",\"role\":\"VIEWER\",\"enabled\":false}"))
			.andExpect(status().isNotFound());
		mockMvc.perform(authed(post("/api/users/" + ownerId + "/reset-password"), admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"temporaryPassword\":\"temporary-pass-1\"}"))
			.andExpect(status().isNotFound());
		mockMvc.perform(authed(delete("/api/users/" + ownerId), admin)).andExpect(status().isNotFound());
		mockMvc.perform(authed(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", "new-owner-" + slug() + "@owner.test", "role", "SUPER_ADMIN",
					"invite", false, "temporaryPassword", "temporary-pass-1"))))
			.andExpect(status().isBadRequest());
		assertThat(users.findByEmailIgnoreCase(OWNER).orElseThrow().isEnabled()).isTrue();
	}

	@Test
	void theOwnerIsNeverAdoptedByAUniversityAndOtherAccountsWithoutOneAreRefused() throws Exception {
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
			users.assignUniversityWhereMissing(TenantContext.DEFAULT_UNIVERSITY_ID));
		assertThat(users.findByEmailIgnoreCase(OWNER).orElseThrow().getUniversityId()).isNull();

		// Fail closed: an ordinary account that somehow has no university cannot sign in.
		String email = "orphan-" + slug() + "@owner.test";
		users.save(AdminUser.builder().email(email).passwordHash(passwordEncoder.encode("orphan-pass-123"))
			.role(AdminRole.ADMIN).universityId(null).build());
		try {
			login(email, "orphan-pass-123").andExpect(status().isUnauthorized());
		} finally {
			users.findByEmailIgnoreCase(email).ifPresent(users::delete);
		}
	}

	@Test
	void ownerCanResendTheFirstInvitationOrAddAnotherAdministrator() throws Exception {
		String slug = slug();
		String email = "resend-" + slug + "@owner.test";
		JsonNode created = json(mockMvc.perform(ownerPost("/api/owner/universities", Map.of(
			"name", "Resend University " + slug, "slug", slug, "adminEmail", email))).andExpect(status().isCreated()));
		String id = created.get("university").get("id").asText();

		JsonNode resent = json(mockMvc.perform(ownerPost("/api/owner/universities/" + id + "/admins", Map.of("email", email)))
			.andExpect(status().isCreated()));
		// Only the newest link works.
		mockMvc.perform(publicPost("/api/auth/reset-password/check", Map.of("token", tokenOf(created.get("inviteLink").asText()))))
			.andExpect(status().isBadRequest());
		acceptInvite(resent.get("inviteLink").asText(), "resend-admin-pass-1");
		login(email, "resend-admin-pass-1").andExpect(status().isOk());

		// Once signed in, the owner cannot take the account over with a new link.
		mockMvc.perform(ownerPost("/api/owner/universities/" + id + "/admins", Map.of("email", email)))
			.andExpect(status().isUnprocessableEntity());
		// Accounts of other universities (or the owner) cannot be pulled in.
		mockMvc.perform(ownerPost("/api/owner/universities/" + id + "/admins", Map.of("email", DEFAULT_ADMIN)))
			.andExpect(status().isConflict());
		mockMvc.perform(ownerPost("/api/owner/universities/" + id + "/admins", Map.of("email", OWNER)))
			.andExpect(status().isConflict());

		JsonNode second = json(mockMvc.perform(ownerPost("/api/owner/universities/" + id + "/admins",
			Map.of("email", "second-" + slug + "@owner.test"))).andExpect(status().isCreated()));
		assertThat(second.get("university").get("admins")).hasSize(2);
		mockMvc.perform(ownerPost("/api/owner/universities/" + UUID.randomUUID() + "/admins", Map.of("email", "ghost-" + slug + "@owner.test")))
			.andExpect(status().isNotFound());
	}

	@Test
	void ownerActionsAreLoggedForTheOwnerOnly() throws Exception {
		String slug = slug();
		mockMvc.perform(ownerPost("/api/owner/universities", Map.of("name", "Logged University " + slug, "slug", slug,
			"adminEmail", "logged-" + slug + "@owner.test"))).andExpect(status().isCreated());

		String ownerLog = json(mockMvc.perform(authed(get("/api/owner/activity?size=100"), token(OWNER))).andExpect(status().isOk())).toString();
		assertThat(ownerLog).contains("Created university 'Logged University " + slug + "'");
		assertThat(ownerLog).contains("Invited logged-" + slug + "@owner.test");

		String universityLog = json(mockMvc.perform(authed(get("/api/audit-events?size=200"), token(DEFAULT_ADMIN)))).toString();
		assertThat(universityLog).doesNotContain("Logged University " + slug);
	}

	// ─────────────────────────── helpers ───────────────────────────

	private JsonNode universityInOverview(String id) throws Exception {
		JsonNode overview = json(mockMvc.perform(authed(get("/api/owner/universities"), token(OWNER))).andExpect(status().isOk()));
		for (JsonNode university : overview.get("universities")) {
			if (university.get("id").asText().equals(id)) {
				return university;
			}
		}
		throw new AssertionError("University " + id + " not in the overview");
	}

	private void acceptInvite(String link, String password) throws Exception {
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", tokenOf(link), "newPassword", password)))
			.andExpect(status().isNoContent());
	}

	private static String tokenOf(String link) {
		return link.substring(link.indexOf("#token=") + "#token=".length());
	}

	private static String slug() {
		return "t-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private String token(String email) {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private ResultActions login(String email, String password) throws Exception {
		String ip = "198.19.0." + IP.getAndIncrement();
		return mockMvc.perform(post("/api/auth/login").header(CSRF, "XMLHttpRequest")
			.with(request -> { request.setRemoteAddr(ip); return request; })
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new HashMap<>(Map.of("email", email, "password", password)))));
	}

	private MockHttpServletRequestBuilder ownerPost(String path, Object body) throws Exception {
		return authed(post(path), token(OWNER)).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private MockHttpServletRequestBuilder ownerPut(String path, Object body) throws Exception {
		return authed(put(path), token(OWNER)).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private MockHttpServletRequestBuilder publicPost(String path, Object body) throws Exception {
		String ip = "198.19.1." + IP.getAndIncrement();
		return post(path).header(CSRF, "XMLHttpRequest").with(request -> { request.setRemoteAddr(ip); return request; })
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}
}
