package schedule.example.schedule.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One email in the outbox. Schedule emails are stored with their content and sent by a background
 * worker with retries. Account emails (invitation, password reset) contain one-time links, so their
 * content is never stored: the row only records the delivery.
 *
 * <p>Not filtered by Hibernate's university filter (the worker runs without a user); every query
 * for a page adds the university explicitly.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "email_outbox", indexes = {
	@Index(name = "idx_email_outbox_due", columnList = "status, next_attempt_at"),
	@Index(name = "idx_email_outbox_university", columnList = "university_id, created_at"),
	@Index(name = "idx_email_outbox_dedupe", columnList = "dedupe_key")
})
public class EmailMessage {

	public static final String QUEUED = "QUEUED";
	public static final String SENDING = "SENDING";
	public static final String SENT = "SENT";
	public static final String FAILED = "FAILED";

	/** 16 MB - 1: MySQL mediumtext / mediumblob. */
	public static final int MAX_CONTENT = 16_777_215;

	public static final String KIND_SCHEDULE = "SCHEDULE";
	public static final String KIND_INVITE = "INVITE";
	public static final String KIND_PASSWORD_RESET = "PASSWORD_RESET";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "university_id")
	private UUID universityId;

	/** All emails of one "send to staff" click. */
	@Column(name = "batch_id")
	private UUID batchId;

	@Column(nullable = false, length = 30)
	private String kind;

	@Column(nullable = false, length = 160)
	private String recipient;

	@Column(name = "recipient_name", length = 160)
	private String recipientName;

	@Column(nullable = false, length = 300)
	private String subject;

	/** Content for the worker; cleared once the email is sent or has failed for good. Null for account emails. */
	@Lob
	@Basic(fetch = FetchType.LAZY)
	@Column(name = "html_body", length = MAX_CONTENT)  // MySQL mediumtext; without a length it would be tinytext (255 bytes)
	private String htmlBody;

	@Column(name = "attachment_name", length = 200)
	private String attachmentName;

	@Lob
	@Basic(fetch = FetchType.LAZY)
	@Column(name = "attachment", length = MAX_CONTENT)  // MySQL mediumblob (16 MB)
	private byte[] attachment;

	/** e.g. SCHEDULE:&lt;exam period&gt;:&lt;person&gt; — with {@link #contentHash}: "this exact schedule was already sent". */
	@Column(name = "dedupe_key", length = 200)
	private String dedupeKey;

	@Column(name = "content_hash", length = 64)
	private String contentHash;

	@Column(nullable = false, length = 12)
	private String status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	/** When a worker took it (a SENDING row older than a few minutes is taken again after a crash). */
	@Column(name = "claimed_at")
	private Instant claimedAt;

	/** Short reason, never the content. */
	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "provider_message_id", length = 200)
	private String providerMessageId;

	@Column(name = "created_by", length = 160)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "sent_at")
	private Instant sentAt;
}
