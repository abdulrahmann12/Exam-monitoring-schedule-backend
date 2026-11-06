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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Invitation and password-reset emails.
 *
 * <p>Sent in the background: the request returns at once, and "forgot password" takes the same time
 * whether or not the email belongs to an account. Without email configured, nothing is sent; in
 * local development ({@code app.account.log-links=true}) the link is written to the log instead.
 */
@Service
public class AccountEmailService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountEmailService.class);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final MailService mailService;
	/** Two background senders are plenty for account emails. */
	private final ExecutorService taskExecutor = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "account-mail");
		thread.setDaemon(true);
		return thread;
	});
	private final AccountLinkProperties properties;
	private final SettingsRepository settingsRepository;

	public AccountEmailService(MailService mailService, AccountLinkProperties properties, SettingsRepository settingsRepository) {
		this.mailService = mailService;
		this.properties = properties;
		this.settingsRepository = settingsRepository;
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
		return send(user.getEmail(), subject, html, "invitation", link.link());
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
		return send(user.getEmail(), subject, html, "password reset", link.link());
	}

	private boolean send(String to, String subject, String html, String kind, String link) {
		if (!mailService.isConfigured()) {
			if (properties.logLinks()) {
				LOGGER.warn("[MAIL-DEV] Email not configured. {} link for {}: {}", kind, to, link);
			} else {
				LOGGER.warn("[MAIL] Email not configured — could not send the {} email to {}.", kind, to);
			}
			return false;
		}
		taskExecutor.execute(() -> {
			try {
				mailService.sendHtml(to, subject, html);
				LOGGER.info("[MAIL] {} email sent to {}.", kind, to);
			} catch (RuntimeException ex) {
				// Never log the link itself here: production logs must not contain working links.
				LOGGER.error("[MAIL] Could not send the {} email to {}: {}", kind, to, ex.getMessage());
			}
		});
		return true;
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
