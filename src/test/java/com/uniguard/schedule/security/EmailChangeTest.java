package com.uniguard.schedule.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.service.SessionService;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing the sign-in email: the new address must confirm a one-time link first, so a typo or
 * someone else's address can never take the account over (via "forgot password" or SSO by email).
 */
class EmailChangeTest {

	private static final String DOMAIN = "@email-change.test";
	private static final String OWNER = "owner" + DOMAIN;
	private static final String VIEWER = "viewer" + DOMAIN;
	private static final String NEW_EMAIL = "new-owner" + DOMAIN;
	private static final String PASSWORD = "correct-horse-battery";
	private static final String CSRF_HEADER = "X-Requested-With";
	private static final AtomicInteger IP = new AtomicInteger(1);

	abstract static class Base {
		@Autowired MockMvc mockMvc;
		@Autowired ObjectMapper objectMapper;
		@Autowired AdminUserRepository users;
		@Autowired PasswordEncoder passwordEncoder;
		@Autowired JwtTokenProvider jwt;
		@Autowired SessionService sessionService;

		@BeforeEach
		void createAccounts() {
			users.save(AdminUser.builder().universityId(null).email(OWNER)
				.passwordHash(passwordEncoder.encode(PASSWORD)).role(AdminRole.SUPER_ADMIN).build());
			users.save(AdminUser.builder().universityId(com.uniguard.schedule.tenant.TenantContext.DEFAULT_UNIVERSITY_ID).email(VIEWER)
				.passwordHash(passwordEncoder.encode(PASSWORD)).role(AdminRole.VIEWER).build());
		}

		@AfterEach
		void cleanUp() {
			users.findAll().stream().filter(u -> u.getEmail().endsWith(DOMAIN)).forEach(users::delete);
		}

		String token(String email) {
			AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
			var session = sessionService.start(user, null).session();
			return jwt.generateToken(user.getEmail(), user.getTokenVersion(), session.getId()).token();
		}

		MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
			MockHttpServletRequestBuilder request = builder.header(CSRF_HEADER, "XMLHttpRequest")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
			return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}

