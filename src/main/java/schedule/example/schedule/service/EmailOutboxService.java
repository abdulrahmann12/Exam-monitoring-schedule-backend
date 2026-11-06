package schedule.example.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.EmailMessage;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.repository.EmailMessageRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The email outbox: emails are stored first, then sent by a background worker with retries.
 *
 * <ul>
 *   <li>Temporary problems (provider down, timeout, rate limit): retried after 1, 5, 15 and 60 minutes.</li>
 *   <li>Permanent problems (invalid address, rejected by the provider): failed at once, with the reason.</li>
 *   <li>Several workers or server instances never send the same email twice: each email is "claimed"
 *       with a conditional update first; a worker that crashed releases it after 10 minutes.</li>
 *   <li>Sent emails keep no content (the body and PDF are removed); the log entries are kept 90 days.</li>
 * </ul>
 */
@Service
public class EmailOutboxService {

	private static final Logger LOGGER = LoggerFactory.getLogger(EmailOutboxService.class);
	static final int MAX_ATTEMPTS = 5;
	private static final Duration[] BACKOFF = {
		Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)
	};
	private static final Duration STALE_CLAIM = Duration.ofMinutes(10);
	private static final int BATCH_SIZE = 20;
	private static final List<String> ON_ITS_WAY_OR_SENT = List.of(EmailMessage.QUEUED, EmailMessage.SENDING, EmailMessage.SENT);

	private final EmailMessageRepository repository;
	private final MailService mailService;
	private final TransactionTemplate transaction;
	private final boolean workerEnabled;
	private final int retentionDays;
	private volatile boolean warnedNotConfigured;

	public EmailOutboxService(
		EmailMessageRepository repository,
		MailService mailService,
		PlatformTransactionManager transactionManager,
		@Value("${app.mail.outbox.worker-enabled:true}") boolean workerEnabled,
		@Value("${app.mail.outbox.retention-days:90}") int retentionDays
	) {
		this.repository = repository;
		this.mailService = mailService;
		this.transaction = new TransactionTemplate(transactionManager);
		this.workerEnabled = workerEnabled;
		this.retentionDays = retentionDays;
	}

	// ─────────────────────────── Queueing schedule emails ───────────────────────────

	/** True when exactly this content was already sent (or is on its way) to this address. */
	@Transactional(readOnly = true)
	public boolean alreadySent(String dedupeKey, String contentHash, String recipient) {
		List<Object[]> latest = repository.latestForKey(dedupeKey, ON_ITS_WAY_OR_SENT, PageRequest.of(0, 1));
		if (latest.isEmpty()) {
			return false;
		}
		Object[] row = latest.get(0);
		return contentHash.equals(row[1]) && recipient.equalsIgnoreCase(String.valueOf(row[2]));
	}

	public record Outgoing(String dedupeKey, String contentHash, String recipient, String recipientName, String subject,
		String htmlBody, String attachmentName, byte[] attachment) {
	}

	/** Queues one schedule email; an older version still waiting for the same person is cancelled. */
	@Transactional
	public void queue(Outgoing email, UUID universityId, UUID batchId, String createdBy) {
		repository.cancelQueuedForKey(email.dedupeKey());
		EmailMessage message = new EmailMessage();
		message.setUniversityId(universityId);
		message.setBatchId(batchId);
		message.setKind(EmailMessage.KIND_SCHEDULE);
		message.setRecipient(email.recipient());
		message.setRecipientName(truncate(email.recipientName(), 160));
		message.setSubject(truncate(email.subject(), 300));
		message.setHtmlBody(email.htmlBody());
		message.setAttachmentName(truncate(email.attachmentName(), 200));
		message.setAttachment(email.attachment());
		message.setDedupeKey(email.dedupeKey());
		message.setContentHash(email.contentHash());
		message.setStatus(EmailMessage.QUEUED);
		message.setAttempts(0);
		message.setNextAttemptAt(Instant.now());
		message.setCreatedBy(createdBy);
		message.setCreatedAt(Instant.now());
		repository.save(message);
	}

	// ─────────────────────────── Account emails (content never stored) ───────────────────────────

	@Transactional
	public UUID recordAccountEmail(String kind, UUID universityId, String recipient, String recipientName, String subject) {
		EmailMessage message = new EmailMessage();
		message.setUniversityId(universityId);
		message.setKind(kind);
		message.setRecipient(recipient);
		message.setRecipientName(truncate(recipientName, 160));
		message.setSubject(truncate(subject, 300));
		message.setStatus(EmailMessage.SENDING);
		message.setAttempts(0);
		message.setClaimedAt(Instant.now());
		message.setCreatedAt(Instant.now());
		return repository.save(message).getId();
	}

	@Transactional
	public void recordAttempt(UUID id, boolean sent, String providerMessageId, String error, boolean finalAttempt) {
		repository.findById(id).ifPresent(message -> {
			message.setAttempts(message.getAttempts() + 1);
			if (sent) {
				message.setStatus(EmailMessage.SENT);
				message.setSentAt(Instant.now());
				message.setProviderMessageId(truncate(providerMessageId, 200));
				message.setLastError(null);
			} else {
				message.setStatus(finalAttempt ? EmailMessage.FAILED : EmailMessage.SENDING);
				message.setLastError(truncate(error, 500));
			}
			repository.save(message);
		});
	}

	// ─────────────────────────── Worker ───────────────────────────

	@Scheduled(fixedDelayString = "${app.mail.outbox.poll-ms:5000}", initialDelayString = "${app.mail.outbox.initial-delay-ms:15000}")
	public void scheduledRun() {
		if (workerEnabled) {
			processDue();
		}
	}

	/** Sends what is due; returns how many emails were handled. */
	public int processDue() {
		if (!mailService.isConfigured()) {
			if (!warnedNotConfigured) {
				LOGGER.warn("[MAIL] Email is not set up (BREVO_API_KEY / BREVO_SENDER_EMAIL): queued emails wait until it is.");
				warnedNotConfigured = true;
			}
			return 0;
		}
		Instant now = Instant.now();
		List<UUID> due = transaction.execute(status -> repository.findDue(now, now.minus(STALE_CLAIM), PageRequest.of(0, BATCH_SIZE)));
		int handled = 0;
		for (UUID id : due == null ? List.<UUID>of() : due) {
			Instant claimTime = Instant.now();
			Integer claimed = transaction.execute(status -> repository.claim(id, claimTime, claimTime.minus(STALE_CLAIM)));
			if (claimed == null || claimed == 0) {
				continue; // someone else took it
			}
			sendOne(id);
			handled++;
		}
		return handled;
	}

	private void sendOne(UUID id) {
		EmailMessage message = transaction.execute(status -> repository.findById(id).map(found -> {
			// Touch the lazy content inside the transaction.
			found.getHtmlBody();
			found.getAttachment();
			return found;
		}).orElse(null));
		if (message == null) {
			return;
		}
		String providerId = null;
		MailDeliveryException failure = null;
		try {
			providerId = mailService.sendHtmlWithPdf(message.getRecipient(), message.getSubject(), message.getHtmlBody(),
				message.getAttachmentName(), message.getAttachment());
		} catch (MailDeliveryException ex) {
			failure = ex;
		} catch (RuntimeException ex) {
			failure = new MailDeliveryException(ex.getMessage(), true, ex);
		}
		String messageId = providerId;
		MailDeliveryException problem = failure;
		transaction.executeWithoutResult(status -> repository.findById(id).ifPresent(row -> {
			int attempts = row.getAttempts() + 1;
			row.setAttempts(attempts);
			row.setClaimedAt(null);
			if (problem == null) {
				row.setStatus(EmailMessage.SENT);
				row.setSentAt(Instant.now());
				row.setProviderMessageId(truncate(messageId, 200));
				row.setLastError(null);
				row.setHtmlBody(null); // sent: no reason to keep the content
				row.setAttachment(null);
			} else if (problem.retryable() && attempts < MAX_ATTEMPTS) {
				row.setStatus(EmailMessage.QUEUED);
				row.setNextAttemptAt(Instant.now().plus(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)]));
				row.setLastError(truncate("Attempt " + attempts + " failed, will retry: " + problem.getMessage(), 500));
			} else {
				row.setStatus(EmailMessage.FAILED);
				row.setLastError(truncate(problem.getMessage(), 500));
				LOGGER.warn("[MAIL] Email to {} failed for good after {} attempt(s): {}", row.getRecipient(), attempts, problem.getMessage());
			}
			repository.save(row);
		}));
	}

	@Scheduled(cron = "0 41 3 * * *")
	@Transactional
	public void applyRetention() {
		if (retentionDays > 0) {
			int deleted = repository.deleteOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
			if (deleted > 0) {
				LOGGER.info("[MAIL] Removed {} delivery log entries older than {} days.", deleted, retentionDays);
			}
		}
	}

	// ─────────────────────────── Delivery log ───────────────────────────

	@Transactional(readOnly = true)
	public Page<EmailMessageRepository.Summary> page(UUID universityId, String status, Pageable pageable) {
		return status == null || status.isBlank()
			? repository.findByUniversityIdOrderByCreatedAtDesc(universityId, pageable)
			: repository.findByUniversityIdAndStatusOrderByCreatedAtDesc(universityId, status.trim().toUpperCase(java.util.Locale.ROOT), pageable);
	}

	@Transactional(readOnly = true)
	public Map<String, Long> counts(UUID universityId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String status : List.of(EmailMessage.QUEUED, EmailMessage.SENDING, EmailMessage.SENT, EmailMessage.FAILED)) {
			counts.put(status, 0L);
		}
		for (Object[] row : repository.countByStatus(universityId)) {
			counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
		}
		return counts;
	}

	/** Sends a failed schedule email again (account emails cannot be retried: their links are not stored). */
	@Transactional
	public void retry(UUID universityId, UUID id) {
		EmailMessage message = repository.findByIdAndUniversityId(id, universityId)
			.orElseThrow(() -> new NotFoundException(Messages.EMAIL_NOT_FOUND));
		if (!EmailMessage.FAILED.equals(message.getStatus())) {
			throw new BusinessRuleViolationException(Messages.EMAIL_RETRY_ONLY_FAILED);
		}
		if (!EmailMessage.KIND_SCHEDULE.equals(message.getKind()) || message.getHtmlBody() == null) {
			throw new BusinessRuleViolationException(Messages.EMAIL_RETRY_ACCOUNT);
		}
		message.setStatus(EmailMessage.QUEUED);
		message.setAttempts(0);
		message.setNextAttemptAt(Instant.now());
		message.setLastError(null);
		repository.save(message);
	}

	@Transactional
	public int retryAllFailed(UUID universityId) {
		return repository.retryAllFailed(universityId, Instant.now());
	}

	static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max - 3) + "...";
	}
}
