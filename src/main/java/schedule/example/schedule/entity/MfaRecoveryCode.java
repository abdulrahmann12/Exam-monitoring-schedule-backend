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

/** A one-time recovery code for two-step sign-in (when the phone is lost). Only its SHA-256 hash is stored. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "mfa_recovery_codes", indexes = @Index(name = "idx_mfa_recovery_codes_user", columnList = "user_id"))
public class MfaRecoveryCode {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "code_hash", nullable = false, length = 64)
	private String codeHash;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
