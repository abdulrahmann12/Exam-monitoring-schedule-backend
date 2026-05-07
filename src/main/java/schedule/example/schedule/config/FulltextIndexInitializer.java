package schedule.example.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates MySQL FULLTEXT indexes on startup if they do not already exist.
 *
 * <p>FULLTEXT indexes support {@code MATCH(...) AGAINST(...)} queries which provide
 * better full-text search than leading-wildcard LIKE ('%term%'), which cannot use B-tree
 * indexes and causes full table scans. These indexes are created idempotently — already-
 * existing indexes are silently skipped.
 *
 * <p>The initializer only runs when the configured datasource URL contains "mysql".
 * On H2 or PostgreSQL test environments the component initializes but skips index creation
 * (those engines use different mechanisms for full-text search).
 *
 * <p><strong>Current usage:</strong> The active search queries use prefix LIKE on the
 * {@code normalized_name} / {@code department} B-tree indexes which are already fast.
 * These FULLTEXT indexes are created proactively for future use if richer text search
 * (e.g. tokenized word-boundary matching) is desired without requiring schema migration.
 *
 * <p>Created indexes:
 * <ul>
 *   <li>{@code idx_people_name_ft} — FULLTEXT on {@code people.name}</li>
 *   <li>{@code idx_people_dept_ft} — FULLTEXT on {@code people.department}</li>
 *   <li>{@code idx_rooms_name_ft}  — FULLTEXT on {@code rooms.name}</li>
 * </ul>
 */
@Component
public class FulltextIndexInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FulltextIndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final Environment  environment;

    public FulltextIndexInitializer(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment  = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createFulltextIndexes() {
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        if (!datasourceUrl.contains("mysql")) {
            LOGGER.debug("Non-MySQL datasource detected — skipping FULLTEXT index creation.");
            return;
        }

        createFulltextIndexIfMissing("people", "idx_people_name_ft", "FULLTEXT (name)");
        createFulltextIndexIfMissing("people", "idx_people_dept_ft", "FULLTEXT (department)");
        createFulltextIndexIfMissing("rooms",  "idx_rooms_name_ft",  "FULLTEXT (name)");
    }

    /**
     * Creates a FULLTEXT index on {@code table} if {@code indexName} does not already exist.
     * Uses {@code INFORMATION_SCHEMA.STATISTICS} for an idempotent check — safe to call on
     * every startup.
     *
     * @param table      unqualified table name (e.g. "people")
     * @param indexName  desired index name (e.g. "idx_people_name_ft")
     * @param definition index definition after ADD (e.g. "FULLTEXT (name)")
     */
    private void createFulltextIndexIfMissing(String table, String indexName, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class,
                table,
                indexName
            );

            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD " + definition);
                LOGGER.info("Created FULLTEXT index '{}' on table '{}'.", indexName, table);
            } else {
                LOGGER.debug("FULLTEXT index '{}' on table '{}' already exists — skipping.", indexName, table);
            }
        } catch (Exception ex) {
            // Non-fatal: log a warning and continue startup. The application functions correctly
            // without FULLTEXT indexes — it falls back to prefix LIKE on B-tree indexes.
            LOGGER.warn(
                "Could not create FULLTEXT index '{}' on '{}': {}. " +
                "Search will fall back to prefix LIKE on B-tree indexes.",
                indexName, table, ex.getMessage()
            );
        }
    }
}

