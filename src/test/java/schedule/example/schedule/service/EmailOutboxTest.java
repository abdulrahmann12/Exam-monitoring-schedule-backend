package schedule.example.schedule.service;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.EmailMessage;
import schedule.example.schedule.entity.University;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.EmailMessageRepository;
import schedule.example.schedule.security.JwtTokenProvider;
import schedule.example.schedule.tenant.TenantContext;
import schedule.example.schedule.tenant.UniversityProvisioning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reliable email: "send schedules" queues and returns at once, a worker sends with retries, nothing
 * is sent twice, failures are visible in the delivery log. Only the email provider (Brevo) is replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailOutboxTest {

	private static final String PASSWORD = "outbox-password-1";
	private static final String CSRF = "X-Requested-With";
	private static final AtomicInteger COUNTER = new AtomicInteger();

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private EmailOutboxService outbox;
	@Autowired private EmailMessageRepository messages;
	@Autowired private AdminUserRepository users;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private SessionService sessionService;
	@Autowired private JwtTokenProvider jwt;
	@Autowired private UniversityProvisioning provisioning;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private MailService mailService;

	private final List<String> createdUsers = new ArrayList<>();
	private String admin;
	private Schedule schedule;

	@BeforeEach
	void setUp() throws Exception {
		when(mailService.isConfigured()).thenReturn(true);
		doReturn("brevo-message-1").when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		admin = token(newUser(AdminRole.ADMIN, TenantContext.DEFAULT_UNIVERSITY_ID));
		schedule = createSchedule();
	}

	@AfterEach
	void cleanUp() {
		messages.deleteAll();
		for (String email : createdUsers) {
			users.findByEmailIgnoreCase(email).ifPresent(user -> {
				sessionService.deleteAllForUser(user.getId());
				users.delete(user);
			});
		}
	}

	@Test
	void sendingSchedulesQueuesOneEmailPerPersonAndReturnsAtOnce() throws Exception {
		JsonNode result = notifySchedule(false);
		assertThat(result.get("queued").asInt()).isEqualTo(4);
		assertThat(result.get("skipped").asInt()).isEqualTo(1); // the person without an email address
		assertThat(result.get("unchanged").asInt()).isZero();
		verify(mailService, never()).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any()); // nothing sent in the request

		assertThat(outbox.processDue()).isEqualTo(4);
		verify(mailService, times(4)).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		List<EmailMessage> all = messages.findAll();
		assertThat(all).hasSize(4).allSatisfy(message -> {
			assertThat(message.getStatus()).isEqualTo(EmailMessage.SENT);
			assertThat(message.getProviderMessageId()).isEqualTo("brevo-message-1");
			assertThat(message.getHtmlBody()).isNull();   // content removed after sending
			assertThat(message.getAttachment()).isNull();
			assertThat(message.getUniversityId()).isEqualTo(TenantContext.DEFAULT_UNIVERSITY_ID);
		});
		assertThat(outbox.processDue()).isZero(); // nothing left
	}

	@Test
	void clickingAgainSendsNothingTwiceAndChangesGoOnlyToThoseAffected() throws Exception {
		notifySchedule(false);
		outbox.processDue();
		JsonNode again = notifySchedule(false);
		assertThat(again.get("queued").asInt()).isZero();
		assertThat(again.get("unchanged").asInt()).isEqualTo(4);

		// Change only the second exam room: its two people get a new email, the others do not.
		mockMvc.perform(authed(put("/api/assignments/" + schedule.secondAssignment()), admin, assignment(schedule, schedule.secondRoom(),
				schedule.people().get(2), List.of(schedule.people().get(3)), "Physics 2")))
			.andExpect(status().isOk());
		JsonNode afterChange = notifySchedule(false);
		assertThat(afterChange.get("queued").asInt()).isEqualTo(2);
		assertThat(afterChange.get("unchanged").asInt()).isEqualTo(2);

		JsonNode everybody = notifySchedule(true);
		assertThat(everybody.get("queued").asInt()).isEqualTo(4);
	}

	@Test
	void aNewerVersionReplacesOneThatWasNotSentYet() throws Exception {
		notifySchedule(false);
		JsonNode resend = notifySchedule(true); // before the worker ran
		assertThat(resend.get("queued").asInt()).isEqualTo(4);
		assertThat(messages.findAll().stream().filter(m -> "CANCELLED".equals(m.getStatus())).count()).isEqualTo(4);
		assertThat(outbox.processDue()).isEqualTo(4); // each person still gets exactly one email
	}

	@Test
	void temporaryProblemsAreRetriedLaterAndPermanentOnesFailWithTheReason() throws Exception {
		doThrow(new MailDeliveryException("Brevo answered 503: busy", true, null)).when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		notifySchedule(false);
		// The worker runs every few seconds; give it up to three runs, as in the app.
		for (int run = 0; run < 3 && messages.findAll().stream().anyMatch(m -> m.getAttempts() == 0); run++) {
			outbox.processDue();
		}
		List<EmailMessage> waiting = messages.findAll();
		assertThat(waiting).allSatisfy(message -> {
			assertThat(message.getStatus()).isEqualTo(EmailMessage.QUEUED);
			assertThat(message.getAttempts()).isEqualTo(1);
			assertThat(message.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(30)); // about a minute later
			assertThat(message.getLastError()).contains("will retry").contains("503");
		});
		assertThat(outbox.processDue()).isZero(); // not due yet

		// Brevo is back: once due, they are sent.
		clearInvocations(mailService);
		doReturn("brevo-message-2").when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		makeAllDue();
		assertThat(outbox.processDue()).isEqualTo(4);
		assertThat(messages.findAll()).allSatisfy(message -> assertThat(message.getStatus()).isEqualTo(EmailMessage.SENT));

		// A rejected address fails at once and keeps the reason; it can be sent again later.
		doThrow(new MailDeliveryException("Brevo answered 400: invalid email", false, null)).when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		notifySchedule(true);
		outbox.processDue();
		List<EmailMessage> failed = messages.findAll().stream().filter(m -> EmailMessage.FAILED.equals(m.getStatus())).toList();
		assertThat(failed).hasSize(4).allSatisfy(message -> {
			assertThat(message.getAttempts()).isEqualTo(1);
			assertThat(message.getLastError()).contains("invalid email");
			assertThat(message.getHtmlBody()).isNotNull(); // kept so it can be retried
		});

		doReturn("brevo-message-3").when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		mockMvc.perform(authed(post("/api/email-outbox/" + failed.get(0).getId() + "/retry"), admin)).andExpect(status().isNoContent());
		mockMvc.perform(authed(post("/api/email-outbox/retry-failed"), admin)).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(3));
		assertThat(outbox.processDue()).isEqualTo(4);
		assertThat(messages.findAll().stream().filter(m -> EmailMessage.FAILED.equals(m.getStatus()))).isEmpty();
	}

	@Test
	void anEmailThatKeepsFailingStopsAfterFiveAttempts() throws Exception {
		doThrow(new MailDeliveryException("timeout", true, null)).when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		notifySchedule(false);
		for (int attempt = 1; attempt <= 6; attempt++) {
			makeAllDue();
			outbox.processDue();
		}
		assertThat(messages.findAll()).allSatisfy(message -> {
			assertThat(message.getStatus()).isEqualTo(EmailMessage.FAILED);
			assertThat(message.getAttempts()).isEqualTo(EmailOutboxService.MAX_ATTEMPTS);
		});
		verify(mailService, times(4 * EmailOutboxService.MAX_ATTEMPTS)).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void anEmailIsTakenByOnlyOneWorkerAndACrashedWorkerReleasesIt() throws Exception {
		notifySchedule(false);
		UUID id = messages.findAll().get(0).getId();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		Instant now = Instant.now();
		Integer firstWorker = tx.execute(s -> messages.claim(id, now, now.minusSeconds(600)));
		Integer secondWorker = tx.execute(s -> messages.claim(id, now, now.minusSeconds(600)));
		assertThat(firstWorker).isEqualTo(1);
		assertThat(secondWorker).isZero(); // second worker: no

		// The first worker "crashed": 10 minutes later the email is taken again and sent.
		tx.executeWithoutResult(s -> messages.findById(id).ifPresent(m -> {
			m.setClaimedAt(Instant.now().minusSeconds(11 * 60));
			messages.save(m);
		}));
		assertThat(outbox.processDue()).isEqualTo(4);
		assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(EmailMessage.SENT);
	}

	@Test
	void theDeliveryLogShowsStatusButNoContentAndOnlyToItsOwnUniversity() throws Exception {
		doThrow(new MailDeliveryException("Brevo answered 400: invalid email", false, null)).when(mailService).sendHtmlWithPdf(anyString(), anyString(), anyString(), anyString(), any());
		notifySchedule(false);
		outbox.processDue();

		String scheduler = token(newUser(AdminRole.SCHEDULER, TenantContext.DEFAULT_UNIVERSITY_ID));
		String log = mockMvc.perform(authed(get("/api/email-outbox?size=50"), scheduler)).andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(4))
			.andExpect(jsonPath("$.content[0].status").value("FAILED"))
			.andExpect(jsonPath("$.content[0].lastError").value(org.hamcrest.Matchers.containsString("invalid email")))
			.andReturn().getResponse().getContentAsString();
		assertThat(log).doesNotContain("htmlBody").doesNotContain("attachment\"").doesNotContain("<div");
		mockMvc.perform(authed(get("/api/email-outbox/counts"), scheduler)).andExpect(jsonPath("$.counts.FAILED").value(4));
		mockMvc.perform(authed(get("/api/email-outbox?status=sent"), scheduler)).andExpect(jsonPath("$.content.length()").value(0));

		String viewer = token(newUser(AdminRole.VIEWER, TenantContext.DEFAULT_UNIVERSITY_ID));
		mockMvc.perform(authed(get("/api/email-outbox"), viewer)).andExpect(status().isForbidden());

		University other = provisioning.create("Outbox Other University", "outbox-other-" + COUNTER.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6));
		String otherAdmin = token(newUser(AdminRole.ADMIN, other.getId()));
		mockMvc.perform(authed(get("/api/email-outbox"), otherAdmin)).andExpect(jsonPath("$.content.length()").value(0));
		UUID someoneElses = messages.findAll().get(0).getId();
		mockMvc.perform(authed(post("/api/email-outbox/" + someoneElses + "/retry"), otherAdmin)).andExpect(status().isNotFound());
	}

	@Test
	void invitationEmailsAreLoggedWithoutTheirOneTimeLink() throws Exception {
		when(mailService.sendHtml(anyString(), anyString(), anyString())).thenReturn("brevo-invite-1");
		String email = "invited-" + UUID.randomUUID().toString().substring(0, 8) + "@outbox.test";
		createdUsers.add(email);
		mockMvc.perform(authed(post("/api/users"), admin, Map.of("email", email, "role", "VIEWER", "sendInvite", true)))
			.andExpect(status().isCreated());

		EmailMessage logged = null;
		for (int i = 0; i < 50 && (logged == null || !EmailMessage.SENT.equals(logged.getStatus())); i++) {
			Thread.sleep(100); // sent by a background thread
			logged = messages.findAll().stream().filter(m -> email.equals(m.getRecipient())).findFirst().orElse(null);
		}
		assertThat(logged).isNotNull();
		assertThat(logged.getKind()).isEqualTo(EmailMessage.KIND_INVITE);
		assertThat(logged.getStatus()).isEqualTo(EmailMessage.SENT);
		assertThat(logged.getHtmlBody()).isNull(); // the link is never stored
		mockMvc.perform(authed(post("/api/email-outbox/" + logged.getId() + "/retry"), admin)).andExpect(status().isUnprocessableEntity());
	}

	// ─────────────────────────── helpers ───────────────────────────

	private record Schedule(String groupId, String slotId, String firstRoom, String secondRoom, String firstAssignment, String secondAssignment,
		List<String> people) {
	}

	/** Two exam rooms, four staff with email addresses and one without. */
	private Schedule createSchedule() throws Exception {
		int n = COUNTER.incrementAndGet();
		String suffix = n + "-" + UUID.randomUUID().toString().substring(0, 6);
		String groupId = id(mockMvc.perform(authed(post("/api/schedule-groups"), admin, Map.of("name", "Outbox Period " + suffix))));
		String slotId = id(mockMvc.perform(authed(post("/api/slots"), admin, Map.of("scheduleGroupId", groupId, "label", "Morning",
			"startTime", "09:00:00", "endTime", "12:00:00", "sortOrder", 1))));
		String room1 = id(mockMvc.perform(authed(post("/api/rooms"), admin, room("Outbox Hall A " + suffix))));
		String room2 = id(mockMvc.perform(authed(post("/api/rooms"), admin, room("Outbox Hall B " + suffix))));
		List<String> people = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			people.add(id(mockMvc.perform(authed(post("/api/people"), admin, person("Outbox Person " + i + " " + suffix,
				"person" + i + "-" + suffix + "@outbox.test", i % 2 == 0 ? "CHIEF_INVIGILATOR" : "INVIGILATOR")))));
		}
		String noEmail = id(mockMvc.perform(authed(post("/api/people"), admin, person("Outbox NoEmail " + suffix, null, "INVIGILATOR"))));
		Schedule draft = new Schedule(groupId, slotId, room1, room2, null, null, people);
		String a1 = id(mockMvc.perform(authed(post("/api/assignments"), admin,
			assignment(draft, room1, people.get(0), List.of(people.get(1), noEmail), "Maths 1"))));
		String a2 = id(mockMvc.perform(authed(post("/api/assignments"), admin,
			assignment(draft, room2, people.get(2), List.of(people.get(3)), "Physics 1"))));
		return new Schedule(groupId, slotId, room1, room2, a1, a2, people);
	}

	private static Map<String, Object> room(String name) {
		return Map.of("name", name, "capacity", 40, "type", "MEDIUM", "minInvigilators", 1, "hallGender", "MIXED");
	}

	private static Map<String, Object> person(String name, String email, String role) {
		java.util.HashMap<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "department", "Exams", "role", role,
			"availableDays", List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")));
		if (email != null) {
			body.put("email", email);
		}
		return body;
	}

	private static Map<String, Object> assignment(Schedule s, String roomId, String chiefId, List<String> invigilators, String subject) {
		return Map.of("scheduleGroupId", s.groupId(), "examDate", "2027-01-10", "roomId", roomId, "timeSlotId", s.slotId(),
			"subjectName", subject, "chiefInvigilatorId", chiefId, "locked", false, "invigilatorIds", invigilators);
	}

	private JsonNode notifySchedule(boolean resendAll) throws Exception {
		return objectMapper.readTree(mockMvc.perform(authed(post("/api/schedule-groups/" + schedule.groupId() + "/notify?resendAll=" + resendAll), admin))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private void makeAllDue() {
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> messages.findAll().forEach(m -> {
			if (EmailMessage.QUEUED.equals(m.getStatus())) {
				m.setNextAttemptAt(Instant.now().minusSeconds(1));
				messages.save(m);
			}
		}));
	}

	private String id(ResultActions actions) throws Exception {
		String body = actions.andReturn().getResponse().getContentAsString();
		assertThat(actions.andReturn().getResponse().getStatus()).as(body).isBetween(200, 299);
		return objectMapper.readTree(body).get("id").asText();
	}

	private String newUser(AdminRole role, UUID universityId) {
		String email = "outbox-" + UUID.randomUUID().toString().substring(0, 8) + "@outbox.test";
		users.save(AdminUser.builder().email(email).passwordHash(passwordEncoder.encode(PASSWORD)).role(role).universityId(universityId).build());
		createdUsers.add(email);
		return email;
	}

	private String token(String email) {
		AdminUser user = users.findByEmailIgnoreCase(email).orElseThrow();
		UUID sid = sessionService.start(user, null).session().getId();
		return jwt.generateToken(user.getEmail(), user.getTokenVersion(), sid).token();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(CSRF, "XMLHttpRequest");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token, Object body) throws Exception {
		return authed(builder, token).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}
}
