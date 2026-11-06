package com.uniguard.schedule.dto.user;

import com.uniguard.schedule.entity.enums.AdminRole;

import java.time.Instant;
import java.util.UUID;

/** A user account as shown on the Users page. Never contains the password hash. */
public record UserResponse(
	UUID id,
	String email,
	String displayName,
	AdminRole role,
	boolean enabled,
	boolean locked,
	Instant lockedUntil,
	boolean mustChangePassword,
	boolean invitePending,
	Instant lastLoginAt,
	Instant createdAt,
	boolean twoFactorEnabled
) {
}
