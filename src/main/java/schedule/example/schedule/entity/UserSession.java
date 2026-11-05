package schedule.example.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One signed-in device. The browser holds the refresh token in an HttpOnly cookie; the database
 * only keeps its SHA-256 hash, so a database leak does not leak usable tokens.
 *
 * <p>Getters/setters only (no Lombok {@code @Data}): entity equality stays identity-based.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "user_sessions",
	indexes = {
		@Index(name = "ux_user_sessions_refresh_hash", columnList = "refresh_token_hash", unique = true),
		@Index(name = "idx_user_sessions_previous_hash", columnList = "previous_token_hash"),
		@Index(name = "idx_user_sessions_user_id", columnList = "user_id"),
		@Index(name = "idx_user_sessions_expires_at", columnList = "expires_at")
	}
)
public class UserSession {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "refresh_token_hash", nullable = false, length = 64)
	private String refreshTokenHash;

	/** The token that was replaced by the last rotation — presenting it again means it was copied. */
	@Column(name = "previous_token_hash", length = 64)
	private String previousTokenHash;

	/** The user's token version when the session was created; a later bump ends the session. */
	@Column(name = "token_version", nullable = false)
	private int tokenVersion;

	@Column(name = "rotated_at")
	private Instant rotatedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_used_at", nullable = false)
	private Instant lastUsedAt;

	/** Absolute end of the session; refreshing does not extend it. */
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "user_agent", length = 255)
	private String userAgent;

	@Column(name = "ip_address", length = 64)
	private String ipAddress;

	public boolean isActive(Instant now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}
}
