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

/** One started Microsoft / Google sign-in. Single use, 10 minutes; the state itself is stored only as a hash. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sso_states", indexes = {
	@Index(name = "ux_sso_states_hash", columnList = "state_hash", unique = true),
	@Index(name = "idx_sso_states_expires", columnList = "expires_at")
})
public class SsoState {

	public static final String LOGIN = "LOGIN";
	public static final String LINK = "LINK";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "state_hash", nullable = false, length = 64)
	private String stateHash;

	@Column(nullable = false, length = 20)
	private String provider;

	/** LOGIN or LINK (connect to the signed-in account). */
	@Column(nullable = false, length = 10)
	private String purpose;

	/** For LINK: the account that started it. */
	@Column(name = "user_id")
	private UUID userId;

	@Column(nullable = false, length = 100)
	private String nonce;

	/** PKCE: proves the code is redeemed by whoever started the sign-in. */
	@Column(name = "code_verifier", nullable = false, length = 100)
	private String codeVerifier;

	/** Fixed app path to return to after linking ("/settings" or "/owner"); never taken from the request. */
	@Column(name = "return_path", length = 40)
	private String returnPath;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
