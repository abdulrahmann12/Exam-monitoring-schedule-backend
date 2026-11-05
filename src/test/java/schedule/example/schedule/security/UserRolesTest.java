package schedule.example.schedule.security;

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
import schedule.example.schedule.dto.user.UpdateUserRequest;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.service.SessionService;
import schedule.example.schedule.service.UserManagementService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 2 step 1: roles, user management and its safety rules, over HTTP through the real filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserRolesTest {

	private static final String DOMAIN = "@roles.test";
	private static final String ADMIN = "admin" + DOMAIN;
	private static final String SCHEDULER = "scheduler" + DOMAIN;
	private static final String VIEWER = "viewer" + DOMAIN;
	private static final String PASSWORD = "correct-horse-battery";
	private static final String PERSON_JSON = """
		{"name":"Roles Test Person","department":"X","role":"INVIGILATOR","availableDays":["Sun"]}
		""";
	private static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private PersonRepository people;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private UserManagementService userManagementService;
	@Autowired private SessionService sessionService;

	private static final String CSRF_HEADER = "X-Requested-With";

	@BeforeEach
	void createAccounts() {
		save(ADMIN, AdminRole.ADMIN);
		save(SCHEDULER, AdminRole.SCHEDULER);
		save(VIEWER, AdminRole.VIEWER);
	}

	@AfterEach
	void cleanUp() {
		users.findAll().stream().filter(u -> u.getEmail().endsWith(DOMAIN)).forEach(users::delete);
		people.findAll().stream().filter(p -> "Roles Test Person".equals(p.getName())).forEach(people::delete);
	}

	// ── What each role may do ───────────────────────────────────────────────

	@Test
	void viewerCanReadButCannotChangeAnything() throws Exception {
		String viewer = token(VIEWER);
		mockMvc.perform(authed(get("/api/people"), viewer)).andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/settings"), viewer)).andExpect(status().isOk());

		mockMvc.perform(json(post("/api/people"), viewer, PERSON_JSON))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("You do not have permission to do this."));
		mockMvc.perform(json(post("/api/assignments/generate-preview"), viewer, "{}")).andExpect(status().isForbidden());
		mockMvc.perform(authed(delete("/api/rooms/" + UUID.randomUUID()), viewer)).andExpect(status().isForbidden());
		mockMvc.perform(authed(get("/api/users"), viewer)).andExpect(status().isForbidden());
		// Own account stays usable.
		mockMvc.perform(authed(get("/api/auth/me"), viewer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("VIEWER"));
	}

	@Test
	void schedulerEditsScheduleDataButNotUsersOrSettings() throws Exception {
		String scheduler = token(SCHEDULER);
		mockMvc.perform(json(post("/api/people"), scheduler, PERSON_JSON)).andExpect(status().isCreated());

		mockMvc.perform(json(put("/api/settings"), scheduler, "{}")).andExpect(status().isForbidden());
		mockMvc.perform(authed(get("/api/users"), scheduler)).andExpect(status().isForbidden());
		mockMvc.perform(json(post("/api/users"), scheduler, newUserJson("x" + DOMAIN, "ADMIN"))).andExpect(status().isForbidden());
	}

	@Test
	void roleChangesApplyToTheNextRequestWithoutNewSignIn() throws Exception {
		String viewerToken = token(VIEWER);
		mockMvc.perform(json(post("/api/people"), viewerToken, PERSON_JSON)).andExpect(status().isForbidden());

		AdminUser viewer = users.findByEmailIgnoreCase(VIEWER).orElseThrow();
		mockMvc.perform(json(put("/api/users/" + viewer.getId()), token(ADMIN), """
				{"displayName":"Now scheduler","role":"SCHEDULER","enabled":true}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("SCHEDULER"));

		mockMvc.perform(json(post("/api/people"), viewerToken, PERSON_JSON)).andExpect(status().isCreated());
	}

	// ── New users start with a temporary password ───────────────────────────

	@Test
	void newUserMustReplaceTheTemporaryPasswordBeforeDoingAnythingElse() throws Exception {
		String email = "New.User" + DOMAIN;
		mockMvc.perform(json(post("/api/users"), token(ADMIN), newUserJson(email, "SCHEDULER")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(email.toLowerCase()))
			.andExpect(jsonPath("$.mustChangePassword").value(true))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		String tempToken = login(email, "temporary-pass-1").andExpect(status().isOk()).andReturn().getResponse().getCookie("ug_access").getValue();

		mockMvc.perform(authed(get("/api/auth/me"), tempToken)).andExpect(jsonPath("$.mustChangePassword").value(true));
		mockMvc.perform(authed(get("/api/people"), tempToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("You must change your password before continuing."));
		mockMvc.perform(json(post("/api/people"), tempToken, PERSON_JSON)).andExpect(status().isForbidden());

		String newToken = mockMvc.perform(json(post("/api/auth/change-password"), tempToken, """
				{"currentPassword":"temporary-pass-1","newPassword":"my-own-password-1"}
				"""))
			.andExpect(status().isOk()).andReturn().getResponse().getCookie("ug_access").getValue();

		mockMvc.perform(authed(get("/api/auth/me"), newToken)).andExpect(jsonPath("$.mustChangePassword").value(false));
		mockMvc.perform(authed(get("/api/people"), newToken)).andExpect(status().isOk());
		mockMvc.perform(authed(get("/api/people"), tempToken)).andExpect(status().isUnauthorized()); // old token revoked
	}

	@Test
	void createRejectsDuplicatesTheDemoRoleAndBadPasswords() throws Exception {
		String admin = token(ADMIN);
		mockMvc.perform(json(post("/api/users"), admin, newUserJson(VIEWER.toUpperCase(), "VIEWER")))
			.andExpect(status().isConflict());
		mockMvc.perform(json(post("/api/users"), admin, newUserJson("demo2" + DOMAIN, "DEMO_ADMIN")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(json(post("/api/users"), admin, newUserJson("bad-role" + DOMAIN, "ROOT")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(json(post("/api/users"), admin, """
				{"email":"short%s","role":"VIEWER","temporaryPassword":"short"}
				""".formatted(DOMAIN)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("at least 10")));
		// 40 Arabic letters = 80 bytes: over the BCrypt limit -> clean 400, not a server error.
		mockMvc.perform(json(post("/api/users"), admin, """
				{"email":"long%s","role":"VIEWER","temporaryPassword":"%s"}
				""".formatted(DOMAIN, "م".repeat(40))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("too long")));
		mockMvc.perform(json(post("/api/users"), admin, """
				{"email":"not-an-email","role":"VIEWER","temporaryPassword":"temporary-pass-1"}
				"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void changePasswordWithAnOversizedPasswordIsA400NotA500() throws Exception {
		mockMvc.perform(json(post("/api/auth/change-password"), token(ADMIN), """
				{"currentPassword":"%s","newPassword":"%s"}
				""".formatted(PASSWORD, "a".repeat(100))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("too long")));
	}

	// ── Safety rules ────────────────────────────────────────────────────────

	@Test
	void anAdministratorCannotDemoteDisableDeleteOrResetThemselves() throws Exception {
		String admin = token(ADMIN);
		UUID self = users.findByEmailIgnoreCase(ADMIN).orElseThrow().getId();

		mockMvc.perform(json(put("/api/users/" + self), admin, "{\"role\":\"VIEWER\",\"enabled\":true}"))
			.andExpect(status().isUnprocessableEntity());
		mockMvc.perform(json(put("/api/users/" + self), admin, "{\"role\":\"ADMIN\",\"enabled\":false}"))
			.andExpect(status().isUnprocessableEntity());
		mockMvc.perform(authed(delete("/api/users/" + self), admin)).andExpect(status().isUnprocessableEntity());
		mockMvc.perform(json(post("/api/users/" + self + "/reset-password"), admin, "{\"temporaryPassword\":\"temporary-pass-1\"}"))
			.andExpect(status().isUnprocessableEntity());
		// Renaming yourself is fine.
		mockMvc.perform(json(put("/api/users/" + self), admin, "{\"displayName\":\"Me\",\"role\":\"ADMIN\",\"enabled\":true}"))
			.andExpect(status().isOk());

		AdminUser after = users.findByEmailIgnoreCase(ADMIN).orElseThrow();
		assertThat(after.getRole()).isEqualTo(AdminRole.ADMIN);
		assertThat(after.isEnabled()).isTrue();
	}

	@Test
	void theLastActiveAdministratorCannotBeRemoved() {
		// Leave exactly one active admin (ours) by disabling every other one for this test.
		List<AdminUser> others = users.findAll().stream()
			.filter(u -> u.getRole() == AdminRole.ADMIN && u.isEnabled() && !u.getEmail().equals(ADMIN))
			.toList();
		others.forEach(u -> { u.setEnabled(false); users.save(u); });
		try {
			UUID lastAdmin = users.findByEmailIgnoreCase(ADMIN).orElseThrow().getId();
			UUID someoneElse = UUID.randomUUID();
			assertThatThrownBy(() -> userManagementService.update(lastAdmin, new UpdateUserRequest(null, AdminRole.VIEWER, true), someoneElse))
				.isInstanceOf(BusinessRuleViolationException.class)
				.hasMessageContaining("at least one active administrator");
			assertThatThrownBy(() -> userManagementService.update(lastAdmin, new UpdateUserRequest(null, AdminRole.ADMIN, false), someoneElse))
				.isInstanceOf(BusinessRuleViolationException.class);
			assertThatThrownBy(() -> userManagementService.delete(lastAdmin, someoneElse))
				.isInstanceOf(BusinessRuleViolationException.class);
		} finally {
			others.forEach(u -> { u.setEnabled(true); users.save(u); });
		}
	}

	@Test
	void theDemoAccountIsHiddenAndCannotBeChanged() throws Exception {
		AdminUser demo = save("demo" + DOMAIN, AdminRole.DEMO_ADMIN);
		String admin = token(ADMIN);

		String list = mockMvc.perform(authed(get("/api/users"), admin)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(list).contains(VIEWER).doesNotContain("demo" + DOMAIN);

		mockMvc.perform(json(put("/api/users/" + demo.getId()), admin, "{\"role\":\"ADMIN\",\"enabled\":true}"))
			.andExpect(status().isUnprocessableEntity());
		mockMvc.perform(authed(delete("/api/users/" + demo.getId()), admin)).andExpect(status().isUnprocessableEntity());
		mockMvc.perform(authed(get("/api/users"), token(demo.getEmail()))).andExpect(status().isForbidden());
	}

	// ── Disable, unlock, reset ──────────────────────────────────────────────

	@Test
	void disablingAUserEndsTheirSessionAndBlocksSignIn() throws Exception {
		String viewerToken = token(VIEWER);
		AdminUser viewer = users.findByEmailIgnoreCase(VIEWER).orElseThrow();

		mockMvc.perform(json(put("/api/users/" + viewer.getId()), token(ADMIN), "{\"role\":\"VIEWER\",\"enabled\":false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false));

		mockMvc.perform(authed(get("/api/people"), viewerToken)).andExpect(status().isUnauthorized());
		// Right password: told the account is disabled. Wrong password: nothing revealed.
		login(VIEWER, PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value(containsString("disabled")));
		login(VIEWER, "wrong-password-1").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value("Invalid email or password."));

		mockMvc.perform(json(put("/api/users/" + viewer.getId()), token(ADMIN), "{\"role\":\"VIEWER\",\"enabled\":true}"))
			.andExpect(status().isOk());
		login(VIEWER, PASSWORD).andExpect(status().isOk());
	}

	@Test
	void anAdministratorCanUnlockALockedAccount() throws Exception {
		AdminUser viewer = users.findByEmailIgnoreCase(VIEWER).orElseThrow();
		viewer.setLockedUntil(Instant.now().plusSeconds(3600));
		users.save(viewer);
		login(VIEWER, PASSWORD).andExpect(status().isLocked());

		mockMvc.perform(json(post("/api/users/" + viewer.getId() + "/unlock"), token(ADMIN), "{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.locked").value(false));
		login(VIEWER, PASSWORD).andExpect(status().isOk());
		assertThat(users.findByEmailIgnoreCase(VIEWER).orElseThrow().getLastLoginAt()).isNotNull();
	}

	@Test
	void resetPasswordSignsTheUserOutAndForcesANewPassword() throws Exception {
		String viewerToken = token(VIEWER);
		AdminUser viewer = users.findByEmailIgnoreCase(VIEWER).orElseThrow();

		mockMvc.perform(json(post("/api/users/" + viewer.getId() + "/reset-password"), token(ADMIN),
				"{\"temporaryPassword\":\"temporary-pass-2\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mustChangePassword").value(true));

		mockMvc.perform(authed(get("/api/people"), viewerToken)).andExpect(status().isUnauthorized());
		login(VIEWER, PASSWORD).andExpect(status().isUnauthorized());
		login(VIEWER, "temporary-pass-2").andExpect(status().isOk());
	}

	@Test
	void unknownUserIdIs404() throws Exception {
		mockMvc.perform(json(put("/api/users/" + UUID.randomUUID()), token(ADMIN), "{\"role\":\"VIEWER\",\"enabled\":true}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void accountsBuiltWithTheNoArgsConstructorAreEnabledByDefault() {
		assertThat(new AdminUser().isEnabled()).isTrue();
		assertThat(new AdminUser().isMustChangePassword()).isFalse();
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private AdminUser save(String email, AdminRole role) {
		return users.save(AdminUser.builder()
			.email(email).passwordHash(passwordEncoder.encode(PASSWORD)).role(role).build());
	}

	private String token(String email) {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		var session = sessionService.start(user, null).session();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), session.getId()).token();
	}

	private ResultActions login(String email, String password) throws Exception {
		String ip = "198.18.0." + IP.getAndIncrement(); // fresh IP per sign-in: keeps the per-IP limiter out of the way
		return mockMvc.perform(post("/api/auth/login").header(CSRF_HEADER, "XMLHttpRequest")
			.with(request -> { request.setRemoteAddr(ip); return request; })
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new java.util.HashMap<>(java.util.Map.of("email", email, "password", password)))));
	}

	private static String newUserJson(String email, String role) {
		return """
			{"email":"%s","displayName":"Test","role":"%s","temporaryPassword":"temporary-pass-1"}
			""".formatted(email, role);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String token, String body) {
		return authed(builder, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF_HEADER, "XMLHttpRequest");
	}
}
