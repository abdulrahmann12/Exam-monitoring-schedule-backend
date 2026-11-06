package com.uniguard.schedule.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quick local check that every Flyway script runs (H2 in MySQL mode) and that V9 really makes
 * names unique per university. Hibernate's schema validation is skipped here: H2 reports UUID
 * columns differently from MySQL. The full check against real MySQL runs in CI ({@link MySqlFreshInstallIT}).
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:flyway-smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
	"spring.flyway.enabled=true",
	"spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
class FlywayOnH2SmokeTest {

	private static final String UNI_1 = "X'00000000000000000000000000000001'";
	private static final String UNI_2 = "X'00000000000000000000000000000099'";

	@Autowired private JdbcTemplate jdbc;

	@Test
	void allMigrationsApplyAndNamesAreUniquePerUniversity() {
		Integer applied = jdbc.queryForObject("select count(*) from \"flyway_schema_history\" where \"success\" = true", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(13);
		assertThat(jdbc.queryForObject("select slug from universities", String.class)).isEqualTo("default");

		jdbc.update("insert into universities (id, name, slug, active, created_at) values (" + UNI_2 + ", 'Second', 'second', true, now())");
		insertRoom("X'000000000000000000000000000000A1'", UNI_1);
		insertRoom("X'000000000000000000000000000000A2'", UNI_2); // same name, other university: fine
		assertThatThrownBy(() -> insertRoom("X'000000000000000000000000000000A3'", UNI_1))
			.isInstanceOf(DataIntegrityViolationException.class);
		// Rows must belong to a real university.
		assertThatThrownBy(() -> insertRoom("X'000000000000000000000000000000A4'", "X'00000000000000000000000000000000'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void onlyThePlatformOwnerHasNoUniversity() {
		insertAccount("X'000000000000000000000000000000B1'", "owner@smoke.test", "SUPER_ADMIN", "null");
		insertAccount("X'000000000000000000000000000000B2'", "admin@smoke.test", "ADMIN", UNI_1);
		// An owner inside a university, or a university account without one, is refused by the database itself.
		assertThatThrownBy(() -> insertAccount("X'000000000000000000000000000000B3'", "owner2@smoke.test", "SUPER_ADMIN", UNI_1))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAccount("X'000000000000000000000000000000B4'", "orphan@smoke.test", "ADMIN", "null"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertAccount(String id, String email, String role, String universityId) {
		jdbc.update("insert into admin_users (id, created_at, email, password_hash, role, updated_at, university_id) "
			+ "values (" + id + ", now(), '" + email + "', 'x', '" + role + "', now(), " + universityId + ")");
	}

	private void insertRoom(String id, String universityId) {
		jdbc.update("insert into rooms (id, active, capacity, created_at, min_invigilators, name, normalized_name, type, updated_at, hall_gender, university_id) "
			+ "values (" + id + ", true, 30, now(), 1, 'Hall A', 'hall a', 'SMALL', now(), 'MIXED', " + universityId + ")");
	}
}
