package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.config.BootstrapAdminProperties;
import schedule.example.schedule.config.DemoProperties;
import schedule.example.schedule.dto.auth.AuthResponse;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.dto.auth.MeResponse;
import schedule.example.schedule.service.AuthService;
import schedule.example.schedule.service.DemoAuthService;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final AuthService authService;
	private final DemoAuthService demoAuthService;
	private final DemoProperties demoProperties;
	private final BootstrapAdminProperties bootstrapAdminProperties;

	public AuthController(
		AuthService authService,
		DemoAuthService demoAuthService,
		DemoProperties demoProperties,
		BootstrapAdminProperties bootstrapAdminProperties
	) {
		this.authService = authService;
		this.demoAuthService = demoAuthService;
		this.demoProperties = demoProperties;
		this.bootstrapAdminProperties = bootstrapAdminProperties;
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	/**
	 * Public demo login endpoint — no credentials required.
	 *
	 * <p>Returns a JWT for the pre-seeded demo account ({@code demo@uniguard.com}).
	 * Protected by a separate IP-based rate limiter ({@link schedule.example.schedule.security.DemoLoginRateLimiterFilter}).
	 * Returns 404 when {@code app.demo.enabled=false}.
	 */
	@PostMapping("/demo-login")
	public ResponseEntity<AuthResponse> demoLogin(HttpServletRequest request) {
		if (!demoProperties.enabled()) {
			log.info("[DEMO] Demo login attempted but demo mode is disabled. ip='{}'",
				resolveClientIp(request));
			return ResponseEntity.notFound().build();
		}
		String clientIp = resolveClientIp(request);
		AuthResponse response = demoAuthService.demoLogin(clientIp);
		return ResponseEntity.ok(response);
	}

	/** Resolves the current authenticated session — used by the frontend on startup. */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal UserDetails userDetails) {
		return new MeResponse(userDetails.getUsername(), userDetails.getAuthorities()
				.stream().map(GrantedAuthority::getAuthority).toList());
	}
	/**
	 * Diagnostic endpoint — returns the admin email that this deployment was bootstrapped with.
	 * Safe to expose publicly: reveals only the configured email address, never a password.
	 * Use this to verify the BOOTSTRAP_ADMIN_EMAIL env var on the deployed server matches
	 * the email you are logging in with.
	 *
	 * <p>Example response:
	 * <pre>{"bootstrapEmail": "admin@uniguard.local"}</pre>
	 */
	@GetMapping("/admin-check")
	public Map<String, String> adminCheck() {
		String email = bootstrapAdminProperties.email();
		String normalizedEmail = (email != null) ? email.trim().toLowerCase(Locale.ROOT) : "NOT_CONFIGURED";
		return Map.of(
			"bootstrapEmail", normalizedEmail,
			"hint", "Use this exact email to log in. If it differs from what you expected, " +
				"check the BOOTSTRAP_ADMIN_EMAIL environment variable on the deployed server."
		);
	}

	// -------------------------------------------------------------------------

	private String resolveClientIp(HttpServletRequest request) {
		String xff = request.getHeader("X-Forwarded-For");
		if (xff != null && !xff.isBlank()) {
			return xff.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
