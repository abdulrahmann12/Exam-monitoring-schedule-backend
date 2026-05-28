package schedule.example.schedule.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.DemoProperties;
import schedule.example.schedule.dto.auth.AuthResponse;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.security.JwtTokenProvider;

import java.time.Instant;

/**
 * Handles the demo-login flow.
 *
 * <p>Bypasses the standard {@link AuthService} / {@link org.springframework.security.authentication.AuthenticationManager}
 * pipeline and generates a JWT directly for the pre-seeded demo account.
 * No credentials are required from the caller.
 *
 * <p>This service is intentionally separate from {@link AuthService} to avoid
 * any coupling between the demo feature and the production authentication path.
 */
@Service
public class DemoAuthService {

    private static final Logger log = LoggerFactory.getLogger(DemoAuthService.class);

    private final DemoProperties demoProperties;
    private final AdminUserRepository adminUserRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public DemoAuthService(
        DemoProperties demoProperties,
        AdminUserRepository adminUserRepository,
        JwtTokenProvider jwtTokenProvider
    ) {
        this.demoProperties = demoProperties;
        this.adminUserRepository = adminUserRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Authenticates as the demo account and returns a JWT response.
     *
     * @param clientIp the resolved client IP address (for audit logging)
     * @return a valid {@link AuthResponse} for the demo account
     * @throws NotFoundException when the demo account has not been seeded yet
     * @throws schedule.example.schedule.exception.BusinessRuleViolationException when demo mode is disabled
     */
    public AuthResponse demoLogin(String clientIp) {
        if (!demoProperties.enabled()) {
            // Callers should check the flag before calling; this is a safety net.
            throw new schedule.example.schedule.exception.BusinessRuleViolationException(
                "Demo mode is currently disabled."
            );
        }

        String demoEmail = DemoProperties.DEMO_EMAIL;
        boolean exists = adminUserRepository.findByEmailIgnoreCase(demoEmail).isPresent();

        if (!exists) {
            log.warn("[DEMO] Demo login attempted but demo account '{}' does not exist. " +
                "Verify DataInitializer ran with app.demo.enabled=true.", demoEmail);
            throw new NotFoundException("Demo account is not available. Please contact the administrator.");
        }

        JwtTokenProvider.TokenDetails tokenDetails = jwtTokenProvider.generateToken(demoEmail);

        log.info("[DEMO] Demo login SUCCESS — ip='{}', email='{}', timestamp={}, expiresAt={}",
            clientIp, demoEmail, Instant.now(), tokenDetails.expiresAt());

        return new AuthResponse(tokenDetails.token(), "Bearer", tokenDetails.expiresAt(), demoEmail);
    }
}

