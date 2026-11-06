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
 * A Microsoft or Google identity connected to an account. Sign-in finds the account by the
 * provider's stable subject, never by the email address (which can change or be reused).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_identities", indexes = {
	@Index(name = "ux_user_identities_provider_subject", columnList = "provider, subject", unique = true),
	@Index(name = "ux_user_identities_user_provider", columnList = "user_id, provider", unique = true)
})
public class UserIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	/** "google" or "microsoft". */
	@Column(nullable = false, length = 20)
	private String provider;

	/** Google: sub. Microsoft: tid/oid (stable across apps and email changes). */
	@Column(nullable = false, length = 255)
	private String subject;

	/** Shown to the user only ("Connected as ..."). */
	@Column(length = 160)
	private String email;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;
}
