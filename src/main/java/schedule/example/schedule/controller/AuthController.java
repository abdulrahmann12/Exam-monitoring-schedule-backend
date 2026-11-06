package schedule.example.schedule.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import schedule.example.schedule.config.DemoProperties;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.auth.ChangePasswordRequest;
import schedule.example.schedule.dto.auth.ForgotPasswordRequest;
import schedule.example.schedule.dto.auth.LinkInfoResponse;
import schedule.example.schedule.dto.auth.LinkTokenRequest;
import schedule.example.schedule.dto.auth.SetPasswordFromLinkRequest;
import schedule.example.schedule.dto.common.MessageResponse;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.dto.auth.MeResponse;
import schedule.example.schedule.dto.auth.SessionResponse;
import schedule.example.schedule.dto.common.ApiErrorResponse;
import schedule.example.schedule.entity.University;
import schedule.example.schedule.exception.InvalidTokenException;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.security.AppUserPrincipal;
import schedule.example.schedule.security.AuthCookies;
import schedule.example.schedule.service.AccountTokenService;
import schedule.example.schedule.service.AuthService;
import schedule.example.schedule.service.PasswordRecoveryService;
import schedule.example.schedule.service.DemoAuthService;

import java.time.Instant;
import java.util.List;

/**
 * Sign-in and session endpoints. Tokens are only ever sent as HttpOnly cookies (see {@link AuthCookies}).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final AuthService authService;
	private final DemoAuthService demoAuthService;
	private final DemoProperties demoProperties;
	private final AuthCookies authCookies;
	private final PasswordRecoveryService passwordRecoveryService;
	private final AccountTokenService accountTokenService;
	private final UniversityRepository universityRepository;

	public AuthController(
		AuthService authService,
		DemoAuthService demoAuthService,
		DemoProperties demoProperties,
		AuthCookies authCookies,
		PasswordRecoveryService passwordRecoveryService,
		AccountTokenService accountTokenService,
		UniversityRepository universityRepository
	) {
		this.authService = authService;
		this.demoAuthService = demoAuthService;
		this.demoProperties = demoProperties;
		this.authCookies = authCookies;
		this.passwordRecoveryService = passwordRecoveryService;
		this.accountTokenService = accountTokenService;
		this.universityRepository = universityRepository;
	}

	@PostMapping("/login")
	public SessionResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
		return writeSession(authService.login(request, httpRequest), response);
	}

	/**
	 * Public demo login — no credentials. Returns 404 unless {@code app.demo.enabled=true}.
	 * Demo sessions are read-only on the server ({@code DemoReadOnlyFilter}).
	 */
	@PostMapping("/demo-login")
	public ResponseEntity<SessionResponse> demoLogin(HttpServletRequest request, HttpServletResponse response) {
		if (!demoProperties.enabled()) {
			log.info("[DEMO] Demo login attempted but demo mode is disabled. ip='{}'", request.getRemoteAddr());
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(writeSession(demoAuthService.demoLogin(request), response));
	}

	/**
	 * Renews the session from the refresh cookie: new access cookie, new (rotated) refresh cookie.
	 * On failure the cookies are cleared — except when another tab renewed the session a moment
	 * ago, because then the browser already holds the new, valid cookies.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<?> refresh(HttpServletRequest request, HttpServletResponse response) {
		try {
			return ResponseEntity.ok(writeSession(authService.refresh(authCookies.readRefresh(request)), response));
		} catch (InvalidTokenException ex) {
			if (!Messages.AUTH_SESSION_RENEWED_ELSEWHERE.equals(ex.getMessage())) {
				authCookies.clear(response);
			}
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiErrorResponse(
				Instant.now(), 401, HttpStatus.UNAUTHORIZED.getReasonPhrase(), ex.getMessage(), request.getRequestURI(), List.of()));
		}
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
			user.isMustChangePassword(),
			user.getUniversityId(),
			user.getUniversityId() == null ? null
				: universityRepository.findById(user.getUniversityId()).map(University::getName).orElse(null)
		);
	}

	/** Logs out this device. Works even when the access token has expired; always clears the cookies. */
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		authService.logout(authCookies.readRefresh(request));
		authCookies.clear(response);
	}

	/** Logs out every device of the current user, including this one. */
	@PostMapping("/logout-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logoutAll(@AuthenticationPrincipal AppUserPrincipal user, HttpServletResponse response) {
		authService.logoutEverywhere(user.getUsername());
		authCookies.clear(response);
	}

	/** Changes the password; other devices are signed out, this one gets a new access cookie. */
	@PostMapping("/change-password")
	public SessionResponse changePassword(
		@AuthenticationPrincipal AppUserPrincipal user,
		@Valid @RequestBody ChangePasswordRequest request,
		HttpServletResponse response
	) {
		AuthService.AccessToken access = authService.changePassword(user.getUsername(), user.getSessionId(), request);
		authCookies.writeAccess(response, access.token(), access.expiresAt());
		return new SessionResponse(user.getUsername(), access.expiresAt(), null);
	}

	/**
	 * "Forgot password". Always the same 202 answer, whether or not the account exists,
	 * so the endpoint cannot be used to find out which emails have accounts.
	 */
	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
		passwordRecoveryService.requestReset(request.email(), httpRequest.getRemoteAddr());
		return new MessageResponse(Messages.AUTH_RESET_REQUESTED);
	}

	/** Tells the password page what the link is for (invitation or reset) and for which account. */
	@PostMapping("/reset-password/check")
	public LinkInfoResponse checkLink(@Valid @RequestBody LinkTokenRequest request) {
		AccountTokenService.LinkInfo info = accountTokenService.check(request.token());
		return new LinkInfoResponse(info.purpose(), info.email(), info.expiresAt());
	}

	/** Sets the password from an invitation or reset link. All existing sessions are signed out. */
	@PostMapping("/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void resetPassword(@Valid @RequestBody SetPasswordFromLinkRequest request, HttpServletResponse response) {
		accountTokenService.setPassword(request.token(), request.newPassword());
		authCookies.clear(response); // a different account may have been signed in on this browser
	}

	private SessionResponse writeSession(AuthService.SignedIn signedIn, HttpServletResponse response) {
		authCookies.writeAccess(response, signedIn.accessToken(), signedIn.accessExpiresAt());
		authCookies.writeRefresh(response, signedIn.refreshToken(), signedIn.sessionExpiresAt());
		return new SessionResponse(signedIn.email(), signedIn.accessExpiresAt(), signedIn.sessionExpiresAt());
	}
}
