package schedule.example.schedule.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import schedule.example.schedule.security.AppUserPrincipal;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Which university the current work belongs to.
 *
 * <ul>
 *   <li>In a web request: the signed-in user's university (from the security context).</li>
 *   <li>In system work (start-up, background jobs): set explicitly with {@link #callAs}/{@link #runAs}.</li>
 * </ul>
 *
 * <p>Important: Hibernate fixes the university when a database session opens, i.e. when a
 * transaction starts. {@code runAs} must therefore wrap the transaction, not run inside one.
 */
public final class TenantContext {

	/** The very first university (keeps the id of the original single settings row). */
	public static final UUID DEFAULT_UNIVERSITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final ThreadLocal<UUID> EXPLICIT = new ThreadLocal<>();

	private TenantContext() {
	}

	/** The university of the current work, if there is one. */
	public static Optional<UUID> current() {
		UUID explicit = EXPLICIT.get();
		if (explicit != null) {
			return Optional.of(explicit);
		}
		return signedInUser().map(AppUserPrincipal::getUniversityId);
	}

	/** The current university; fails loudly instead of ever guessing one. */
	public static UUID require() {
		return current().orElseThrow(() -> new IllegalStateException("No university in the current context."));
	}

	/** True when a signed-in user is present (whether or not they belong to a university). */
	static boolean hasSignedInUser() {
		return signedInUser().isPresent();
	}

	static boolean hasExplicit() {
		return EXPLICIT.get() != null;
	}

	/** Test support only (package-private): keeps a university for a whole test method. */
	static void enter(UUID universityId) {
		EXPLICIT.set(universityId);
	}

	static void exit() {
		EXPLICIT.remove();
	}

	public static <T> T callAs(UUID universityId, Supplier<T> work) {
		if (universityId == null) {
			throw new IllegalArgumentException("universityId is required");
		}
		UUID previous = EXPLICIT.get();
		EXPLICIT.set(universityId);
		try {
			return work.get();
		} finally {
			if (previous == null) {
				EXPLICIT.remove();
			} else {
				EXPLICIT.set(previous);
			}
		}
	}

	public static void runAs(UUID universityId, Runnable work) {
		callAs(universityId, () -> {
			work.run();
			return null;
		});
	}

	private static Optional<AppUserPrincipal> signedInUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal ? Optional.of(principal) : Optional.empty();
	}
}
