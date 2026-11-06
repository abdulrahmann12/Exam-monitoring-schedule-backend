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
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upgrade path of the live database: a schema created by the old Hibernate
 * {@code ddl-auto=update} (= the V1 baseline) <em>with data</em> and <em>no</em> {@code flyway_schema_history}.
 * On startup Flyway must baseline at V1, apply V2+ without losing a row, and the entities must validate.
 *
 * <p>The legacy data mirrors the production database (checked read-only on 2026-11-06, MariaDB 10.11):
 * an extra old table {@code person_schedule_exceptions}, extra nullable {@code start_date}/{@code end_date}
 * columns on {@code schedule_groups}, people without normalized names, an exam room without an exam period,
 * and the demo account. The settings row gets an unexpected id on purpose: V9 must still link it.
 * Runs in CI against MySQL 8 and MariaDB 10.11.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = MySqlMigrationSupport.ENV_URL_BASE, matches = ".+")
class MySqlUpgradeFromHibernateIT {

	private static final String SCHEMA = "uniguard_upgrade_it";
	private static final String UNI = "X'00000000000000000000000000000001'";

	@DynamicPropertySource
	static void legacyDatabase(DynamicPropertyRegistry registry) throws Exception {
		String url = MySqlMigrationSupport.url(SCHEMA);
		try (var connection = DriverManager.getConnection(MySqlMigrationSupport.url(""), MySqlMigrationSupport.user(), MySqlMigrationSupport.password());
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
			insertLegacyData(statement);
			// Production: old Hibernate added the same unique index at every start until admin_users had
			// MySQL's maximum of 64 indexes, so V9's new index failed with "Too many keys specified".
			for (int copy = 2; copy <= 63; copy++) {
				statement.execute("ALTER TABLE admin_users ADD UNIQUE KEY email_" + copy + " (email)");
			}
			statement.execute("ALTER TABLE people ADD UNIQUE KEY email_2 (email)");
		}
		MySqlMigrationSupport.register(registry, SCHEMA);
	}

