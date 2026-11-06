package com.uniguard.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.uniguard.schedule.entity.enums.AdminRole;

import java.time.Instant;
import java.util.UUID;

/**
 * User account (admin, scheduler, viewer or the demo account). Tracks creation/modification timestamps for security audit trails.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
	name = "admin_users",
	indexes = {
		@Index(name = "idx_admin_user_email", columnList = "email", unique = true)
	}
)
public class AdminUser {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/**
	 * The user's university. Not a Hibernate tenant filter on purpose: sign-in finds the account by
	 * email first, then everything the user does is limited to this university.
	 * Null is reserved for the platform owner (super-admin, a later step).
	 */
	@Column(name = "university_id")
	private UUID universityId;

	@Column(nullable = false, unique = true, length = 160)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	/** Name shown in the app (optional). */
	@Column(name = "display_name", length = 120)
	private String displayName;

	/** Disabled accounts cannot sign in and their existing tokens stop working immediately. */
	@Builder.Default
	@Column(nullable = false)
	private boolean enabled = true;

	/** Set when an administrator created the account or reset its password: the user must pick their own password first. */
	@Builder.Default
	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword = false;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AdminRole role = AdminRole.ADMIN;

	/** Consecutive wrong passwords since the last successful sign-in or lock. */
	@Builder.Default
	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts = 0;

	/** Sign-in is refused until this instant (null = not locked). */
	@Column(name = "locked_until")
	private Instant lockedUntil;

	/**
	 * Embedded in every JWT. Incrementing it (logout, password change) instantly invalidates
	 * all tokens issued before — the server-side "log out everywhere" switch.
	 */
	@Builder.Default
	@Column(name = "token_version", nullable = false)
	private int tokenVersion = 0;

	/** Two-step sign-in: authenticator secret, encrypted (see SecretCipher). Never sent to the browser. */
	@Column(name = "totp_secret", length = 255)
	private String totpSecret;

	/** Secret shown during set-up, active only after the first correct code. */
	@Column(name = "totp_pending_secret", length = 255)
	private String totpPendingSecret;

	@Builder.Default
	@Column(name = "totp_enabled", nullable = false)
	private boolean totpEnabled = false;

	@Column(name = "totp_enabled_at")
	private Instant totpEnabledAt;

	/** Time step of the last accepted code: a code can be used only once. */
	@Column(name = "totp_last_step")
	private Long totpLastStep;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(nullable = false)
	private Instant updatedAt;
}