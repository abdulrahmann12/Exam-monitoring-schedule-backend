package com.uniguard.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Settings for invitation and password-reset links.
 *
 * @param frontendBaseUrl where the app runs, e.g. {@code https://schedules.university.edu}. Links in
 *                        emails are always built from this value — never from request headers, which
 *                        an attacker could set to send victims to a look-alike site.
 * @param inviteHours     how long an invitation link works
 * @param resetMinutes    how long a password-reset link works
 * @param logLinks        write links to the log when email is not configured. Local development only:
 *                        anyone who can read the log could use them.
 */
@ConfigurationProperties(prefix = "app.account")
public record AccountLinkProperties(
	String frontendBaseUrl,
	int inviteHours,
	int resetMinutes,
	boolean logLinks
) {

	public AccountLinkProperties {
		if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
			frontendBaseUrl = "http://localhost:5173";
		}
		frontendBaseUrl = frontendBaseUrl.trim().replaceAll("/+$", "");
		URI uri = URI.create(frontendBaseUrl);
		if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
			throw new IllegalStateException("APP_FRONTEND_URL must be an http(s) address like https://schedules.example.edu (was '" + frontendBaseUrl + "').");
		}
		if (inviteHours <= 0) {
			inviteHours = 72;
		}
		if (resetMinutes <= 0) {
			resetMinutes = 30;
		}
	}

	/** The page that sets a password from a link. The secret goes after '#', so it is never sent to any server or logged. */
	public String passwordLink(String token) {
		return frontendBaseUrl + "/reset-password#token=" + token;
	}

	/** The page that confirms a new sign-in email. Same '#' rule as above. */
	public String emailChangeLink(String token) {
		return frontendBaseUrl + "/confirm-email#token=" + token;
	}
}