	/** Data as the old version stored it, before any migration. */
	static void insertLegacyData(Statement s) throws Exception {
		// Leftovers that exist in production but in no migration.
		s.execute("ALTER TABLE schedule_groups ADD COLUMN start_date date");
		s.execute("ALTER TABLE schedule_groups ADD COLUMN end_date date");
		s.execute("""
			CREATE TABLE person_schedule_exceptions (
			  id binary(16) NOT NULL, created_at datetime(6) NOT NULL, end_date date NOT NULL, note varchar(500) DEFAULT NULL,
			  start_date date NOT NULL, updated_at datetime(6) NOT NULL, person_id binary(16) NOT NULL, PRIMARY KEY (id),
			  CONSTRAINT FKpcyrhx5fxc5fbe1h9l0i0ix01 FOREIGN KEY (person_id) REFERENCES people (id))""");
		s.execute("CREATE INDEX idx_pse_person_id ON person_schedule_exceptions (person_id)");
		s.execute("CREATE INDEX idx_pse_date_range ON person_schedule_exceptions (person_id, start_date, end_date)");

		s.execute("INSERT INTO admin_users (id, created_at, email, password_hash, role, updated_at) VALUES "
			+ "(X'000000000000000000000000000000A1', now(6), 'admin@legacy.test', '$2a$10$abcdefghijklmnopqrstuuJ8Q9f1O0r5m8F6x3b5c7d9e1f3a5b7c', 'ADMIN', now(6)),"
			+ "(X'000000000000000000000000000000A2', now(6), 'demo@uniguard.com', '$2a$10$abcdefghijklmnopqrstuuJ8Q9f1O0r5m8F6x3b5c7d9e1f3a5b7c', 'DEMO_ADMIN', now(6))");
		// An unexpected settings id: V9 must still turn this row into the first university's settings.
		s.execute("INSERT INTO settings (id, app_tagline, department, exam_period, logo_url, system_name, theme, university_name) VALUES "
			+ "(X'0000000000000000000000000000BEEF', 'Exams', 'Exam office', 'Fall 2026', NULL, 'Uni-Guard Schedules', 'LIGHT', 'Legacy University')");
		s.execute("INSERT INTO schedule_groups (id, active, archived, created_at, description, name, updated_at, start_date, end_date) VALUES "
			+ "(X'00000000000000000000000000000010', 1, 0, now(6), NULL, 'Fall 2026', now(6), '2027-01-10', '2027-01-20')");
		s.execute("INSERT INTO time_slots (id, active, created_at, end_time, label, sort_order, start_time, updated_at, schedule_group_id) VALUES "
			+ "(X'00000000000000000000000000000020', 1, now(6), '12:00:00', 'Morning', 1, '09:00:00', now(6), X'00000000000000000000000000000010')");
		s.execute("INSERT INTO rooms (id, active, capacity, created_at, min_invigilators, name, normalized_name, type, updated_at) VALUES "
			+ "(X'00000000000000000000000000000030', 1, 40, now(6), 1, 'Hall A', 'hall a', 'MEDIUM', now(6)),"
			+ "(X'00000000000000000000000000000031', 1, 80, now(6), 2, 'Hall B', NULL, 'LARGE', now(6))");
		s.execute("INSERT INTO people (id, active, created_at, department, email, max_parallel_rooms, name, normalized_name, role, total_assignments, updated_at) VALUES "
			+ "(X'00000000000000000000000000000040', 1, now(6), 'Physics', 'chief@legacy.test', 1, 'Dr. Chief', 'dr. chief', 'CHIEF_INVIGILATOR', 3, now(6)),"
			+ "(X'00000000000000000000000000000041', 1, now(6), 'Maths', 'inv@legacy.test', 1, 'Sara Inv', NULL, 'INVIGILATOR', 1, now(6)),"
			+ "(X'00000000000000000000000000000042', 0, now(6), 'Maths', NULL, 1, 'Old Person', NULL, 'INVIGILATOR', 0, now(6))");
		s.execute("INSERT INTO person_available_days (person_id, available_day) VALUES "
			+ "(X'00000000000000000000000000000040', 'SUN'), (X'00000000000000000000000000000040', 'MON'), (X'00000000000000000000000000000041', 'SUN')");
		s.execute("INSERT INTO person_schedule_exceptions (id, created_at, end_date, note, start_date, updated_at, person_id) VALUES "
			+ "(X'00000000000000000000000000000045', now(6), '2027-01-12', 'old leave', '2027-01-11', now(6), X'00000000000000000000000000000041')");
		s.execute("INSERT INTO room_assignments (id, created_at, exam_date, generation_version, is_locked, source, subject_code, subject_name, updated_at, chief_invigilator_id, room_id, schedule_group_id, time_slot_id) VALUES "
			+ "(X'00000000000000000000000000000050', now(6), '2027-01-10', 1, 0, 'GENERATED', 'MATH101', 'Calculus', now(6), X'00000000000000000000000000000040', X'00000000000000000000000000000030', X'00000000000000000000000000000010', X'00000000000000000000000000000020'),"
			+ "(X'00000000000000000000000000000051', now(6), '2027-01-11', 0, 1, 'MANUAL', NULL, NULL, now(6), NULL, X'00000000000000000000000000000031', NULL, X'00000000000000000000000000000020')");
		s.execute("INSERT INTO invigilator_assignments (id, created_at, position_index, required, invigilator_id, room_assignment_id) VALUES "
			+ "(X'00000000000000000000000000000060', now(6), 0, 1, X'00000000000000000000000000000041', X'00000000000000000000000000000050'),"
			+ "(X'00000000000000000000000000000061', now(6), 1, 1, NULL, X'00000000000000000000000000000050')");
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void legacySchemaWithDataIsBaselinedAndUpgradedWithoutLosingARow() {
		assertUpgraded(jdbcTemplate, "DATABASE()");
		// The duplicate copies were removed; the named unique index on email is kept.
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'admin_users' AND index_name LIKE 'email%'",
			Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'admin_users' AND index_name = 'idx_admin_user_email'",
			Integer.class)).isEqualTo(1);
	}

	/** Shared with the H2 version of this test. */
	static void assertUpgraded(JdbcTemplate jdbcTemplate, String currentSchemaFunction) {
		// Newer Flyway versions add a marker row (rank -1, type TABLE) before the real history.
		List<String> types = jdbcTemplate.queryForList("SELECT type FROM flyway_schema_history WHERE installed_rank > 0 ORDER BY installed_rank", String.class);
		assertThat(types.getFirst()).isEqualTo("BASELINE");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE script = 'V13__email_outbox.sql' AND success = 1", Integer.class))
			.isEqualTo(1);

		// Every row is still there.
		assertThat(count(jdbcTemplate, "people")).isEqualTo(3);
		assertThat(count(jdbcTemplate, "rooms")).isEqualTo(2);
		assertThat(count(jdbcTemplate, "room_assignments")).isEqualTo(2);
		assertThat(count(jdbcTemplate, "invigilator_assignments")).isEqualTo(2);
		assertThat(count(jdbcTemplate, "person_available_days")).isEqualTo(3);
		assertThat(count(jdbcTemplate, "person_schedule_exceptions")).isEqualTo(1); // old table left untouched

		// ...and belongs to the first university, which took its name from the old settings.
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM universities WHERE id = " + UNI, String.class)).isEqualTo("Legacy University");
		for (String table : List.of("people", "rooms", "schedule_groups", "time_slots", "room_assignments", "invigilator_assignments")) {
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE university_id <> " + UNI, Integer.class))
				.as(table).isZero();
		}
		// The settings row with the unexpected id became the first university's settings.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settings WHERE id = " + UNI + " AND university_id = " + UNI, Integer.class)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT university_name FROM settings WHERE id = " + UNI, String.class)).isEqualTo("Legacy University");

		// Accounts keep working: administrator and demo in the first university, the new owner without one.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_users WHERE email IN ('admin@legacy.test', 'demo@uniguard.com') AND university_id = " + UNI, Integer.class)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT role FROM admin_users WHERE email = 'admin@legacy.test'", String.class)).isEqualTo("ADMIN");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_users WHERE role = 'SUPER_ADMIN' AND university_id IS NULL", Integer.class)).isEqualTo(1);

		Integer versionColumns = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.columns
			WHERE LOWER(table_schema) = LOWER(%s) AND LOWER(column_name) = 'version'
			  AND LOWER(table_name) IN ('people', 'room_assignments', 'time_slots')
			""".formatted(currentSchemaFunction), Integer.class);
		assertThat(versionColumns).isEqualTo(3);
	}

	private static int count(JdbcTemplate jdbcTemplate, String table) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}
}
