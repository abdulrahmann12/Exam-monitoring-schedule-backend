package com.uniguard.schedule.tools;

import jakarta.persistence.Entity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Developer tool — prints the MySQL DDL Hibernate would generate for the current entities.
 *
 * <p>Use it when writing a new Flyway migration: generate the DDL, diff it against the
 * previous output, and hand-write the {@code ALTER} statements into {@code V<n>__*.sql}.
 *
 * <pre>
 * mvn test -Dtest=SchemaDdlGenerator -DgenerateDdl=true
 * # output: target/schema-mysql.sql
 * </pre>
 *
 * Skipped during normal test runs.
 */
@EnabledIfSystemProperty(named = "generateDdl", matches = "true")
class SchemaDdlGenerator {

	@Test
	void generateMysqlDdl() throws Exception {
		Path output = Path.of("target", "schema-mysql.sql");
		Files.createDirectories(output.getParent());
		Files.deleteIfExists(output);

		Map<String, Object> settings = new HashMap<>();
		settings.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
		settings.put("hibernate.boot.allow_jdbc_metadata_access", "false");
		settings.put("hibernate.format_sql", "true");
		settings.put("hibernate.hbm2ddl.delimiter", ";");
		settings.put("jakarta.persistence.schema-generation.database.action", "none");
		settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
		settings.put("jakarta.persistence.schema-generation.scripts.create-target", output.toString());

		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
			.applySettings(settings)
			.build();
		try {
			MetadataSources sources = new MetadataSources(registry);
			ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
			scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
			for (var candidate : scanner.findCandidateComponents("com.uniguard.schedule.entity")) {
				sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
			}

			// Same naming strategies Spring Boot applies at runtime.
			Metadata metadata = sources.getMetadataBuilder()
				.applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
				.applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
				.build();

			SchemaManagementToolCoordinator.process(metadata, registry, settings, null);
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
		System.out.println("DDL written to " + output.toAbsolutePath());
	}
}
