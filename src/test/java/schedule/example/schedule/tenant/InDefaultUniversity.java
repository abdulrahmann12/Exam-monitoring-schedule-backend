package schedule.example.schedule.tenant;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension: runs each test method inside the first university, for tests that create data
 * directly through repositories (as system code would, inside TenantContext.runAs).
 * Not for isolation tests: an explicit university takes precedence over the signed-in user's.
 */
public class InDefaultUniversity implements BeforeEachCallback, AfterEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		TenantContext.enter(TenantContext.DEFAULT_UNIVERSITY_ID);
	}

	@Override
	public void afterEach(ExtensionContext context) {
		TenantContext.exit();
	}
}
