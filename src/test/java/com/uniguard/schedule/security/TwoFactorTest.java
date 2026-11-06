package com.uniguard.schedule.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.repository.MfaChallengeRepository;
import com.uniguard.schedule.security.mfa.Totp;
import com.uniguard.schedule.service.SessionService;
import com.uniguard.schedule.tenant.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-step sign-in with an authenticator app: set-up, the second sign-in step, recovery codes,
 * guessing protection, and resets by an administrator or the platform owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TwoFactorTest {

	private static final String PASSWORD = "two-step-pass-123";
	private static final String CSRF = "X-Requested-With";
	private static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private MfaChallengeRepository challenges;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;

	private String email;
	private final List<String> created = new ArrayList<>();

	@BeforeEach
	void setUp() {
		email = newUser(AdminRole.VIEWER);
	}

	@AfterEach
	void cleanUp() {
		for (String address : created) {
			users.findByEmailIgnoreCase(address).ifPresent(user -> {
				sessionService.deleteAllForUser(user.getId());
				users.delete(user);
			});
		}
	}

	@Test
	void settingUpNeedsThePasswordAndAWorkingCodeAndSignsOutOtherDevices() throws Exception {
		String token = token(email);
		String otherDevice = token(email);
		mockMvc.perform(authed(get("/api/auth/2fa"), token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.available").value(true))
			.andExpect(jsonPath("$.enabled").value(false));

		mockMvc.perform(authed(post("/api/auth/2fa/setup"), token, Map.of("currentPassword", "wrong-password-1")))
			.andExpect(status().isBadRequest());
		JsonNode setup = json(mockMvc.perform(authed(post("/api/auth/2fa/setup"), token, Map.of("currentPassword", PASSWORD)))
			.andExpect(status().isOk()));
		String secret = setup.get("secret").asText().replace(" ", "");
		assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secret);

		// Not on until a correct code arrives; a wrong code changes nothing.
		mockMvc.perform(authed(post("/api/auth/2fa/enable"), token, Map.of("code", wrongCode(secret))))
			.andExpect(status().isBadRequest());
		assertThat(user().isTotpEnabled()).isFalse();

		JsonNode codes = json(mockMvc.perform(authed(post("/api/auth/2fa/enable"), token, Map.of("code", now(secret))))
			.andExpect(status().isOk()));
		assertThat(codes.get("codes")).hasSize(10);
		assertThat(codes.get("codes").get(0).asText()).matches("[a-z2-9]{5}-[a-z2-9]{5}");

		// The secret is stored encrypted, never in plain text.
		assertThat(user().getTotpSecret()).startsWith("v1:").doesNotContain(secret);
		mockMvc.perform(authed(get("/api/auth/2fa"), token))
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.recoveryCodesLeft").value(10));
		mockMvc.perform(authed(get("/api/auth/me"), token)).andExpect(jsonPath("$.twoFactorEnabled").value(true));
		mockMvc.perform(authed(get("/api/auth/me"), otherDevice)).andExpect(status().isUnauthorized());
	}

	@Test
	void signInStopsAtThePasswordUntilTheCodeFromTheAppIsGiven() throws Exception {
		String secret = enableFor(email).secret();
		MockHttpServletResponse first = login(email, PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.twoFactorRequired").value(true))
			.andReturn().getResponse();
		// No session yet: only the short-lived challenge cookie, scoped to the two-step endpoint.
		assertThat(first.getCookie("ug_access")).isNull();
		assertThat(first.getCookie("ug_refresh")).isNull();
		Cookie challenge = first.getCookie("ug_mfa");
		assertThat(challenge).isNotNull();
		assertThat(challenge.isHttpOnly()).isTrue();
		assertThat(challenge.getPath()).isEqualTo("/api/auth/2fa");

		verify(challenge, wrongCode(secret)).andExpect(status().isUnauthorized());
		MockHttpServletResponse signedIn = verify(challenge, now(secret)).andExpect(status().isOk())
			.andExpect(jsonPath("$.twoFactorRequired").value(false))
			.andReturn().getResponse();
		String access = signedIn.getCookie("ug_access").getValue();
		mockMvc.perform(get("/api/rooms").cookie(new Cookie("ug_access", access))).andExpect(status().isOk());
		assertThat(signedIn.getCookie("ug_mfa").getMaxAge()).isZero();

		// The challenge was used up; and the same code cannot be used again with a new challenge.
		verify(challenge, now(secret)).andExpect(status().isUnauthorized());
		Cookie second = login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa");
		verify(second, Totp.codeAt(secret, user().getTotpLastStep())).andExpect(status().isUnauthorized());
	}

	@Test
	void eachRecoveryCodeWorksOnce() throws Exception {
		Enabled enabled = enableFor(email);
		String code = enabled.recoveryCodes().get(3);
		verify(login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa"), code.toUpperCase()).andExpect(status().isOk());
		verify(login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa"), code).andExpect(status().isUnauthorized());
		mockMvc.perform(authed(get("/api/auth/2fa"), token(email))).andExpect(jsonPath("$.recoveryCodesLeft").value(9));
	}

	@Test
	void guessingCodesLocksTheAccountAndAnotherPasswordDoesNotResetTheCount() throws Exception {
		String secret = enableFor(email).secret();
		Cookie challenge = login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa");
		for (int i = 0; i < 3; i++) {
			verify(challenge, wrongCode(secret)).andExpect(status().isUnauthorized());
		}
		// The right password again must not give a fresh set of guesses.
		challenge = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getCookie("ug_mfa");
		verify(challenge, wrongCode(secret)).andExpect(status().isUnauthorized());
		verify(challenge, wrongCode(secret)).andExpect(status().isLocked());
		// Locked: even the right code and the right password are refused now.
		verify(challenge, now(secret)).andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isLocked());
		assertThat(challenges.findAll()).noneMatch(c -> c.getUserId().equals(user().getId()));
	}

	@Test
	void aChallengeExpiresAndCannotBeForged() throws Exception {
		String secret = enableFor(email).secret();
		mockMvc.perform(post("/api/auth/2fa/verify").header(CSRF, "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"123456\"}"))
			.andExpect(status().isUnauthorized());
		verify(new Cookie("ug_mfa", "made-up-" + UUID.randomUUID()), now(secret)).andExpect(status().isUnauthorized());

		Cookie challenge = login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa");
		challenges.findAll().stream().filter(c -> c.getUserId().equals(user().getId())).forEach(c -> {
			c.setExpiresAt(Instant.now().minusSeconds(1));
			challenges.save(c);
		});
		verify(challenge, now(secret)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sign in again")));
	}

	@Test
	void switchingOffNeedsPasswordAndCodeThenSignInIsOneStepAgain() throws Exception {
		Enabled enabled = enableFor(email);
		String token = token(email);
		mockMvc.perform(authed(post("/api/auth/2fa/disable"), token, Map.of("currentPassword", PASSWORD, "code", "000000")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(authed(post("/api/auth/2fa/disable"), token, Map.of("currentPassword", "wrong-password-1", "code", enabled.recoveryCodes().get(0))))
			.andExpect(status().isBadRequest());
		mockMvc.perform(authed(post("/api/auth/2fa/disable"), token, Map.of("currentPassword", PASSWORD, "code", enabled.recoveryCodes().get(0))))
			.andExpect(status().isNoContent());
		assertThat(user().isTotpEnabled()).isFalse();
		assertThat(user().getTotpSecret()).isNull();
		login(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.twoFactorRequired").value(false));
	}

	@Test
	void newRecoveryCodesReplaceTheOldOnes() throws Exception {
		Enabled enabled = enableFor(email);
		JsonNode fresh = json(mockMvc.perform(authed(post("/api/auth/2fa/recovery-codes"), token(email),
			Map.of("currentPassword", PASSWORD, "code", enabled.recoveryCodes().get(0)))).andExpect(status().isOk()));
		assertThat(fresh.get("codes")).hasSize(10);
		verify(login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa"), enabled.recoveryCodes().get(1))
			.andExpect(status().isUnauthorized());
		verify(login(email, PASSWORD).andReturn().getResponse().getCookie("ug_mfa"), fresh.get("codes").get(0).asText())
			.andExpect(status().isOk());
	}

	@Test
	void anAdministratorCanResetALostPhoneButNotTheirOwn() throws Exception {
		enableFor(email);
		String admin = newUser(AdminRole.ADMIN);
		String adminToken = token(admin);
		String userToken = token(email);
		UUID userId = user().getId();

		mockMvc.perform(authed(get("/api/users"), adminToken))
			.andExpect(jsonPath("$[?(@.email == '" + email + "')].twoFactorEnabled").value(true));
		mockMvc.perform(authed(post("/api/users/" + userId + "/reset-2fa"), adminToken)).andExpect(status().isOk())
			.andExpect(jsonPath("$.twoFactorEnabled").value(false));
		mockMvc.perform(authed(get("/api/auth/me"), userToken)).andExpect(status().isUnauthorized()); // signed out
		login(email, PASSWORD).andExpect(jsonPath("$.twoFactorRequired").value(false));
		mockMvc.perform(authed(post("/api/users/" + userId + "/reset-2fa"), adminToken)).andExpect(status().isUnprocessableEntity());

		enableFor(admin);
		UUID adminId = users.findByEmailIgnoreCase(admin).orElseThrow().getId();
		mockMvc.perform(authed(post("/api/users/" + adminId + "/reset-2fa"), token(admin))).andExpect(status().isUnprocessableEntity());
		// Viewers cannot reset anybody.
		mockMvc.perform(authed(post("/api/users/" + adminId + "/reset-2fa"), token(email))).andExpect(status().isForbidden());
	}

	@Test
	void thePlatformOwnerCanResetAUniversityAdministrator() throws Exception {
		String admin = newUser(AdminRole.ADMIN);
		enableFor(admin);
		UUID adminId = users.findByEmailIgnoreCase(admin).orElseThrow().getId();
		String owner = token("owner@test.local");
		mockMvc.perform(authed(post("/api/owner/universities/" + TenantContext.DEFAULT_UNIVERSITY_ID + "/admins/" + adminId + "/reset-2fa"), owner))
			.andExpect(status().isOk());
		assertThat(users.findByEmailIgnoreCase(admin).orElseThrow().isTotpEnabled()).isFalse();
		// Only administrators of that university: not viewers, not unknown ids.
		enableFor(email);
		mockMvc.perform(authed(post("/api/owner/universities/" + TenantContext.DEFAULT_UNIVERSITY_ID + "/admins/" + user().getId() + "/reset-2fa"), owner))
			.andExpect(status().isNotFound());
	}

	// ─────────────────────────── helpers ───────────────────────────

	private record Enabled(String secret, List<String> recoveryCodes) {
	}

	/** Switches two-step sign-in on through the API, as the user would. */
	private Enabled enableFor(String address) throws Exception {
		String token = token(address);
		JsonNode setup = json(mockMvc.perform(authed(post("/api/auth/2fa/setup"), token, Map.of("currentPassword", PASSWORD)))
			.andExpect(status().isOk()));
		String secret = setup.get("secret").asText().replace(" ", "");
		// Use the previous step so the current one is still free for a sign-in right after.
		String code = Totp.codeAt(secret, Totp.stepAt(Instant.now()) - 1);
		JsonNode codes = json(mockMvc.perform(authed(post("/api/auth/2fa/enable"), token, Map.of("code", code))).andExpect(status().isOk()));
		List<String> list = new ArrayList<>();
		codes.get("codes").forEach(node -> list.add(node.asText()));
		return new Enabled(secret, list);
	}

	private static String now(String secret) {
		return Totp.codeAt(secret, Totp.stepAt(Instant.now()));
	}

	/** A 6-digit code that is valid for none of the accepted time steps. */
	private static String wrongCode(String secret) {
		long step = Totp.stepAt(Instant.now());
		for (int candidate = 0; ; candidate++) {
			String code = String.format("%06d", candidate);
			if (!code.equals(Totp.codeAt(secret, step - 1)) && !code.equals(Totp.codeAt(secret, step))
				&& !code.equals(Totp.codeAt(secret, step + 1))) {
				return code;
			}
		}
	}

	private String newUser(AdminRole role) {
		String address = "two-step-" + UUID.randomUUID().toString().substring(0, 8) + "@mfa.test";
		users.save(AdminUser.builder().email(address).passwordHash(passwordEncoder.encode(PASSWORD))
			.role(role).universityId(TenantContext.DEFAULT_UNIVERSITY_ID).build());
		created.add(address);
		return address;
	}

	private AdminUser user() {
		return users.findByEmailIgnoreCase(email).orElseThrow();
	}

	private String token(String address) {
		AdminUser user = users.findByEmailIgnoreCase(address).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private ResultActions login(String address, String password) throws Exception {
		String ip = "198.20.0." + IP.getAndIncrement();
		return mockMvc.perform(post("/api/auth/login").header(CSRF, "XMLHttpRequest")
			.with(request -> { request.setRemoteAddr(ip); return request; })
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new HashMap<>(Map.of("email", address, "password", password)))));
	}

	private ResultActions verify(Cookie challenge, String code) throws Exception {
		String ip = "198.20.1." + IP.getAndIncrement();
		return mockMvc.perform(post("/api/auth/2fa/verify").header(CSRF, "XMLHttpRequest").cookie(challenge)
			.with(request -> { request.setRemoteAddr(ip); return request; })
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("code", code))));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
		return authed(builder, token).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}
}
