package schedule.example.schedule.dto.auth;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in user. {@code roles} keeps the Spring authority names (e.g. {@code ROLE_ADMIN})
 * for older clients; {@code role} is the plain role name.
 */
public record MeResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        List<String> roles,
        boolean mustChangePassword,
        UUID universityId,
        String universityName
) {
}
