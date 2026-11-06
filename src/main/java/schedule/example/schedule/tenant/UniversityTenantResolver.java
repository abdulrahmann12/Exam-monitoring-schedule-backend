package schedule.example.schedule.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Tells Hibernate which university a database session belongs to. Every entity with a
 * {@code @TenantId} field is then filtered to that university in every query, and new rows get
 * that university automatically.
 *
 * <ul>
 *   <li>Explicit system scope ({@link TenantContext#runAs}) or a signed-in user's university → that university.</li>
 *   <li>A signed-in user <em>without</em> a university → {@link #NO_UNIVERSITY}: matches no data, and
 *       any insert fails on the foreign key. Fail closed, never "see everything".</li>
 *   <li>Nobody signed in (sign-in endpoints, start-up, background jobs) → {@link #SYSTEM}: no filter.
 *       Those code paths only touch account tables, or use {@code runAs} for university data; a row
 *       inserted with the SYSTEM value is rejected by the foreign key to {@code universities}.</li>
 * </ul>
 */
@Component
public class UniversityTenantResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

	/** "No filter" — only for code running without a signed-in user. Never a real university. */
	public static final UUID SYSTEM = new UUID(0L, 0L);

	/** Signed in, but with no university: sees nothing. Never a real university. */
	public static final UUID NO_UNIVERSITY = new UUID(-1L, -1L);

	@Override
	public UUID resolveCurrentTenantIdentifier() {
		if (TenantContext.hasExplicit() || TenantContext.hasSignedInUser()) {
			return TenantContext.current().orElse(NO_UNIVERSITY);
		}
		return SYSTEM;
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return false;
	}

	@Override
	public boolean isRoot(UUID tenantId) {
		return SYSTEM.equals(tenantId);
	}

	@Override
	public void customize(Map<String, Object> hibernateProperties) {
		hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
		hibernateProperties.put("hibernate.integrator_provider",
			(org.hibernate.jpa.boot.spi.IntegratorProvider) () -> java.util.List.of(new TenantInsertGuard()));
	}
}
