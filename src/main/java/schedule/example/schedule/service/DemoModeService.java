package schedule.example.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.DemoProperties;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.exception.DemoOperationNotAllowedException;

import java.time.Instant;

/**
 * Central service for all demo-mode enforcement logic.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   demoModeService.rejectIfDemoUser("delete-all-schedules");
 * }</pre>
 *
 * <p>Inject this service into any controller or service that exposes destructive operations
 * and call {@link #rejectIfDemoUser(String)} at the top of the method body.
 */
@Service
public class DemoModeService {

    private static final Logger log = LoggerFactory.getLogger(DemoModeService.class);

    private final DemoProperties demoProperties;

    public DemoModeService(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    /**
     * The Spring Security role authority granted to the demo account.
     * {@code .roles("DEMO_ADMIN")} in {@link schedule.example.schedule.security.AdminUserDetailsService}
     * produces the {@code ROLE_DEMO_ADMIN} authority string.
     */
    private static final String DEMO_ROLE_AUTHORITY = "ROLE_DEMO_ADMIN";

    /**
     * Returns {@code true} when the currently authenticated principal is the demo account.
     *
     * <p>Detection uses two independent signals — either is sufficient:
     * <ol>
     *   <li>{@code ROLE_DEMO_ADMIN} authority — role-based check (primary).</li>
     *   <li>{@code demo@uniguard.com} email — email-based check (fallback).</li>
     * </ol>
     *
     * @return {@code true} if the current principal is the demo account
     */
    public boolean isDemoUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        boolean hasDemoRole = auth.getAuthorities()
            .contains(new SimpleGrantedAuthority(DEMO_ROLE_AUTHORITY));
        return hasDemoRole || isDemoAccount(auth.getName());
    }

    /**
     * Returns {@code true} when the given e-mail belongs to the demo account.
     *
     * @param email the e-mail to check (case-insensitive)
     * @return {@code true} if email matches the demo account address
     */
    public boolean isDemoAccount(String email) {
        if (email == null) {
            return false;
        }
        return DemoProperties.DEMO_EMAIL.equalsIgnoreCase(email.trim());
    }

    /**
     * Throws {@link DemoOperationNotAllowedException} when the current user is the demo account.
     * Logs a WARN entry for security monitoring.
     *
     * @param operationType a short label describing the blocked operation (for audit logs)
     * @throws DemoOperationNotAllowedException when the current user is the demo account
     */
    public void rejectIfDemoUser(String operationType) {
        if (!demoProperties.enabled()) {
            return; // demo mode disabled — no restrictions apply
        }
        if (isDemoUser()) {
            String email = resolveCurrentEmail();
            log.warn("[DEMO] Blocked operation='{}' for email='{}' at timestamp={}",
                operationType, email, Instant.now());
            throw new DemoOperationNotAllowedException(Messages.DEMO_OPERATION_NOT_ALLOWED);
        }
    }

    // -------------------------------------------------------------------------

    private String resolveCurrentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null) ? auth.getName() : "unknown";
    }
}

