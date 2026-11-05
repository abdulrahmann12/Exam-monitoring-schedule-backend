package schedule.example.schedule.security;

import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.UserSession;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.UserSessionRepository;
import schedule.example.schedule.service.SessionService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 2 step 2: HttpOnly cookie sessions, refresh rotation, theft detection and CSRF protection. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionCookieTest {

	private static final String EMAIL = "cookie-user@sessions.test";
	private static final String PASSWORD = "cookie-password-123";
	private static final String CSRF = "X-Requested-With";
	private static final String PERSON_JSON = """
		{"name":"Session Test Person","department":"X","role":"INVIGILATOR","availableDays":["Sun"]}
		""";
	private static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired private MockMvc mockMvc;
	@Autowired private AdminUserRepository users;
	@Autowired private UserSessionRepository sessions;
	@Autowired private PersonRepository people;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;

	@BeforeEach
	void createUser() {
		users.save(AdminUser.builder().email(EMAIL).passwordHash(passwordEncoder.encode(PASSWORD)).role(AdminRole.ADMIN).build());
	}

	@AfterEach
	void cleanUp() {
		users.findByEmailIgnoreCase(EMAIL).ifPresent(u -> {
			sessionService.deleteAllForUser(u.getId());
			users.delete(u);
		});
		people.findAll().stream().filter(p -> "Session Test Person".equals(p.getName())).forEach(people::delete);
	}

	// ── Cookies ─────────────────────────────────────────────────────────────

	@Test
	void signInSetsHttpOnlyCookiesAndNeverReturnsTokensToJavaScript() throws Exception {
		MvcResult result = login();
		List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

		String access = setCookies.stream().filter(c -> c.startsWith("ug_access=")).findFirst().orElseThrow();
		String refresh = setCookies.stream().filter(c -> c.startsWith("ug_refresh=")).findFirst().orElseThrow();
		assertThat(access).contains("HttpOnly", "SameSite=Lax", "Path=/api;").doesNotContain("Path=/api/auth");
		assertThat(refresh).contains("HttpOnly", "SameSite=Lax", "Path=/api/auth");

		String body = result.getResponse().getContentAsString();
		assertThat(body).contains(EMAIL).doesNotContain("accessToken").doesNotContain(cookie(result, "ug_access").getValue());
	}

	@Test
	void theAccessCookieAuthenticatesApiCalls() throws Exception {
		MvcResult result = login();
		mockMvc.perform(get("/api/people").cookie(cookie(result, "ug_access"))).andExpect(status().isOk());
		mockMvc.perform(get("/api/auth/me").cookie(cookie(result, "ug_access")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(EMAIL));
		mockMvc.perform(get("/api/people")).andExpect(status().isUnauthorized());
	}

	@Test
	void aBrokenAccessCookieDoesNotBlockSignInOrRefresh() throws Exception {
		MvcResult first = login();
		Cookie garbage = new Cookie("ug_access", "not-a-jwt");
		mockMvc.perform(refresh(cookie(first, "ug_refresh")).cookie(garbage)).andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/login").cookie(garbage).header(CSRF, "XMLHttpRequest")
				.with(r -> { r.setRemoteAddr(nextIp()); return r; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void tokensWithoutASessionAreRejected() throws Exception {
		AdminUser user = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		String noSuchSession = jwt.generateToken(EMAIL, user.getTokenVersion(), UUID.randomUUID()).token();
		mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + noSuchSession))
			.andExpect(status().isUnauthorized());
	}

	// ── Refresh rotation ────────────────────────────────────────────────────

	@Test
	void refreshRotatesBothCookies() throws Exception {
		MvcResult signIn = login();
		Cookie oldRefresh = cookie(signIn, "ug_refresh");

		MvcResult renewed = mockMvc.perform(refresh(oldRefresh)).andExpect(status().isOk()).andReturn();
		Cookie newRefresh = cookie(renewed, "ug_refresh");
		Cookie newAccess = cookie(renewed, "ug_access");

		assertThat(newRefresh.getValue()).isNotEqualTo(oldRefresh.getValue());
		mockMvc.perform(get("/api/auth/me").cookie(newAccess)).andExpect(status().isOk());
		mockMvc.perform(refresh(newRefresh)).andExpect(status().isOk());
	}

	@Test
	void twoTabsRefreshingAtOnceDoNotSignTheUserOut() throws Exception {
		Cookie oldRefresh = cookie(login(), "ug_refresh");
		Cookie winner = cookie(mockMvc.perform(refresh(oldRefresh)).andExpect(status().isOk()).andReturn(), "ug_refresh");

		// The slower tab still sent the old token: told to retry, cookies are NOT cleared.
		MvcResult loser = mockMvc.perform(refresh(oldRefresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("another tab")))
			.andReturn();
		assertThat(loser.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

		mockMvc.perform(refresh(winner)).andExpect(status().isOk());
	}

	@Test
	void reusingAnOldRefreshTokenLaterEndsTheWholeSession() throws Exception {
		MvcResult signIn = login();
		Cookie stolen = cookie(signIn, "ug_refresh");
		MvcResult renewed = mockMvc.perform(refresh(stolen)).andExpect(status().isOk()).andReturn();
		Cookie current = cookie(renewed, "ug_refresh");
		Cookie access = cookie(renewed, "ug_access");

		// Pretend the rotation happened a while ago (outside the two-tab grace period).
		UserSession session = sessions.findAll().stream()
			.filter(s -> s.getUserId().equals(users.findByEmailIgnoreCase(EMAIL).orElseThrow().getId()))
			.findFirst().orElseThrow();
		session.setRotatedAt(Instant.now().minusSeconds(120));
		sessions.save(session);

		MockHttpServletResponse reuse = mockMvc.perform(refresh(stolen)).andExpect(status().isUnauthorized()).andReturn().getResponse();
		assertThat(reuse.getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(c -> c.startsWith("ug_refresh=;") && c.contains("Max-Age=0"));

		// Both the thief and the real user are out; the real user simply signs in again.
		mockMvc.perform(refresh(current)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isUnauthorized());
	}

	// ── Logout ──────────────────────────────────────────────────────────────

	@Test
	void logoutEverywhereEndsAllDevices() throws Exception {
		MvcResult laptop = login();
		MvcResult phone = login();

		mockMvc.perform(post("/api/auth/logout-all").cookie(cookie(laptop, "ug_access")).header(CSRF, "XMLHttpRequest"))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/auth/me").cookie(cookie(laptop, "ug_access"))).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/auth/me").cookie(cookie(phone, "ug_access"))).andExpect(status().isUnauthorized());
		mockMvc.perform(refresh(cookie(phone, "ug_refresh"))).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutWithAnExpiredAccessCookieStillWorks() throws Exception {
		Cookie refreshCookie = cookie(login(), "ug_refresh");
		mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie, new Cookie("ug_access", "expired")).header(CSRF, "XMLHttpRequest"))
			.andExpect(status().isNoContent());
		mockMvc.perform(refresh(refreshCookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void changingThePasswordKeepsThisDeviceAndSignsOutTheOthers() throws Exception {
		MvcResult laptop = login();
		MvcResult phone = login();

		MvcResult changed = mockMvc.perform(post("/api/auth/change-password").cookie(cookie(laptop, "ug_access"))
				.header(CSRF, "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a-new-password-456\"}"))
			.andExpect(status().isOk()).andReturn();

		mockMvc.perform(get("/api/auth/me").cookie(cookie(changed, "ug_access"))).andExpect(status().isOk());
		mockMvc.perform(refresh(cookie(laptop, "ug_refresh"))).andExpect(status().isOk());

		mockMvc.perform(get("/api/auth/me").cookie(cookie(phone, "ug_access"))).andExpect(status().isUnauthorized());
		mockMvc.perform(refresh(cookie(phone, "ug_refresh"))).andExpect(status().isUnauthorized());
	}

	@Test
	void aDisabledUserCannotRefresh() throws Exception {
		Cookie refreshCookie = cookie(login(), "ug_refresh");
		AdminUser user = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		user.setEnabled(false);
		users.save(user);
		mockMvc.perform(refresh(refreshCookie)).andExpect(status().isUnauthorized());
	}

	// ── CSRF ────────────────────────────────────────────────────────────────

	@Test
	void cookieRequestsWithoutTheAppHeaderAreBlocked() throws Exception {
		Cookie access = cookie(login(), "ug_access");

		mockMvc.perform(post("/api/people").cookie(access).contentType(MediaType.APPLICATION_JSON).content(PERSON_JSON))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Request blocked: it did not come from the application."));
		mockMvc.perform(put("/api/settings").cookie(access).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
		// Reading is not affected.
		mockMvc.perform(get("/api/people").cookie(access)).andExpect(status().isOk());
	}

	@Test
	void requestsFromAnUnknownWebsiteAreBlockedEvenWithTheHeader() throws Exception {
		Cookie access = cookie(login(), "ug_access");
		mockMvc.perform(post("/api/people").cookie(access).header(CSRF, "XMLHttpRequest").header(HttpHeaders.ORIGIN, "https://evil.example")
				.contentType(MediaType.APPLICATION_JSON).content(PERSON_JSON))
			.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/people").cookie(access).header(CSRF, "XMLHttpRequest").header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.contentType(MediaType.APPLICATION_JSON).content(PERSON_JSON))
			.andExpect(status().isCreated());
	}

	@Test
	void signInWithoutTheAppHeaderIsBlocked() throws Exception {
		mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(nextIp()); return r; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void scriptsUsingABearerHeaderAreNotAffected() throws Exception {
		AdminUser user = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		String token = jwt.generateToken(EMAIL, user.getTokenVersion(), sid).token();
		mockMvc.perform(post("/api/people").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(PERSON_JSON))
			.andExpect(status().isCreated());
	}

	// ── Housekeeping ────────────────────────────────────────────────────────

	@Test
	void tooManyDevicesSignsOutTheOldestAndEndedSessionsAreCleanedUp() {
		AdminUser user = users.findByEmailIgnoreCase(EMAIL).orElseThrow();
		UUID first = sessionService.start(user, null).session().getId();
		for (int i = 0; i < 20; i++) {
			sessionService.start(user, null);
		}
		assertThat(sessionService.isActive(first, user.getId())).isFalse(); // limit is 20 devices
		assertThat(sessions.findActiveByUser(user.getId(), Instant.now())).hasSize(20);

		UserSession old = sessions.findById(first).orElseThrow();
		old.setRevokedAt(Instant.now().minusSeconds(3 * 24 * 3600));
		sessions.save(old);
		sessionService.deleteEndedSessions();
		assertThat(sessions.findById(first)).isEmpty();
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private MvcResult login() throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.with(r -> { r.setRemoteAddr(nextIp()); return r; })
				.header(CSRF, "XMLHttpRequest")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
	}

	private static MockHttpServletRequestBuilder refresh(Cookie refreshCookie) {
		return post("/api/auth/refresh").cookie(refreshCookie).header(CSRF, "XMLHttpRequest");
	}

	private static Cookie cookie(MvcResult result, String name) {
		Cookie cookie = result.getResponse().getCookie(name);
		assertThat(cookie).as(name + " cookie").isNotNull();
		return cookie;
	}

	private static String nextIp() {
		return "198.19.0." + IP.getAndIncrement();
	}
}
