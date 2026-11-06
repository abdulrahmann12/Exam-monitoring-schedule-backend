package schedule.example.schedule.owner;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Requests and responses of the owner panel. */
public final class OwnerDtos {

	private OwnerDtos() {
	}

	/** A new university and its first administrator, who receives an invitation link. */
	public record CreateUniversityRequest(
		@NotBlank @Size(max = 160) String name,
		@NotBlank @Size(max = 60) String slug,
		@NotBlank @Email @Size(max = 160) String adminEmail,
		@Size(max = 120) String adminName
	) {
	}

	/** Rename and/or switch a university off or on. */
	public record UpdateUniversityRequest(
		@NotBlank @Size(max = 160) String name,
		@NotNull Boolean active
	) {
	}

	/** Another administrator for a university, or a fresh link for one who never signed in. */
	public record AddAdminRequest(
		@NotBlank @Email @Size(max = 160) String email,
		@Size(max = 120) String displayName
	) {
	}

	public record AdminInfo(UUID id, String email, String displayName, boolean enabled, boolean signedIn, boolean invitationPending) {
	}

	/** One university with its usage. Counts only, never the university's data itself. */
	public record UniversitySummary(
		UUID id,
		String name,
		String slug,
		boolean active,
		Instant createdAt,
		long accounts,
		long people,
		long rooms,
		long scheduledExamRooms,
		Instant lastSignInAt,
		Instant lastActivityAt,
		List<AdminInfo> admins
	) {
	}

	public record PlatformTotals(long universities, long activeUniversities, long accounts, long people, long scheduledExamRooms) {
	}

	public record Overview(PlatformTotals totals, List<UniversitySummary> universities) {
	}

	/**
	 * @param inviteLink one-time link for the administrator (copy it when email is not configured)
	 */
	public record AdminInvitation(UniversitySummary university, String adminEmail, String inviteLink, Instant inviteExpiresAt, boolean emailSent) {
	}
}
