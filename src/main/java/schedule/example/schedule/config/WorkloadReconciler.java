package schedule.example.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes {@code people.total_assignments} from the real assignment rows at startup.
 *
 * <p>The column is a denormalised display counter maintained by deltas. Any past bug, manual DB
 * edit or failed request could leave it wrong forever; this makes it self-healing. Scheduling
 * fairness does not depend on it (see {@code WorkloadTracker}).
 */
@Component
@Order(100)
public class WorkloadReconciler implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(WorkloadReconciler.class);

	private final JdbcTemplate jdbcTemplate;

	public WorkloadReconciler(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		int updated = jdbcTemplate.update("""
			UPDATE people p SET total_assignments =
			    (SELECT COUNT(*) FROM room_assignments ra WHERE ra.chief_invigilator_id = p.id)
			  + (SELECT COUNT(*) FROM invigilator_assignments ia WHERE ia.invigilator_id = p.id)
			""");
		LOGGER.info("[WORKLOAD] Reconciled total_assignments for {} person row(s).", updated);
	}
}
