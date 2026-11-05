package schedule.example.schedule.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.DemoProperties;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.repository.AdminUserRepository;

/**
 * Public demo login: no credentials, signs the visitor in as the pre-seeded demo account.
 * Every visitor gets their own session; the account itself is read-only on the server
 * ({@code DemoReadOnlyFilter}).
 */
@Service
public class DemoAuthService {

	private static final Logger log = LoggerFactory.getLogger(DemoAuthService.class);

	private final DemoProperties demoProperties;
	private final AdminUserRepository adminUserRepository;
	private final AuthService authService;

	public DemoAuthService(DemoProperties demoProperties, AdminUserRepository adminUserRepository, AuthService authService) {
		this.demoProperties = demoProperties;
		this.adminUserRepository = adminUserRepository;
		this.authService = authService;
	}

	public AuthService.SignedIn demoLogin(HttpServletRequest request) {
		if (!demoProperties.enabled()) {
			// Callers check the flag first; this is a safety net.
			throw new BusinessRuleViolationException("Demo mode is currently disabled.");
		}
		AdminUser demoUser = adminUserRepository.findByEmailIgnoreCase(DemoProperties.DEMO_EMAIL).orElseThrow(() -> {
			log.warn("[DEMO] Demo login attempted but the demo account does not exist. Verify app.demo.enabled=true at startup.");
			return new NotFoundException("Demo account is not available. Please contact the administrator.");
		});
		AuthService.SignedIn signedIn = authService.openSession(demoUser, request);
		log.info("[DEMO] Demo login — ip='{}'", request.getRemoteAddr());
		return signedIn;
	}
}
