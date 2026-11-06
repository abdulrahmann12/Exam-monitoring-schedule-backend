package com.uniguard.schedule.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.HtmlUtils;
import com.uniguard.schedule.entity.AccountToken;
import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.repository.AccountTokenRepository;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.service.AccountTokenService;
import com.uniguard.schedule.service.MailService;
import com.uniguard.schedule.service.SessionService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 2 step 3: invitation emails, "forgot password" and one-time links. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountLinksTest {

	private static final String DOMAIN = "@links.test";
	private static final String ADMIN = "admin" + DOMAIN;
	private static final String PASSWORD = "admin-password-123";
	private static final String CSRF = "X-Requested-With";
	private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
	private static final AtomicInteger IP = new AtomicInteger(1);

	@MockitoBean private MailService mailService;

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private AccountTokenRepository tokens;
	@Autowired private AccountTokenService accountTokenService;
	@Autowired private SessionService sessionService;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private JwtTokenProvider jwt;

	@BeforeEach
	void setUp() {
		when(mailService.isConfigured()).thenReturn(true);
		save(ADMIN, AdminRole.ADMIN);
	}

	@AfterEach
	void cleanUp() {
		users.findAll().stream().filter(u -> u.getEmail().endsWith(DOMAIN)).forEach(u -> {
			sessionService.deleteAllForUser(u.getId());
			accountTokenService.deleteAllForUser(u.getId());
			users.delete(u);
		});
	}

	// ── Invitations ─────────────────────────────────────────────────────────

	@Test
	void anInvitedUserChoosesTheirOwnPasswordFromTheEmailLink() throws Exception {
		String email = "new.person" + DOMAIN;
		JsonNode created = json(mockMvc.perform(authed(post("/api/users"), admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"displayName\":\"New <b>Person</b>\",\"role\":\"SCHEDULER\",\"sendInvite\":true}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.emailSent").value(true))
			.andExpect(jsonPath("$.user.invitePending").value(true))
			.andExpect(jsonPath("$.user.mustChangePassword").value(false)));
		String shownLink = created.get("inviteLink").asText();
		assertThat(shownLink).startsWith("http://localhost:5173/reset-password#token=");

		String html = sentEmailTo(email);
		assertThat(linkIn(html)).isEqualTo(shownLink);
		assertThat(html).contains("New &lt;b&gt;Person&lt;/b&gt;").doesNotContain("<b>Person</b>"); // names are escaped

		String token = tokenOf(shownLink);
		mockMvc.perform(publicPost("/api/auth/reset-password/check", Map.of("token", token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purpose").value("INVITE"))
			.andExpect(jsonPath("$.email").value(email));
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", token, "newPassword", "my-chosen-password-1")))
			.andExpect(status().isNoContent());

		login(email, "my-chosen-password-1").andExpect(status().isOk());
		// Used once — never again.
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", token, "newPassword", "another-password-22")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("invalid")));
		assertThat(users.findByEmailIgnoreCase(email).orElseThrow().isMustChangePassword()).isFalse();
	}

	@Test
	void linksAlwaysUseTheConfiguredAddressNeverTheRequestHeaders() throws Exception {
		String email = "host-header" + DOMAIN;
		JsonNode created = json(mockMvc.perform(authed(post("/api/users"), admin())
				.header(HttpHeaders.HOST, "evil.example")
				.header("X-Forwarded-Host", "evil.example")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"role\":\"VIEWER\",\"sendInvite\":true}"))
			.andExpect(status().isCreated()));
		assertThat(created.get("inviteLink").asText()).startsWith("http://localhost:5173/").doesNotContain("evil");
		assertThat(linkIn(sentEmailTo(email))).doesNotContain("evil");
	}

	@Test
	void aNewInvitationReplacesTheOldOneAndIsRefusedOnceTheUserHasSignedIn() throws Exception {
		String email = "resend" + DOMAIN;
		JsonNode first = json(mockMvc.perform(authed(post("/api/users"), admin()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"role\":\"VIEWER\",\"sendInvite\":true}")).andExpect(status().isCreated()));
		String id = first.get("user").get("id").asText();

		JsonNode second = json(mockMvc.perform(authed(post("/api/users/" + id + "/invite"), admin())).andExpect(status().isOk()));
		mockMvc.perform(publicPost("/api/auth/reset-password/check", Map.of("token", tokenOf(first.get("inviteLink").asText()))))
			.andExpect(status().isBadRequest());
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", tokenOf(second.get("inviteLink").asText()), "newPassword", "viewer-password-12")))
			.andExpect(status().isNoContent());
		login(email, "viewer-password-12").andExpect(status().isOk());

		mockMvc.perform(authed(post("/api/users/" + id + "/invite"), admin()))
			.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void creatingAUserNeedsEitherAnInvitationOrATemporaryPassword() throws Exception {
		mockMvc.perform(authed(post("/api/users"), admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"neither" + DOMAIN + "\",\"role\":\"VIEWER\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("invitation")));
	}

	@Test
	void withoutEmailConfiguredTheAdminStillGetsALinkToShare() throws Exception {
		when(mailService.isConfigured()).thenReturn(false);
		mockMvc.perform(authed(post("/api/users"), admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"no-mail" + DOMAIN + "\",\"role\":\"VIEWER\",\"sendInvite\":true}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.emailSent").value(false))
			.andExpect(jsonPath("$.inviteLink").isNotEmpty());
		verify(mailService, after(300).never()).sendHtml(anyString(), anyString(), anyString());
	}

	// ── Forgot password ─────────────────────────────────────────────────────

	@Test
	void forgotPasswordResetsThePasswordAndSignsOutEverySession() throws Exception {
		String email = "forgetful" + DOMAIN;
		AdminUser user = save(email, AdminRole.SCHEDULER);
		user.setLockedUntil(Instant.now().plusSeconds(3600));
		users.save(user);
		String oldSession = bearerFor(user);

		forgot(email).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("If an account exists")));
		String token = tokenOf(linkIn(sentEmailTo(email)));

		mockMvc.perform(publicPost("/api/auth/reset-password/check", Map.of("token", token)))
			.andExpect(jsonPath("$.purpose").value("PASSWORD_RESET"));
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", token, "newPassword", "brand-new-pass-77")))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldSession)).andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isUnauthorized());
		login(email, "brand-new-pass-77").andExpect(status().isOk()); // also unlocked
	}

	@Test
	void theAnswerIsTheSameForUnknownDisabledAndDemoAccountsAndNoEmailIsSent() throws Exception {
		AdminUser disabled = save("disabled" + DOMAIN, AdminRole.VIEWER);
		disabled.setEnabled(false);
		users.save(disabled);
		save("demo" + DOMAIN, AdminRole.DEMO_ADMIN);
		save("known" + DOMAIN, AdminRole.VIEWER);
		String known = forgot("known" + DOMAIN).andReturn().getResponse().getContentAsString();

		for (String email : new String[] {"nobody" + DOMAIN, "disabled" + DOMAIN, "demo" + DOMAIN}) {
			String body = forgot(email).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
			assertThat(body).isEqualTo(known);
			verify(mailService, after(300).never()).sendHtml(eq(email), anyString(), anyString());
		}
	}

	@Test
	void onlyTheNewestResetLinkWorksAndAWeakPasswordKeepsTheLinkUsable() throws Exception {
		String email = "twice" + DOMAIN;
		save(email, AdminRole.VIEWER);
		forgot(email);
		String first = tokenOf(linkIn(sentEmailTo(email)));
		clearInvocations(mailService);
		forgot(email);
		String second = tokenOf(linkIn(sentEmailTo(email)));

		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", first, "newPassword", "first-link-pass-1")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", second, "newPassword", "short")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 10")));
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", second, "newPassword", "second-link-pass-2")))
			.andExpect(status().isNoContent());
	}

	@Test
	void expiredAndMadeUpLinksAreRejected() throws Exception {
		String email = "expired" + DOMAIN;
		save(email, AdminRole.VIEWER);
		forgot(email);
		String token = tokenOf(linkIn(sentEmailTo(email)));
		AccountToken stored = tokens.findAll().stream()
			.filter(t -> t.getUserId().equals(users.findByEmailIgnoreCase(email).orElseThrow().getId()))
			.findFirst().orElseThrow();
		stored.setExpiresAt(Instant.now().minusSeconds(1));
		tokens.save(stored);

		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", token, "newPassword", "too-late-pass-12")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(publicPost("/api/auth/reset-password", Map.of("token", "made-up-" + UUID.randomUUID(), "newPassword", "guess-pass-1234")))
			.andExpect(status().isBadRequest());
	}

	@Test
	void requestsAreLimitedPerEmailAndPerIp() throws Exception {
		String email = "flooded" + DOMAIN;
		save(email, AdminRole.VIEWER);
		for (int i = 0; i < 4; i++) {
			forgot(email).andExpect(status().isAccepted()); // the 4th is accepted but silently ignored
		}
		verify(mailService, after(500).times(3)).sendHtml(eq(email), anyString(), anyString());

		String ip = "203.0.113." + (200 + IP.getAndIncrement() % 50);
		for (int i = 1; i <= 6; i++) {
			int expected = i <= 5 ? 202 : 429;
			mockMvc.perform(publicPost("/api/auth/forgot-password", Map.of("email", "spray" + i + DOMAIN)).with(r -> { r.setRemoteAddr(ip); return r; }))
				.andExpect(status().is(expected));
		}
	}

	@Test
	void theLinkEndpointsNeedTheAppHeader() throws Exception {
		mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x" + DOMAIN + "\"}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\",\"newPassword\":\"whatever-123\"}"))
			.andExpect(status().isForbidden());
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private AdminUser save(String email, AdminRole role) {
		return users.save(AdminUser.builder().universityId(com.uniguard.schedule.tenant.TenantContext.DEFAULT_UNIVERSITY_ID).email(email).passwordHash(passwordEncoder.encode(PASSWORD)).role(role).build());
	}

	private String admin() {
		return bearerFor(users.findByEmailIgnoreCase(ADMIN).orElseThrow());
	}

	private String bearerFor(AdminUser user) {
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private String sentEmailTo(String email) {
		ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
		verify(mailService, timeout(2000)).sendHtml(eq(email), anyString(), html.capture());
		return html.getValue();
	}

	private static String linkIn(String html) {
		Matcher matcher = HREF.matcher(html);
		assertThat(matcher.find()).as("link in email").isTrue();
		return HtmlUtils.htmlUnescape(matcher.group(1));
	}

	private static String tokenOf(String link) {
		return link.substring(link.indexOf("#token=") + "#token=".length());
	}

	private ResultActions forgot(String email) throws Exception {
		return mockMvc.perform(publicPost("/api/auth/forgot-password", Map.of("email", email)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(publicPost("/api/auth/login", Map.of("email", email, "password", password)));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publicPost(String path, Map<String, String> body) throws Exception {
		String ip = "198.51.100." + (IP.getAndIncrement() % 250 + 1); // fresh IP: keeps per-IP limits out of other tests
		return post(path).with(r -> { r.setRemoteAddr(ip); return r; })
			.header(CSRF, "XMLHttpRequest")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}
}
