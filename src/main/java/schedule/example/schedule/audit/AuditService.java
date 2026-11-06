package schedule.example.schedule.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import schedule.example.schedule.entity.AuditEvent;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.AuditEventRepository;
import schedule.example.schedule.tenant.TenantContext;
import schedule.example.schedule.security.AppUserPrincipal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Writes the activity log.
 *
 * <ul>
 *   <li>Every entry is written in its <em>own</em> transaction, so a refused or failed request (wrong
 *       password, access denied) is still recorded even though the request itself rolls back.</li>
 *   <li>{@link #recordAfterCommit} is for changes made inside a transaction: the entry appears only if
 *       the change was really saved.</li>
 *   <li>Logging never breaks the user's request: a failure to write the log is reported in the server
 *       log instead.</li>
 * </ul>
 */
@Service
public class AuditService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

	private final AuditEventRepository repository;
	private final AdminUserRepository users;
	private final TransactionTemplate ownTransaction;
	private final int retentionDays;

	public AuditService(
		AuditEventRepository repository,
		AdminUserRepository users,
		PlatformTransactionManager transactionManager,
		@Value("${app.audit.retention-days:365}") int retentionDays
	) {
		this.repository = repository;
		this.users = users;
		this.ownTransaction = new TransactionTemplate(transactionManager);
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.retentionDays = retentionDays;
	}

	/** Records now, in a separate transaction. */
	public void record(AuditEntry entry) {
		AuditEvent event = toEvent(entry); // read the security context on the caller's thread
		try {
			ownTransaction.executeWithoutResult(status -> repository.save(event));
		} catch (RuntimeException ex) {
			LOGGER.error("[AUDIT] Could not record {} '{}': {}", entry.action(), entry.summary(), ex.getMessage());
		}
	}

	/** Records once the surrounding transaction has committed (nothing if it rolls back). */
	public void recordAfterCommit(AuditEntry entry) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			record(entry);
			return;
		}
		AuditEvent event = toEvent(entry);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					ownTransaction.executeWithoutResult(status -> repository.save(event));
				} catch (RuntimeException ex) {
					LOGGER.error("[AUDIT] Could not record {} '{}': {}", entry.action(), entry.summary(), ex.getMessage());
				}
			}
		});
	}

	/** Nightly: entries older than the retention period are removed (0 = keep forever). */
	@Scheduled(cron = "0 37 3 * * *")
	public void applyRetention() {
		if (retentionDays <= 0) {
			return;
		}
		Integer deleted = ownTransaction.execute(status -> repository.deleteOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS)));
		if (deleted != null && deleted > 0) {
			LOGGER.info("[AUDIT] Removed {} activity log entries older than {} days.", deleted, retentionDays);
		}
	}

	private AuditEvent toEvent(AuditEntry entry) {
		AuditEvent event = new AuditEvent();
		event.setOccurredAt(Instant.now());
		event.setAction(truncate(entry.action(), 60));
		event.setCategory(entry.category().name());
		event.setTargetType(truncate(entry.targetType(), 40));
		event.setTargetId(truncate(entry.targetId(), 64));
		event.setSummary(truncate(entry.summary() == null ? entry.action() : entry.summary(), 500));
		event.setOutcome((entry.outcome() == null ? AuditEntry.Outcome.SUCCESS : entry.outcome()).name());

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
			event.setActorId(principal.getId());
			event.setActorEmail(principal.getUsername());
		}
		if (entry.actorEmail() != null && event.getActorEmail() == null) {
			event.setActorEmail(truncate(entry.actorEmail().trim().toLowerCase(), 160));
		}
		// The signed-in user's university; for events without one (failed sign-in, forgot
		// password) the university of the account concerned. Unknown → platform-level (null).
		event.setUniversityId(TenantContext.current().orElseGet(() -> event.getActorEmail() == null ? null
			: users.findByEmailIgnoreCase(event.getActorEmail()).map(AdminUser::getUniversityId).orElse(null)));
		HttpServletRequest request = currentRequest();
		if (request != null) {
			event.setIpAddress(truncate(request.getRemoteAddr(), 64));
		}
		return event;
	}

	private static HttpServletRequest currentRequest() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
	}

	static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}
}
