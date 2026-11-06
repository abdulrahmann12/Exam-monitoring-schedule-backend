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
 * One line of the activity log: who did what, to what, when, from where, and whether it worked.
 * Never contains passwords, tokens or request bodies.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "audit_events",
	indexes = {
		@Index(name = "idx_audit_events_occurred_at", columnList = "occurred_at"),
		@Index(name = "idx_audit_events_category_time", columnList = "category, occurred_at"),
		@Index(name = "idx_audit_events_actor_email", columnList = "actor_email"),
		@Index(name = "idx_audit_events_action", columnList = "action"),
		@Index(name = "idx_audit_events_university_time", columnList = "university_id, occurred_at")
	}
)
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/** University the event belongs to; administrators only see their own university's events. */
	@Column(name = "university_id")
	private UUID universityId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	/** Null for things done by nobody signed in (failed sign-in, forgot password) or by the system. */
	@Column(name = "actor_id")
	private UUID actorId;

	/** Email at the time of the event (kept even after the user is deleted). */
	@Column(name = "actor_email", length = 160)
	private String actorEmail;

	/** Machine-readable, e.g. ROOM_DELETED, LOGIN_FAILED. */
	@Column(nullable = false, length = 60)
	private String action;

	/** See {@code AuditCategory}. Stored as text so new categories need no schema change. */
	@Column(nullable = false, length = 30)
	private String category;

	@Column(name = "target_type", length = 40)
	private String targetType;

	@Column(name = "target_id", length = 64)
	private String targetId;

	/** Human-readable, e.g. "Deleted room 'Hall A'". */
	@Column(nullable = false, length = 500)
	private String summary;

	/** SUCCESS, FAILURE or DENIED. */
	@Column(nullable = false, length = 20)
	private String outcome;

	@Column(name = "ip_address", length = 64)
	private String ipAddress;
}
