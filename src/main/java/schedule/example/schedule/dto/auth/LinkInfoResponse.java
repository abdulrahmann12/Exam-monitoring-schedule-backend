package schedule.example.schedule.dto.auth;

import schedule.example.schedule.entity.enums.AccountTokenPurpose;

import java.time.Instant;

public record LinkInfoResponse(AccountTokenPurpose purpose, String email, Instant expiresAt) {
}
