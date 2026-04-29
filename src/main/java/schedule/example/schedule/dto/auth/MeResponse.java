package schedule.example.schedule.dto.auth;

import java.util.List;

public record MeResponse(
        String email,
        List<String> roles
) {
}
