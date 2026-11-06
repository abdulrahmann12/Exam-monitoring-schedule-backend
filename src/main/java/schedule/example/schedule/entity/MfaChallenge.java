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
 * "Password was right, now the code": created at sign-in for an account with two-step sign-in.
 * The browser holds the random token in a short-lived HttpOnly cookie; only its hash is stored.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "mfa_challenges", indexes = {
	@Index(name = "ux_mfa_challenges_token", columnList = "token_hash", unique = true),
	@Index(name = "idx_mfa_challenges_user", columnList = "user_id")
})
public class MfaChallenge {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "failed_attempts", nullable = false)
	private int failedAttempts;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
