package schedule.example.schedule.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.entity.AuditEvent;
import schedule.example.schedule.repository.AuditEventRepository;
import schedule.example.schedule.tenant.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searching and exporting the activity log. */
@Service
public class AuditQueryService {

	/** Upper bound for one CSV export, so an export can never exhaust server memory. */
	public static final int MAX_EXPORT_ROWS = 10_000;

	private final AuditEventRepository repository;

	public AuditQueryService(AuditEventRepository repository) {
		this.repository = repository;
	}

	/** All filters are optional. Text filters match anywhere, ignoring case. */
	public record Filter(String category, String actor, String action, String outcome, Instant from, Instant to, String text) {
	}

	@Transactional(readOnly = true)
	public Page<AuditEvent> search(Filter filter, Pageable pageable) {
		return repository.findAll(specification(filter), pageable);
	}

	@Transactional(readOnly = true)
	public List<AuditEvent> export(Filter filter) {
		Pageable newestFirst = PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "occurredAt"));
		return repository.findAll(specification(filter), newestFirst).getContent();
	}

	private static Specification<AuditEvent> specification(Filter filter) {
		// The log table is not Hibernate-filtered (sign-in events are written before anyone is
		// signed in), so the university is always added here explicitly.
		java.util.UUID universityId = TenantContext.require();
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get("universityId"), universityId));
			if (hasText(filter.category())) {
				predicates.add(cb.equal(root.get("category"), filter.category().trim().toUpperCase(Locale.ROOT)));
			}
			if (hasText(filter.outcome())) {
				predicates.add(cb.equal(root.get("outcome"), filter.outcome().trim().toUpperCase(Locale.ROOT)));
			}
			if (hasText(filter.action())) {
				predicates.add(cb.equal(root.get("action"), filter.action().trim().toUpperCase(Locale.ROOT)));
			}
			if (hasText(filter.actor())) {
				predicates.add(cb.like(cb.lower(root.get("actorEmail")), contains(filter.actor()), '\\'));
			}
			if (hasText(filter.text())) {
				predicates.add(cb.like(cb.lower(root.get("summary")), contains(filter.text()), '\\'));
			}
			if (filter.from() != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
			}
			if (filter.to() != null) {
				predicates.add(cb.lessThan(root.get("occurredAt"), filter.to()));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** LIKE pattern for "contains"; the user's own % and _ are matched literally. */
	private static String contains(String value) {
		String escaped = value.trim().toLowerCase(Locale.ROOT)
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
		return "%" + escaped + "%";
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
