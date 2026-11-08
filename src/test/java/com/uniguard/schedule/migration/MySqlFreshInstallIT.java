package com.uniguard.schedule.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.uniguard.schedule.dto.person.PersonRequest;
import com.uniguard.schedule.dto.room.RoomRequest;
import com.uniguard.schedule.entity.enums.HallGender;
import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.entity.enums.RoomType;
import com.uniguard.schedule.entity.enums.WeekDay;
import com.uniguard.schedule.owner.OwnerDtos.DeleteUniversityRequest;
import com.uniguard.schedule.owner.OwnerDtos.UpdateUniversityRequest;
import com.uniguard.schedule.owner.PlatformOwnerService;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.service.PeopleService;
import com.uniguard.schedule.service.RoomService;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.tenant.UniversityProvisioning;

import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

	@Autowired private UniversityProvisioning provisioning;
	@Autowired private PlatformOwnerService ownerService;
	@Autowired private RoomService roomService;
	@Autowired private PeopleService peopleService;
	@Autowired private AdminUserRepository users;

	/** The owner's "delete university" SQL against MySQL/MariaDB's real foreign keys (H2 tests have none). */
	@Test
	void ownerDeletesAUniversityWithItsDataOnMySql() {
		String slug = "it-" + UUID.randomUUID().toString().substring(0, 8);
		UUID id = provisioning.create("IT Delete " + slug, slug).getId();
		TenantContext.runAs(id, () -> {
			roomService.createRoom(new RoomRequest("IT Hall", 40, RoomType.SMALL, 1, HallGender.MIXED));
			peopleService.createPerson(new PersonRequest("Dr. IT", "Exams", null, PersonRole.INVIGILATOR,
				Set.of(WeekDay.SUN, WeekDay.MON), null, Set.of("CS101")));
		});
		ownerService.updateUniversity(id, new UpdateUniversityRequest("IT Delete " + slug, false));
		UUID owner = users.findByEmailIgnoreCase("owner@test.local").orElseThrow().getId();

		var deleted = ownerService.deleteUniversity(id, owner, new DeleteUniversityRequest("IT Delete " + slug, "owner-password-1"));

		assertThat(deleted.rooms()).isEqualTo(1);
		assertThat(deleted.people()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM universities WHERE slug = ?", Integer.class, slug)).isZero();
		for (String table : List.of("people", "rooms", "time_slots", "schedule_groups", "settings", "audit_events", "admin_users")) {
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table
				+ " WHERE university_id IS NOT NULL AND university_id NOT IN (SELECT id FROM universities)", Integer.class))
				.as(table).isZero();
		}
		for (String table : List.of("person_available_days", "person_subject_codes")) {
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE person_id NOT IN (SELECT id FROM people)", Integer.class))
				.as(table).isZero();
		}
	}

	@Test
	void allMigrationsApplyAndMatchEntities() {
		Integer applied = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(2);
	}
}
