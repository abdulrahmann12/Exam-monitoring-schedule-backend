package schedule.example.schedule.dto.auth;

import java.time.Instant;

/**
 * Answer to sign-in, refresh and password change. The tokens themselves travel only in HttpOnly
 * cookies and are deliberately not part of the body, so page scripts never see them.
 *
 * @param accessExpiresAt  when the current access cookie expires (the app renews it before or after)
 * @param sessionExpiresAt when this device must sign in with the password again
 */
public record SessionResponse(
	String email,
	Instant accessExpiresAt,
	Instant sessionExpiresAt
) {
}
