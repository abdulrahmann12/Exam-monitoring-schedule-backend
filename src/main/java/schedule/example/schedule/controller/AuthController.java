package schedule.example.schedule.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import schedule.example.schedule.config.DemoProperties;
import schedule.example.schedule.dto.auth.AuthResponse;
import schedule.example.schedule.dto.auth.ChangePasswordRequest;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.dto.auth.MeResponse;
import schedule.example.schedule.security.AppUserPrincipal;
import schedule.example.schedule.service.AuthService;
import schedule.example.schedule.service.DemoAuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final AuthService authService;
	private final DemoAuthService demoAuthService;
	private final DemoProperties demoProperties;

	public AuthController(AuthService authService, DemoAuthService demoAuthService, DemoProperties demoProperties) {
		this.authService = authService;
		this.demoAuthService = demoAuthService;
		this.demoProperties = demoProperties;
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	/**
	 * Public demo login — no credentials. Returns 404 unless {@code app.demo.enabled=true}.
	 * Demo sessions are read-only on the server ({@code DemoReadOnlyFilter}).
	 */
	@PostMapping("/demo-login")
	public ResponseEntity<AuthResponse> demoLogin(HttpServletRequest request) {
		// getRemoteAddr() is the real client IP: Tomcat's RemoteIpValve resolves it from
		// X-Forwarded-For only when the request came through a trusted proxy.
		String clientIp = request.getRemoteAddr();
		if (!demoProperties.enabled()) {
			log.info("[DEMO] Demo login attempted but demo mode is disabled. ip='{}'", clientIp);
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(demoAuthService.demoLogin(clientIp));
	}

	/** Resolves the current authenticated session — used by the frontend on startup. */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal AppUserPrincipal user) {
		return new MeResponse(
			user.getId(),
			user.getUsername(),
			user.getDisplayName(),
			user.getRole().name(),
			user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
			user.isMustChangePassword()
		);
	}

	/** Revokes every token of the current user (all devices). */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@AuthenticationPrincipal UserDetails userDetails) {
		authService.logout(userDetails.getUsername());
	}

	/** Changes the password, revokes all other sessions and returns a new token for this one. */
	@PostMapping("/change-password")
	public AuthResponse changePassword(
		@AuthenticationPrincipal UserDetails userDetails,
		@Valid @RequestBody ChangePasswordRequest request
	) {
		return authService.changePassword(userDetails.getUsername(), request);
	}
}
