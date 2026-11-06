package schedule.example.schedule.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.University;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.SsoStateRepository;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.repository.UserIdentityRepository;
import schedule.example.schedule.security.mfa.SecretCipher;
import schedule.example.schedule.security.sso.OidcHttpClient;
import schedule.example.schedule.service.SessionService;
import schedule.example.schedule.tenant.TenantContext;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in with Google / Microsoft. Google and Microsoft are replaced by a fake that signs ID tokens
 * with a test RSA key; everything else (signature, issuer, audience, expiry, nonce, PKCE, state
 * binding, account rules) runs for real.
 */
@SpringBootTest(properties = {
	"app.sso.google.client-id=test-google-client",
	"app.sso.google.client-secret=test-google-secret",
	"app.sso.microsoft.client-id=test-ms-client",
	"app.sso.microsoft.client-secret=test-ms-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SsoTest {

	private static final String PASSWORD = "sso-password-123";
	private static final String CSRF = "X-Requested-With";
	private static final String FRONTEND = "http://localhost:5173";
	private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
	private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
	private static final String MS_BASE = "https://login.microsoftonline.com/organizations";
	private static final String TENANT = "72f988bf-86f1-41af-91ab-2d7cd011db47";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AdminUserRepository users;
	@Autowired private UserIdentityRepository identities;
	@Autowired private SsoStateRepository states;
	@Autowired private UniversityRepository universities;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private SecretCipher cipher;
	@MockitoBean private OidcHttpClient http;

	private static final KeyPair PROVIDER_KEY = rsa();
	private static final KeyPair ATTACKER_KEY = rsa();
	private final AtomicReference<String> nextIdToken = new AtomicReference<>();
	private final AtomicReference<Map<String, String>> lastTokenForm = new AtomicReference<>();
	private final List<String> created = new ArrayList<>();
	private String email;

	@BeforeEach
	void setUp() {
		Map<String, Object> jwks = Map.of("keys", List.of(jwk("k1", (RSAPublicKey) PROVIDER_KEY.getPublic())));
		when(http.getJson(GOOGLE_JWKS)).thenReturn(jwks);
		when(http.getJson(MS_BASE + "/discovery/v2.0/keys")).thenReturn(jwks);
		when(http.postForm(anyString(), anyMap())).thenAnswer(invocation -> {
			lastTokenForm.set(invocation.getArgument(1));
			return Map.of("id_token", nextIdToken.get());
		});
		email = newUser(AdminRole.SCHEDULER, TenantContext.DEFAULT_UNIVERSITY_ID);
	}

	@AfterEach
	void cleanUp() {
		universities.findById(TenantContext.DEFAULT_UNIVERSITY_ID).ifPresent(university -> {
			university.setMicrosoftTenantId(null);
			universities.save(university);
		});
		for (String address : created) {
			users.findByEmailIgnoreCase(address).ifPresent(user -> {
				sessionService.deleteAllForUser(user.getId());
				users.delete(user);
			});
		}
	}

	@Test
	void theSignInPageListsTheConfiguredProviders() throws Exception {
		mockMvc.perform(get("/api/auth/sso/providers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value("microsoft"))
			.andExpect(jsonPath("$[1].id").value("google"));
	}

	@Test
	void googleWithAVerifiedEmailSignsInAndConnectsTheAccountForNextTime() throws Exception {
		Started started = start("google");
		assertThat(started.params().get("code_challenge_method")).isEqualTo("S256");
		assertThat(started.params().get("redirect_uri")).isEqualTo(FRONTEND + "/api/auth/sso/google/callback");
		nextIdToken.set(googleToken(started.nonce(), "google-sub-1", email, true, b -> { }));

		MockHttpServletResponse response = callback("google", started).andReturn().getResponse();
		assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/dashboard");
		assertThat(response.getCookie("ug_access")).isNotNull();
		assertThat(response.getCookie("ug_refresh")).isNotNull();
		// PKCE: the code was redeemed with the verifier matching the challenge sent at the start.
		assertThat(challengeOf(lastTokenForm.get().get("code_verifier"))).isEqualTo(started.params().get("code_challenge"));
		assertThat(identities.findByProviderAndSubject("google", "google-sub-1")).isPresent();

		// Next time the connected identity is used, even if the Google email changed meanwhile.
		Started again = start("google");
		nextIdToken.set(googleToken(again.nonce(), "google-sub-1", "renamed@gmail.test", false, b -> { }));
		assertThat(callback("google", again).andReturn().getResponse().getRedirectedUrl()).isEqualTo(FRONTEND + "/dashboard");
	}

	@Test
	void untrustworthyOrUnknownEmailsAreNotSignedIn() throws Exception {
		long accounts = users.count();
		Started unverified = start("google");
		nextIdToken.set(googleToken(unverified.nonce(), "google-sub-2", email, false, b -> { }));
		expectRedirect(callback("google", unverified), "/login?sso_error=not_linked");

		Started unknown = start("google");
		nextIdToken.set(googleToken(unknown.nonce(), "google-sub-3", "nobody-" + UUID.randomUUID() + "@gmail.test", true, b -> { }));
		expectRedirect(callback("google", unknown), "/login?sso_error=not_linked");
		assertThat(users.count()).isEqualTo(accounts); // never creates accounts
		assertThat(identities.findByProviderAndSubject("google", "google-sub-2")).isEmpty();
	}

	@Test
	void microsoftMatchesByEmailOnlyForTheUniversitysOwnOrganisation() throws Exception {
		Started noTenantSet = start("microsoft");
		nextIdToken.set(microsoftToken(noTenantSet.nonce(), TENANT, "oid-1", email, b -> { }));
		expectRedirect(callback("microsoft", noTenantSet), "/login?sso_error=not_linked");

		String admin = newUser(AdminRole.ADMIN, TenantContext.DEFAULT_UNIVERSITY_ID);
		mockMvc.perform(authed(put("/api/auth/sso/university"), token(admin), Map.of("microsoftTenantId", "not-a-tenant")))
			.andExpect(status().isBadRequest());
		mockMvc.perform(authed(put("/api/auth/sso/university"), token(email), Map.of("microsoftTenantId", TENANT)))
			.andExpect(status().isForbidden()); // schedulers cannot change it
		mockMvc.perform(authed(put("/api/auth/sso/university"), token(admin), Map.of("microsoftTenantId", TENANT.toUpperCase())))
			.andExpect(status().isOk()).andExpect(jsonPath("$.microsoftTenantId").value(TENANT));

		Started otherOrganisation = start("microsoft");
		String other = "11111111-2222-3333-4444-555555555555";
		nextIdToken.set(microsoftToken(otherOrganisation.nonce(), other, "oid-2", email, b -> { }));
		expectRedirect(callback("microsoft", otherOrganisation), "/login?sso_error=not_linked");

		Started own = start("microsoft");
		nextIdToken.set(microsoftToken(own.nonce(), TENANT, "oid-1", email, b -> { }));
		expectRedirect(callback("microsoft", own), "/dashboard");
		assertThat(identities.findByProviderAndSubject("microsoft", TENANT + "/oid-1")).isPresent();

		// A token whose issuer does not match its organisation is refused.
		Started badIssuer = start("microsoft");
		nextIdToken.set(microsoftToken(badIssuer.nonce(), TENANT, "oid-1", email,
			b -> b.issuer("https://login.microsoftonline.com/" + other + "/v2.0")));
		expectRedirect(callback("microsoft", badIssuer), "/login?sso_error=failed");
	}

	@Test
	void theReturnMustComeBackToTheSameBrowserOnceAndInTime() throws Exception {
		Started started = start("google");
		nextIdToken.set(googleToken(started.nonce(), "google-sub-4", email, true, b -> { }));
		// No cookie (link opened in another browser), or a different cookie: refused.
		expectRedirect(mockMvc.perform(get("/api/auth/sso/google/callback").param("code", "c").param("state", started.state())), "/login?sso_error=expired");
		expectRedirect(mockMvc.perform(get("/api/auth/sso/google/callback").param("code", "c").param("state", started.state())
			.cookie(new Cookie("ug_sso", "someone-else"))), "/login?sso_error=expired");
		// For the other provider: refused (and the state is used up).
		expectRedirect(callback("microsoft", started), "/login?sso_error=expired");
		expectRedirect(callback("google", started), "/login?sso_error=expired");

		Started late = start("google");
		states.findAll().forEach(row -> {
			row.setExpiresAt(Instant.now().minusSeconds(1));
			states.save(row);
		});
		nextIdToken.set(googleToken(late.nonce(), "google-sub-4", email, true, b -> { }));
		expectRedirect(callback("google", late), "/login?sso_error=expired");

		Started used = start("google");
		nextIdToken.set(googleToken(used.nonce(), "google-sub-4", email, true, b -> { }));
		expectRedirect(callback("google", used), "/dashboard");
		expectRedirect(callback("google", used), "/login?sso_error=expired"); // single use
	}

	@Test
	void forgedOrWrongTokensAreRefused() throws Exception {
		List<Consumer<io.jsonwebtoken.JwtBuilder>> tamper = List.of(
			b -> b.claim("nonce", "replayed-nonce"),
			b -> b.audience().add("someone-elses-client"),
			b -> b.issuer("https://evil.example"),
			b -> b.expiration(Date.from(Instant.now().minusSeconds(600))));
		for (Consumer<io.jsonwebtoken.JwtBuilder> change : tamper) {
			Started started = start("google");
			nextIdToken.set(googleToken(started.nonce(), "google-sub-5", email, true, change));
			expectRedirect(callback("google", started), "/login?sso_error=failed");
		}
		Started otherApp = start("google"); // a token issued only for another application
		nextIdToken.set(Jwts.builder().header().keyId("k1").and()
			.issuer("https://accounts.google.com").audience().add("someone-elses-client").and().subject("google-sub-5")
			.claim("email", email).claim("email_verified", true).claim("nonce", otherApp.nonce())
			.expiration(Date.from(Instant.now().plusSeconds(300))).signWith(PROVIDER_KEY.getPrivate()).compact());
		expectRedirect(callback("google", otherApp), "/login?sso_error=failed");

		Started attacker = start("google");
		nextIdToken.set(Jwts.builder().header().keyId("k1").and()
			.issuer("https://accounts.google.com").audience().add("test-google-client").and().subject("google-sub-5")
			.claim("email", email).claim("email_verified", true).claim("nonce", attacker.nonce())
			.expiration(Date.from(Instant.now().plusSeconds(300))).signWith(ATTACKER_KEY.getPrivate()).compact());
		expectRedirect(callback("google", attacker), "/login?sso_error=failed");

		Started unsigned = start("google");
		nextIdToken.set(Jwts.builder().issuer("https://accounts.google.com").audience().add("test-google-client").and()
			.subject("google-sub-5").claim("email", email).claim("email_verified", true).claim("nonce", unsigned.nonce())
			.expiration(Date.from(Instant.now().plusSeconds(300))).compact());
		expectRedirect(callback("google", unsigned), "/login?sso_error=failed");
		assertThat(identities.findByProviderAndSubject("google", "google-sub-5")).isEmpty();

		Started cancelled = start("google");
		expectRedirect(mockMvc.perform(get("/api/auth/sso/google/callback").param("error", "access_denied")
			.param("state", cancelled.state()).cookie(new Cookie("ug_sso", cancelled.state()))), "/login?sso_error=cancelled");
	}

	@Test
	void accountRulesStillApplyAndTwoStepSignInStillAsksForTheCode() throws Exception {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		user.setTotpEnabled(true);
		user.setTotpSecret(cipher.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"));
		users.save(user);
		Started started = start("google");
		nextIdToken.set(googleToken(started.nonce(), "google-sub-6", email, true, b -> { }));
		MockHttpServletResponse response = callback("google", started).andReturn().getResponse();
		assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?step=two-factor");
		assertThat(response.getCookie("ug_mfa")).isNotNull();
		assertThat(response.getCookie("ug_access")).isNull();

		user = users.findByEmailIgnoreCase(email).orElseThrow();
		user.setTotpEnabled(false);
		user.setEnabled(false);
		users.save(user);
		Started disabled = start("google");
		nextIdToken.set(googleToken(disabled.nonce(), "google-sub-6", email, true, b -> { }));
		expectRedirect(callback("google", disabled), "/login?sso_error=disabled");

		user = users.findByEmailIgnoreCase(email).orElseThrow();
		user.setEnabled(true);
		user.setLockedUntil(Instant.now().plusSeconds(3600));
		users.save(user);
		Started locked = start("google");
		nextIdToken.set(googleToken(locked.nonce(), "google-sub-6", email, true, b -> { }));
		expectRedirect(callback("google", locked), "/login?sso_error=locked");

		user = users.findByEmailIgnoreCase(email).orElseThrow();
		user.setLockedUntil(null);
		users.save(user);
		University university = universities.findById(TenantContext.DEFAULT_UNIVERSITY_ID).orElseThrow();
		university.setActive(false);
		universities.save(university);
		try {
			Started paused = start("google");
			nextIdToken.set(googleToken(paused.nonce(), "google-sub-6", email, true, b -> { }));
			expectRedirect(callback("google", paused), "/login?sso_error=paused");
		} finally {
			university.setActive(true);
			universities.save(university);
		}
	}

	@Test
	void signedInUsersConnectAndDisconnectAnAccountAfterConfirmingTheirPassword() throws Exception {
		String token = token(email);
		mockMvc.perform(authed(post("/api/auth/sso/google/link"), token, Map.of("currentPassword", "wrong-password-1")))
			.andExpect(status().isBadRequest());
		MockHttpServletResponse linkStart = mockMvc.perform(authed(post("/api/auth/sso/google/link"), token,
				Map.of("currentPassword", PASSWORD, "returnPath", "https://evil.example")))
			.andExpect(status().isOk()).andReturn().getResponse();
		String url = objectMapper.readTree(linkStart.getContentAsString()).get("url").asText();
		Map<String, String> params = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
		Started started = new Started(params.get("state"), params.get("nonce"), params);
		// Unverified email is fine here: the person proved their password, the identity is what counts.
		nextIdToken.set(googleToken(started.nonce(), "google-sub-7", "private-address@gmail.test", false, b -> { }));
		expectRedirect(callback("google", started), "/settings?sso=linked"); // never the address given in the request

		JsonNode connections = objectMapper.readTree(mockMvc.perform(authed(get("/api/auth/sso/connections"), token))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(connections).hasSize(1);
		assertThat(connections.get(0).get("email").asText()).isEqualTo("private-address@gmail.test");
		mockMvc.perform(authed(post("/api/auth/sso/google/link"), token, Map.of("currentPassword", PASSWORD)))
			.andExpect(status().isUnprocessableEntity()); // already connected

		// The same Google identity cannot be connected to a second account.
		String other = newUser(AdminRole.VIEWER, TenantContext.DEFAULT_UNIVERSITY_ID);
		String otherUrl = objectMapper.readTree(mockMvc.perform(authed(post("/api/auth/sso/google/link"), token(other),
			Map.of("currentPassword", PASSWORD))).andReturn().getResponse().getContentAsString()).get("url").asText();
		Map<String, String> otherParams = UriComponentsBuilder.fromUriString(otherUrl).build().getQueryParams().toSingleValueMap();
		Started otherStarted = new Started(otherParams.get("state"), otherParams.get("nonce"), otherParams);
		nextIdToken.set(googleToken(otherStarted.nonce(), "google-sub-7", "private-address@gmail.test", false, b -> { }));
		expectRedirect(callback("google", otherStarted), "/settings?sso_error=in_use");

		// Another user cannot remove my connection; I can.
		String id = connections.get(0).get("id").asText();
		mockMvc.perform(authed(delete("/api/auth/sso/connections/" + id), token(other))).andExpect(status().isNotFound());
		mockMvc.perform(authed(delete("/api/auth/sso/connections/" + id), token)).andExpect(status().isNoContent());
		assertThat(identities.findByProviderAndSubject("google", "google-sub-7")).isEmpty();
	}

	@Test
	void anExpiredLoginCookieDoesNotBreakTheReturnFromGoogle() throws Exception {
		Started started = start("google");
		nextIdToken.set(googleToken(started.nonce(), "google-sub-8", email, true, b -> { }));
		expectRedirect(mockMvc.perform(get("/api/auth/sso/google/callback").param("code", "c").param("state", started.state())
			.cookie(new Cookie("ug_sso", started.state()), new Cookie("ug_access", "expired.or.garbage"))), "/dashboard");
	}

	// ─────────────────────────── helpers ───────────────────────────

	private record Started(String state, String nonce, Map<String, String> params) {
	}

	private Started start(String provider) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(get("/api/auth/sso/" + provider + "/start")
				.with(request -> { request.setRemoteAddr("198.21.0." + (Math.abs(UUID.randomUUID().hashCode()) % 250)); return request; }))
			.andExpect(status().isFound()).andReturn().getResponse();
		Map<String, String> params = UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build().getQueryParams().toSingleValueMap();
		Map<String, String> decoded = new HashMap<>();
		params.forEach((key, value) -> decoded.put(key, java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)));
		Cookie cookie = response.getCookie("ug_sso");
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getValue()).isEqualTo(decoded.get("state"));
		return new Started(decoded.get("state"), decoded.get("nonce"), decoded);
	}

	private ResultActions callback(String provider, Started started) throws Exception {
		return mockMvc.perform(get("/api/auth/sso/" + provider + "/callback").param("code", "auth-code").param("state", started.state())
			.cookie(new Cookie("ug_sso", started.state())));
	}

	private static void expectRedirect(ResultActions actions, String path) throws Exception {
		actions.andExpect(status().isFound());
		assertThat(actions.andReturn().getResponse().getRedirectedUrl()).isEqualTo(FRONTEND + path);
	}

	private static String googleToken(String nonce, String sub, String email, boolean verified, Consumer<io.jsonwebtoken.JwtBuilder> change) {
		io.jsonwebtoken.JwtBuilder builder = Jwts.builder().header().keyId("k1").and()
			.issuer("https://accounts.google.com").audience().add("test-google-client").and()
			.subject(sub).claim("email", email).claim("email_verified", verified).claim("nonce", nonce)
			.issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(300)));
		change.accept(builder);
		return builder.signWith(PROVIDER_KEY.getPrivate()).compact();
	}

	private static String microsoftToken(String nonce, String tid, String oid, String email, Consumer<io.jsonwebtoken.JwtBuilder> change) {
		io.jsonwebtoken.JwtBuilder builder = Jwts.builder().header().keyId("k1").and()
			.issuer("https://login.microsoftonline.com/" + tid + "/v2.0").audience().add("test-ms-client").and()
			.subject("pairwise-" + oid).claim("tid", tid).claim("oid", oid).claim("preferred_username", email).claim("nonce", nonce)
			.issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(300)));
		change.accept(builder);
		return builder.signWith(PROVIDER_KEY.getPrivate()).compact();
	}

	private static Map<String, Object> jwk(String kid, RSAPublicKey key) {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return Map.of("kty", "RSA", "kid", kid, "use", "sig", "alg", "RS256",
			"n", encoder.encodeToString(unsigned(key.getModulus())), "e", encoder.encodeToString(unsigned(key.getPublicExponent())));
	}

	private static byte[] unsigned(BigInteger value) {
		byte[] bytes = value.toByteArray();
		return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
	}

	private static String challengeOf(String verifier) throws Exception {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
			MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
	}

	private static KeyPair rsa() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String newUser(AdminRole role, UUID universityId) {
		String address = "sso-" + UUID.randomUUID().toString().substring(0, 8) + "@sso.test";
		users.save(AdminUser.builder().email(address).passwordHash(passwordEncoder.encode(PASSWORD)).role(role).universityId(universityId).build());
		created.add(address);
		return address;
	}

	private String token(String address) {
		AdminUser user = users.findByEmailIgnoreCase(address).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest")
			.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}
}
