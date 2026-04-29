package schedule.example.schedule.dto.auth;

import java.time.Instant;

public record AuthResponse(
	String accessToken,
	String tokenType,
	Instant expiresAt,
	String email
) {
}