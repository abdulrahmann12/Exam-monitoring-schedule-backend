package schedule.example.schedule.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import schedule.example.schedule.config.AccountLinkProperties;
import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.Settings;
import schedule.example.schedule.repository.SettingsRepository;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import java.util.concurrent.Executors;

/**
 * Invitation and password-reset emails.
 *
 * <p>Sent in the background: the request returns at once, and "forgot password" takes the same time
 * whether or not the email belongs to an account. Without email configured, nothing is sent; in
 * local development ({@code app.account.log-links=true}) the link is written to the log instead.
 *
 * <p>Temporary provider problems are retried twice (after 30 seconds and 2 minutes). Each email
 * appears in the delivery log, but its content is never stored: it contains a one-time link.
 */
@Service
public class AccountEmailService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountEmailService.class);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final MailService mailService;
	/** Two background senders are plenty for account emails (retries are scheduled, not slept). */
	private final java.util.concurrent.ScheduledExecutorService taskExecutor = Executors.newScheduledThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "account-mail");
		thread.setDaemon(true);
		return thread;
	});
	private static final long[] RETRY_AFTER_SECONDS = {30, 120};
	private final AccountLinkProperties properties;
	private final SettingsRepository settingsRepository;
	private final EmailOutboxService outbox;

	public AccountEmailService(MailService mailService, AccountLinkProperties properties, SettingsRepository settingsRepository,
		EmailOutboxService outbox) {
		this.mailService = mailService;
		this.properties = properties;
		this.settingsRepository = settingsRepository;
		this.outbox = outbox;
	}

	@PreDestroy
	void stop() {
		taskExecutor.shutdown();
	}

	@PostConstruct
	void warnAboutLocalLinks() {
		String host = URI.create(properties.frontendBaseUrl()).getHost();
		if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
			LOGGER.warn("[MAIL] APP_FRONTEND_URL is '{}'. Invitation and password-reset links will point there — "
				+ "set APP_FRONTEND_URL to the real address in production.", properties.frontendBaseUrl());
		}
	}

	/** False when email is not configured (links must then be shared by hand). */
	public boolean canSend() {
		return mailService.isConfigured();
	}

	/** @return true when an email is being sent; false when email is not configured. */
	public boolean sendInvite(AdminUser user, AccountTokenService.Issued link, String invitedBy) {
		String appName = appName(user);
		String subject = "You're invited to " + appName;
		String html = layout(appName,
			"<p>Hello" + greetingName(user) + ",</p>"
				+ "<p>" + escape(invitedBy) + " invited you to <strong>" + escape(appName) + "</strong>. "
				+ "Choose your password to activate your account.</p>",
			"Choose my password", link.link(),
			"This link works once and expires on " + WHEN.format(link.expiresAt()) + ". "
				+ "If you did not expect this invitation, you can ignore this email.");
		return send(user, subject, html, "invitation", schedule.example.schedule.entity.EmailMessage.KIND_INVITE, link.link());
	}

	public boolean sendPasswordReset(AdminUser user, AccountTokenService.Issued link) {
		String appName = appName(user);
		String subject = "Reset your " + appName + " password";
		String html = layout(appName,
			"<p>Hello" + greetingName(user) + ",</p>"
				+ "<p>Someone (hopefully you) asked to reset the password of your account.</p>",
			"Reset my password", link.link(),
			"This link works once and expires on " + WHEN.format(link.expiresAt()) + ". "
				+ "If you did not ask for this, ignore this email — your password stays the same.");
		return send(user, subject, html, "password reset", schedule.example.schedule.entity.EmailMessage.KIND_PASSWORD_RESET, link.link());
	}

	private boolean send(AdminUser user, String subject, String html, String kind, String outboxKind, String link) {
		String to = user.getEmail();
		java.util.UUID universityId = user.getUniversityId();
		String name = user.getDisplayName();
		if (!mailService.isConfigured()) {
			if (properties.logLinks()) {
				LOGGER.warn("[MAIL-DEV] Email not configured. {} link for {}: {}", kind, to, link);
			} else {
				LOGGER.warn("[MAIL] Email not configured — could not send the {} email to {}.", kind, to);
			}
			// Recorded in the background as well, so "forgot password" keeps the same timing.
			taskExecutor.execute(() -> {
				java.util.UUID id = record(outboxKind, universityId, to, name, subject);
				attemptRecorded(id, false, null, "Email is not set up on the server: share the link by hand.", true);
			});
			return false;
		}
		taskExecutor.execute(() -> attempt(record(outboxKind, universityId, to, name, subject), to, subject, html, kind, 1));
		return true;
	}

	private void attempt(java.util.UUID id, String to, String subject, String html, String kind, int attemptNumber) {
		try {
			String messageId = mailService.sendHtml(to, subject, html);
			attemptRecorded(id, true, messageId, null, true);
			LOGGER.info("[MAIL] {} email sent to {}.", kind, to);
		} catch (RuntimeException ex) {
			boolean retryable = !(ex instanceof MailDeliveryException delivery) || delivery.retryable();
			boolean again = retryable && attemptNumber <= RETRY_AFTER_SECONDS.length;
			// Never log or store the link itself: production logs must not contain working links.
			attemptRecorded(id, false, null, ex.getMessage(), !again);
			if (again) {
				LOGGER.warn("[MAIL] {} email to {} failed (attempt {}), retrying: {}", kind, to, attemptNumber, ex.getMessage());
				taskExecutor.schedule(() -> attempt(id, to, subject, html, kind, attemptNumber + 1),
					RETRY_AFTER_SECONDS[attemptNumber - 1], java.util.concurrent.TimeUnit.SECONDS);
			} else {
				LOGGER.error("[MAIL] Could not send the {} email to {}: {}", kind, to, ex.getMessage());
			}
		}
	}

	/** The delivery log must never stop an email from being sent. */
	private java.util.UUID record(String kind, java.util.UUID universityId, String to, String name, String subject) {
		try {
			return outbox.recordAccountEmail(kind, universityId, to, name, subject);
		} catch (RuntimeException ex) {
			LOGGER.warn("[MAIL] Could not record the {} email to {} in the delivery log: {}", kind, to, ex.getMessage());
			return null;
		}
	}

	private void attemptRecorded(java.util.UUID id, boolean sent, String messageId, String error, boolean finalAttempt) {
		if (id == null) {
			return;
		}
		try {
			outbox.recordAttempt(id, sent, messageId, error, finalAttempt);
		} catch (RuntimeException ex) {
			LOGGER.warn("[MAIL] Could not update the delivery log: {}", ex.getMessage());
		}
	}

	private String appName(AdminUser user) {
		if (user.getUniversityId() == null) {
			return "Uni-Guard Schedules";
		}
		return settingsRepository.findById(user.getUniversityId())
			.map(Settings::getSystemName)
			.filter(name -> name != null && !name.isBlank())
			.orElse("Uni-Guard Schedules");
	}

	private static String greetingName(AdminUser user) {
		return user.getDisplayName() == null || user.getDisplayName().isBlank() ? "" : " " + escape(user.getDisplayName());
	}

	private static String layout(String appName, String intro, String buttonText, String link, String footer) {
		String safeLink = escape(link);
		return "<div style=\"font-family:Arial,sans-serif;max-width:560px;margin:auto;color:#1f2937\">"
			+ "<h2 style=\"color:#1d4ed8\">" + escape(appName) + "</h2>"
			+ intro
			+ "<p style=\"margin:28px 0\"><a href=\"" + safeLink + "\" style=\"background:#1d4ed8;color:#fff;padding:12px 20px;"
			+ "border-radius:8px;text-decoration:none;display:inline-block\">" + escape(buttonText) + "</a></p>"
			+ "<p style=\"font-size:13px;color:#6b7280\">If the button does not work, copy this address into your browser:<br>"
			+ "<span style=\"word-break:break-all\">" + safeLink + "</span></p>"
			+ "<p style=\"font-size:13px;color:#6b7280\">" + escape(footer) + "</p>"
			+ "</div>";
	}

	private static String escape(String value) {
		return value == null ? "" : HtmlUtils.htmlEscape(value);
	}
}
