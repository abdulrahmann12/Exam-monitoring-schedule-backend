package schedule.example.schedule.migration;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Shared setup for the MySQL migration checks. They run only when {@code MYSQL_IT_URL_BASE} is set
 * (CI provides a MySQL 8 service container), e.g.
 * <pre>
 * MYSQL_IT_URL_BASE=jdbc:mysql://localhost:3306/  MYSQL_IT_USER=root  MYSQL_IT_PASSWORD=root
 * mvn test -Dtest='MySql*IT'
 * </pre>
 */
final class MySqlMigrationSupport {

	static final String ENV_URL_BASE = "MYSQL_IT_URL_BASE";

	private MySqlMigrationSupport() {
	}

	static String url(String schema) {
		return System.getenv(ENV_URL_BASE) + schema + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
	}

	static String user() {
		return System.getenv().getOrDefault("MYSQL_IT_USER", "root");
	}

	static String password() {
		return System.getenv().getOrDefault("MYSQL_IT_PASSWORD", "root");
	}

	/** Production-like settings: Flyway owns the schema, Hibernate only validates it. */
	static void register(DynamicPropertyRegistry registry, String schema) {
		registry.add("spring.datasource.url", () -> url(schema));
		registry.add("spring.datasource.username", MySqlMigrationSupport::user);
		registry.add("spring.datasource.password", MySqlMigrationSupport::password);
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}
}
