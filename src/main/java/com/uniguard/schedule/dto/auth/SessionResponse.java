package com.uniguard.schedule.dto.auth;

import java.time.Instant;

/**
 * Answer to sign-in, refresh and password change. The tokens themselves travel only in HttpOnly
 * cookies and are deliberately not part of the body, so page scripts never see them.
 *
 * @param accessExpiresAt  when the current access cookie expires (the app renews it before or after)
 * @param sessionExpiresAt when this device must sign in with the password again
 * @param twoFactorRequired true after a correct password for an account with two-step sign-in:
 *                          no session yet, the code from the app must be sent to /api/auth/2fa/verify
 */
public record SessionResponse(
	String email,
	Instant accessExpiresAt,
	Instant sessionExpiresAt,
	boolean twoFactorRequired
) {
	public SessionResponse(String email, Instant accessExpiresAt, Instant sessionExpiresAt) {
		this(email, accessExpiresAt, sessionExpiresAt, false);
	}

	public static SessionResponse twoFactorPending(String email) {
		return new SessionResponse(email, null, null, true);
	}
}