		String secretOf(String link) {
			return link.substring(link.indexOf("#token=") + "#token=".length());
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	@TestPropertySource(properties = { "app.account.log-links=true", "app.brevo.api-key=" }) // local development: no email, link handed back
	class WithConfirmationLink extends Base {

		@Test
		void theEmailChangesOnlyAfterTheNewAddressConfirms() throws Exception {
			String owner = token(OWNER);
			String body = mockMvc.perform(json(post("/api/auth/email-change"), owner, Map.of("newEmail", "New-Owner" + DOMAIN, "currentPassword", PASSWORD)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.newEmail").value(NEW_EMAIL))
				.andExpect(jsonPath("$.emailSent").value(false))
				.andReturn().getResponse().getContentAsString();
			String link = objectMapper.readTree(body).get("devLink").asText();
			assertThat(link).contains("/confirm-email#token=");

			// Nothing changed yet.
			mockMvc.perform(json(get("/api/auth/me"), owner, Map.of())).andExpect(jsonPath("$.email").value(OWNER));

			// The link can never be used to set a password.
			mockMvc.perform(json(post("/api/auth/reset-password/check"), null, Map.of("token", secretOf(link)))).andExpect(status().isBadRequest());
			mockMvc.perform(json(post("/api/auth/reset-password"), null, Map.of("token", secretOf(link), "newPassword", "another-password-1")))
				.andExpect(status().isBadRequest());

			mockMvc.perform(json(post("/api/auth/email-change/confirm"), null, Map.of("token", secretOf(link))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(containsString(NEW_EMAIL)));

			assertThat(users.findByEmailIgnoreCase(NEW_EMAIL)).isPresent();
			assertThat(users.findByEmailIgnoreCase(OWNER)).isEmpty();
			// Every device signed out (the old email is in their tokens).
			mockMvc.perform(json(get("/api/auth/me"), owner, Map.of())).andExpect(status().isUnauthorized());
			// Signing in works with the new email and the same password.
			String ip = "198.18.1." + IP.getAndIncrement();
			mockMvc.perform(json(post("/api/auth/login"), null, Map.of("email", NEW_EMAIL, "password", PASSWORD))
					.with(request -> { request.setRemoteAddr(ip); return request; }))
				.andExpect(status().isOk());
			// The link works once.
			mockMvc.perform(json(post("/api/auth/email-change/confirm"), null, Map.of("token", secretOf(link)))).andExpect(status().isBadRequest());
		}

		@Test
		void wrongPasswordSameEmailOrTakenEmailAreRefused() throws Exception {
			String owner = token(OWNER);
			mockMvc.perform(json(post("/api/auth/email-change"), owner, Map.of("newEmail", NEW_EMAIL, "currentPassword", "wrong-password-1")))
				.andExpect(status().isBadRequest());
			mockMvc.perform(json(post("/api/auth/email-change"), owner, Map.of("newEmail", OWNER.toUpperCase(), "currentPassword", PASSWORD)))
				.andExpect(status().isBadRequest());
			mockMvc.perform(json(post("/api/auth/email-change"), owner, Map.of("newEmail", VIEWER, "currentPassword", PASSWORD)))
				.andExpect(status().isConflict());
			mockMvc.perform(json(post("/api/auth/email-change"), owner, Map.of("newEmail", "not-an-email", "currentPassword", PASSWORD)))
				.andExpect(status().isBadRequest());
			mockMvc.perform(json(post("/api/auth/email-change"), null, Map.of("newEmail", NEW_EMAIL, "currentPassword", PASSWORD)))
				.andExpect(status().isUnauthorized());
		}

		@Test
		void anAddressTakenMeanwhileIsNotStolenAtConfirmation() throws Exception {
			JsonNode started = objectMapper.readTree(mockMvc.perform(json(post("/api/auth/email-change"), token(VIEWER),
					Map.of("newEmail", NEW_EMAIL, "currentPassword", PASSWORD)))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
			users.save(AdminUser.builder().universityId(null).email(NEW_EMAIL)
				.passwordHash(passwordEncoder.encode(PASSWORD)).role(AdminRole.SUPER_ADMIN).build());

			mockMvc.perform(json(post("/api/auth/email-change/confirm"), null, Map.of("token", secretOf(started.get("devLink").asText()))))
				.andExpect(status().isConflict());
			assertThat(users.findByEmailIgnoreCase(VIEWER)).isPresent();
		}

		@Test
		void changingThePasswordCancelsAPendingEmailChange() throws Exception {
			String owner = token(OWNER);
			JsonNode started = objectMapper.readTree(mockMvc.perform(json(post("/api/auth/email-change"), owner,
					Map.of("newEmail", NEW_EMAIL, "currentPassword", PASSWORD)))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
			mockMvc.perform(json(post("/api/auth/change-password"), owner, Map.of("currentPassword", PASSWORD, "newPassword", "a-brand-new-password")))
				.andExpect(status().isOk());

			mockMvc.perform(json(post("/api/auth/email-change/confirm"), null, Map.of("token", secretOf(started.get("devLink").asText()))))
				.andExpect(status().isBadRequest());
			assertThat(users.findByEmailIgnoreCase(OWNER)).isPresent();
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	@TestPropertySource(properties = { "app.account.log-links=false", "app.brevo.api-key=" }) // production without email
	class WithoutEmailInProduction extends Base {

		@Test
		void isRefusedAndNeverHandsOutTheLink() throws Exception {
			mockMvc.perform(json(post("/api/auth/email-change"), token(OWNER), Map.of("newEmail", NEW_EMAIL, "currentPassword", PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(containsString("Email is not set up")))
				.andExpect(jsonPath("$.devLink").doesNotExist());
			assertThat(users.findByEmailIgnoreCase(OWNER)).isPresent();
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	@TestPropertySource(properties = "app.account.log-links=true") // email set up: the link only goes by email
	class WithEmail extends Base {

		@Test
		void theLinkIsEmailedNeverReturned() throws Exception {
			mockMvc.perform(json(post("/api/auth/email-change"), token(OWNER), Map.of("newEmail", NEW_EMAIL, "currentPassword", PASSWORD)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.emailSent").value(true))
				.andExpect(jsonPath("$.devLink").doesNotExist());
		}
	}
}
