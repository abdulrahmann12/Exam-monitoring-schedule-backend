package schedule.example.schedule.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A brand-new customer database: every Flyway script runs from scratch and the result must match
 * the JPA entities exactly ({@code ddl-auto=validate}), otherwise the context fails to start.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = MySqlMigrationSupport.ENV_URL_BASE, matches = ".+")
class MySqlFreshInstallIT {

	private static final String SCHEMA = "uniguard_fresh_it";

	@DynamicPropertySource
	static void mysql(DynamicPropertyRegistry registry) throws Exception {
		try (var connection = DriverManager.getConnection(
				MySqlMigrationSupport.url(SCHEMA), MySqlMigrationSupport.user(), MySqlMigrationSupport.password());
			 var statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
			statement.execute("CREATE DATABASE " + SCHEMA);
		}
		MySqlMigrationSupport.register(registry, SCHEMA);
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void allMigrationsApplyAndMatchEntities() {
		Integer applied = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(2);
	}
}
