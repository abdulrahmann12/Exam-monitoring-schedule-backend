package com.uniguard.schedule.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login cookie settings.
 *
 * @param secure        send cookies over HTTPS only. Keep {@code true} everywhere except plain-http local development.
 * @param sameSite      {@code Lax} (default) when the frontend and API share a site (same domain or a proxy);
 *                      {@code None} only when they are on different sites — then {@code secure} must be true.
 * @param domain        optional cookie domain (e.g. {@code .university.edu}); empty = the API host only.
 * @param sessionDays   how long a sign-in lasts on a device before the password is needed again.
 * @param maxSessionsPerUser active devices per account; the oldest is signed out beyond this (0 = no limit).
 */
@ConfigurationProperties(prefix = "app.auth.cookie")
public record AuthCookieProperties(
	boolean secure,
	String sameSite,
	String domain,
	int sessionDays,
	int maxSessionsPerUser
) {

	public AuthCookieProperties {
		sameSite = sameSite == null || sameSite.isBlank() ? "Lax" : capitalize(sameSite.trim());
		if (!sameSite.equals("Lax") && !sameSite.equals("Strict") && !sameSite.equals("None")) {
			throw new IllegalStateException("app.auth.cookie.same-site must be Lax, Strict or None (was '" + sameSite + "').");
		}
		if (sameSite.equals("None") && !secure) {
			throw new IllegalStateException("app.auth.cookie.same-site=None requires app.auth.cookie.secure=true (browsers reject it otherwise).");
		}
		if (sessionDays <= 0) {
			sessionDays = 7;
		}
		domain = domain == null || domain.isBlank() ? null : domain.trim();
	}

	private static String capitalize(String value) {
		return value.substring(0, 1).toUpperCase() + value.substring(1).toLowerCase();
	}
}
