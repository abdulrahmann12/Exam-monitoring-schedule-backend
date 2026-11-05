package schedule.example.schedule.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import schedule.example.schedule.config.DataInitializer;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.repository.AdminUserRepository;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 0 security fixes, verified over HTTP through the real filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningTest {

	private static final String ADMIN_EMAIL = "security-test@test.local";
	private static final String ADMIN_PASSWORD = "initial-password-123";
	private static final String DEMO_EMAIL = "demo-security@test.local";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository adminUserRepository;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private DataInitializer dataInitializer;

	@BeforeEach
	void createAccounts() {
		adminUserRepository.save(AdminUser.builder()
			.email(ADMIN_EMAIL).passwordHash(passwordEncoder.encode(ADMIN_PASSWORD)).role(AdminRole.ADMIN).build());
		adminUserRepository.save(AdminUser.builder()
			.email(DEMO_EMAIL).passwordHash(passwordEncoder.encode("unused-demo-password")).role(AdminRole.DEMO_ADMIN).build());
	}

	@AfterEach
	void deleteAccounts() {
		adminUserRepository.findByEmailIgnoreCase(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
		adminUserRepository.findByEmailIgnoreCase(DEMO_EMAIL).ifPresent(adminUserRepository::delete);
	}

	// ── Demo is read-only on the server ─────────────────────────────────────

	@Test
	void demoTokenCanReadButCannotWrite() throws Exception {
		String demo = jwtTokenProvider.generateToken(DEMO_EMAIL, 0).token();

		mockMvc.perform(authed(get("/api/people"), demo)).andExpect(status().isOk());
		mockMvc.perform(authed(post("/api/people"), demo)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"Injected","department":"X","role":"INVIGILATOR","availableDays":["Sun"]}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value("Operation disabled in demo mode."));
		mockMvc.perform(authed(put("/api/settings"), demo).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
	}

	// ── Removed public endpoints ────────────────────────────────────────────

	@Test
	void adminEmailIsNoLongerPublic() throws Exception {
		mockMvc.perform(get("/api/auth/admin-check")).andExpect(status().isUnauthorized());
	}

	// ── Token revocation ────────────────────────────────────────────────────

	@Test
	void logoutRevokesTheTokenOnTheServer() throws Exception {
		String token = login(ADMIN_PASSWORD);
		mockMvc.perform(authed(get("/api/auth/me"), token)).andExpect(status().isOk());

		mockMvc.perform(authed(post("/api/auth/logout"), token)).andExpect(status().isNoContent());

		mockMvc.perform(authed(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
	}

	@Test
	void legacyTokensWithoutVersionClaimAreRejected() throws Exception {
		String legacy = Jwts.builder()
			.subject(ADMIN_EMAIL)
			.issuedAt(new Date())
			.expiration(new Date(System.currentTimeMillis() + 60_000))
			.signWith(Keys.hmacShaKeyFor("test-only-secret-key-that-is-long-enough-1234567890".getBytes(StandardCharsets.UTF_8)))
			.compact();
		mockMvc.perform(authed(get("/api/auth/me"), legacy)).andExpect(status().isUnauthorized());
	}

	@Test
	void changingPasswordRevokesOldSessionsAndKeepsTheCurrentOne() throws Exception {
		String oldToken = login(ADMIN_PASSWORD);

		mockMvc.perform(authed(post("/api/auth/change-password"), oldToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"wrong-password-1\",\"newPassword\":\"brand-new-password-1\"}"))
			.andExpect(status().isBadRequest());

		String body = mockMvc.perform(authed(post("/api/auth/change-password"), oldToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + ADMIN_PASSWORD + "\",\"newPassword\":\"brand-new-password-1\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String newToken = objectMapper.readTree(body).get("accessToken").asText();

		mockMvc.perform(authed(get("/api/auth/me"), oldToken)).andExpect(status().isUnauthorized());
		mockMvc.perform(authed(get("/api/auth/me"), newToken)).andExpect(status().isOk());
		login("brand-new-password-1");
	}

	// ── Bootstrap no longer resets passwords ────────────────────────────────

	@Test
	void restartDoesNotOverwriteAChangedAdminPassword() throws Exception {
		String bootstrapEmail = "admin@test.local";
		AdminUser bootstrap = adminUserRepository.findByEmailIgnoreCase(bootstrapEmail).orElseThrow();
		String changedHash = passwordEncoder.encode("changed-in-the-app-1");
		bootstrap.setPasswordHash(changedHash);
		adminUserRepository.save(bootstrap);

		dataInitializer.run(null); // simulate a restart with BOOTSTRAP_ADMIN_PASSWORD still set

		assertThat(adminUserRepository.findByEmailIgnoreCase(bootstrapEmail).orElseThrow().getPasswordHash())
			.isEqualTo(changedHash);
	}

	// ── Rate limiter cannot be bypassed with a forged header ────────────────

	@Test
	void loginRateLimitIgnoresForgedForwardedFor() throws Exception {
		for (int attempt = 1; attempt <= 11; attempt++) {
			int expected = attempt <= 10 ? 401 : 429;
			int forged = attempt;
			mockMvc.perform(post("/api/auth/login")
					.with(request -> { request.setRemoteAddr("203.0.113.77"); return request; })
					.header("X-Forwarded-For", "198.51.100." + forged)
					.contentType(MediaType.APPLICATION_JSON)
					// unknown account: isolates the per-IP limiter from the per-account lockout
					.content("{\"email\":\"nobody@test.local\",\"password\":\"bad-password-x\"}"))
				.andExpect(status().is(expected));
		}
	}

	// ── Account lockout ─────────────────────────────────────────────────────

	@Test
	void fiveWrongPasswordsLockTheAccountEvenForTheCorrectPassword() throws Exception {
		for (int attempt = 1; attempt <= 5; attempt++) {
			int expected = attempt < 5 ? 401 : 423; // the 5th failure locks and says so
			mockMvc.perform(post("/api/auth/login")
					.with(request -> { request.setRemoteAddr("203.0.113.90"); return request; })
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"wrong-password-" + attempt + "\"}"))
				.andExpect(status().is(expected));
		}

		mockMvc.perform(post("/api/auth/login")
				.with(request -> { request.setRemoteAddr("203.0.113.91"); return request; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isLocked())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("locked")));

		AdminUser locked = adminUserRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
		assertThat(locked.getLockedUntil()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofHours(47)));
	}

	@Test
	void expiredLockAllowsSignInAndSuccessResetsTheCounter() throws Exception {
		AdminUser admin = adminUserRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
		admin.setLockedUntil(java.time.Instant.now().minusSeconds(60));
		admin.setFailedLoginAttempts(3);
		adminUserRepository.save(admin);

		login(ADMIN_PASSWORD);

		AdminUser after = adminUserRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
		assertThat(after.getLockedUntil()).isNull();
		assertThat(after.getFailedLoginAttempts()).isZero();
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private String login(String password) throws Exception {
		String body = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + password + "\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode json = objectMapper.readTree(body);
		return json.get("accessToken").asText();
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}
}
