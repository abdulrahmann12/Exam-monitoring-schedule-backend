package schedule.example.schedule.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Requests and answers of two-step sign-in. */
public final class TwoFactorDtos {

	private TwoFactorDtos() {
	}

	/**
	 * @param available false when the server has no MFA_ENCRYPTION_KEY (two-step sign-in cannot be switched on)
	 */
	public record Status(boolean available, boolean enabled, Instant enabledAt, long recoveryCodesLeft) {
	}

	public record SetupRequest(@NotBlank(message = "Current password is required") @Size(max = 200) String currentPassword) {
	}

	/**
	 * @param secret     the key for typing into the app by hand (when the QR code cannot be scanned)
	 * @param otpauthUri the content of the QR code
	 */
	public record SetupResponse(String secret, String otpauthUri) {
	}

	/** A 6-digit code from the app, or (where allowed) a recovery code. */
	public record CodeRequest(@NotBlank(message = "Enter the code") @Size(max = 32) String code) {
	}

	public record PasswordAndCodeRequest(
		@NotBlank(message = "Current password is required") @Size(max = 200) String currentPassword,
		@NotBlank(message = "Enter the code") @Size(max = 32) String code
	) {
	}

	/** Shown once; only hashes are stored. */
	public record RecoveryCodes(List<String> codes) {
	}
}
