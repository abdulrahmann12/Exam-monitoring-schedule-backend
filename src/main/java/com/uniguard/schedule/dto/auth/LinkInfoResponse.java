package com.uniguard.schedule.dto.auth;

import com.uniguard.schedule.entity.enums.AccountTokenPurpose;

import java.time.Instant;

public record LinkInfoResponse(AccountTokenPurpose purpose, String email, Instant expiresAt) {
}
