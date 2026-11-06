package com.uniguard.schedule.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upgrade path of every database that existed before Flyway: tables created by Hibernate's
 * {@code ddl-auto=update} (= the V1 baseline) and <em>no</em> {@code flyway_schema_history}.
 * On startup Flyway must baseline at V1, apply V2+, and the entities must validate.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = MySqlMigrationSupport.ENV_URL_BASE, matches = ".+")
class MySqlUpgradeFromHibernateIT {

	private static final String SCHEMA = "uniguard_upgrade_it";

	@DynamicPropertySource
	static void legacyDatabase(DynamicPropertyRegistry registry) throws Exception {
		String url = MySqlMigrationSupport.url(SCHEMA);
		try (var connection = DriverManager.getConnection(url, MySqlMigrationSupport.user(), MySqlMigrationSupport.password());
			 var statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
			statement.execute("CREATE DATABASE " + SCHEMA);
		}
		// Recreate the pre-Flyway state: V1 tables present, no migration history.
		Flyway.configure()
			.dataSource(url, MySqlMigrationSupport.user(), MySqlMigrationSupport.password())
			.locations("classpath:db/migration")
			.target("1")
			.load()
			.migrate();
		try (var connection = DriverManager.getConnection(url, MySqlMigrationSupport.user(), MySqlMigrationSupport.password());
			 var statement = connection.createStatement()) {
			statement.execute("DROP TABLE flyway_schema_history");
		}
		MySqlMigrationSupport.register(registry, SCHEMA);
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void legacySchemaIsBaselinedAndUpgraded() {
		List<String> types = jdbcTemplate.queryForList(
			"SELECT type FROM flyway_schema_history ORDER BY installed_rank", String.class);
		assertThat(types.getFirst()).isEqualTo("BASELINE");
		Integer versionColumns = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.columns
			WHERE table_schema = DATABASE() AND column_name = 'version'
			  AND table_name IN ('people', 'room_assignments', 'time_slots')
			""", Integer.class);
		assertThat(versionColumns).isEqualTo(3);
	}
}
