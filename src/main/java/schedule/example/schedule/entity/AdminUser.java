package schedule.example.schedule.entity;

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

import schedule.example.schedule.entity.enums.AdminRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin user entity. Tracks creation/modification timestamps for security audit trails.
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

	@Column(nullable = false, unique = true, length = 160)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

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

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(nullable = false)
	private Instant updatedAt;
}