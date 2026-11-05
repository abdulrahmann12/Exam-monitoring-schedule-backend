package schedule.example.schedule.dto.user;

import java.time.Instant;

/**
 * Result of creating or inviting a user.
 *
 * @param inviteLink set when an invitation was created: the administrator can copy it (e.g. when
 *                   email is not configured). It works once, only for this user, until {@code inviteExpiresAt}.
 * @param emailSent  true when the invitation email is on its way
 */
public record InviteResponse(
	UserResponse user,
	String inviteLink,
	Instant inviteExpiresAt,
	boolean emailSent
) {
}
