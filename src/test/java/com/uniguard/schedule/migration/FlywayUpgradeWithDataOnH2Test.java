package com.uniguard.schedule.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;

/**
 * Local (H2, MySQL mode) version of {@link MySqlUpgradeFromHibernateIT}: the same production-like legacy
 * data, upgraded by every migration, with the same checks. The real-database versions run in CI
 * (MySQL 8 and MariaDB 10.11); this one catches mistakes before pushing. Schema validation is skipped
 * because H2 reports UUID columns differently from MySQL.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=" + FlywayUpgradeWithDataOnH2Test.URL,
	"spring.flyway.enabled=true",
	"spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
class FlywayUpgradeWithDataOnH2Test {

	static final String URL = "jdbc:h2:mem:flyway-upgrade;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";

	@DynamicPropertySource
	static void legacyDatabase(DynamicPropertyRegistry registry) throws Exception {
		Flyway.configure().dataSource(URL, "sa", "test").locations("classpath:db/migration").target("1").load().migrate();
		try (var connection = DriverManager.getConnection(URL, "sa", "test"); var statement = connection.createStatement()) {
			statement.execute("DROP TABLE \"flyway_schema_history\"");
			MySqlUpgradeFromHibernateIT.insertLegacyData(statement);
		}
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void legacyDataSurvivesEveryMigration() {
		MySqlUpgradeFromHibernateIT.assertUpgraded(jdbcTemplate, "SCHEMA()");
	}
}
