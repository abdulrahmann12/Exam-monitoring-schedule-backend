package schedule.example.schedule.tenant;

import org.hibernate.annotations.TenantId;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.integrator.spi.Integrator;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last line of defence: refuses to insert university data that does not belong to a real
 * university (saved by code running without a signed-in user and without {@code runAs}).
 * The database's foreign key would also reject it in production; this makes it fail the same
 * way everywhere, including tests and the local database.
 */
public class TenantInsertGuard implements Integrator, PreInsertEventListener {

	private static final String PROPERTY = "universityId";
	private static final Map<Class<?>, Boolean> TENANT_OWNED = new ConcurrentHashMap<>();

	@Override
	public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory) {
		sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class)
			.appendListeners(EventType.PRE_INSERT, this);
	}

	@Override
	public void disintegrate(SessionFactoryImplementor sessionFactory, org.hibernate.service.spi.SessionFactoryServiceRegistry serviceRegistry) {
		// nothing to release
	}

	@Override
	public boolean onPreInsert(PreInsertEvent event) {
		Object entity = event.getEntity();
		if (!TENANT_OWNED.computeIfAbsent(entity.getClass(), TenantInsertGuard::hasTenantId)) {
			return false;
		}
		String[] names = event.getPersister().getPropertyNames();
		for (int i = 0; i < names.length; i++) {
			if (PROPERTY.equals(names[i])) {
				// The field is filled in by Hibernate after this event, from the session's university;
				// so check the value that will actually be written.
				Object value = event.getState()[i] != null ? event.getState()[i] : event.getSession().getTenantIdentifierValue();
				if (value == null || UniversityTenantResolver.SYSTEM.equals(value) || UniversityTenantResolver.NO_UNIVERSITY.equals(value)) {
					throw new IllegalStateException("Refusing to save " + entity.getClass().getSimpleName()
						+ " without a university (no signed-in user and no TenantContext.runAs).");
				}
				return false;
			}
		}
		return false;
	}

	private static boolean hasTenantId(Class<?> type) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field field = c.getDeclaredField(PROPERTY);
				return field.isAnnotationPresent(TenantId.class) && field.getType() == UUID.class;
			} catch (NoSuchFieldException ignored) {
				// look in the superclass
			}
		}
		return false;
	}
}
