package com.uniguard.schedule.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uniguard.schedule.dto.auth.SessionResponse;
import com.uniguard.schedule.dto.auth.TwoFactorDtos.CodeRequest;
import com.uniguard.schedule.dto.auth.TwoFactorDtos.PasswordAndCodeRequest;
import com.uniguard.schedule.dto.auth.TwoFactorDtos.RecoveryCodes;
import com.uniguard.schedule.dto.auth.TwoFactorDtos.SetupRequest;
import com.uniguard.schedule.dto.auth.TwoFactorDtos.SetupResponse;
import com.uniguard.schedule.dto.auth.TwoFactorDtos.Status;
import com.uniguard.schedule.exception.AccountLockedException;
import com.uniguard.schedule.security.AppUserPrincipal;
import com.uniguard.schedule.security.AuthCookies;
import com.uniguard.schedule.service.AuthService;
import com.uniguard.schedule.service.TwoFactorService;

/** Two-step sign-in: the second step of sign-in, and each user's own set-up. */
@RestController
@RequestMapping("/api/auth/2fa")
public class TwoFactorController {

	private final AuthService authService;
	private final TwoFactorService twoFactorService;
	private final AuthCookies authCookies;

	public TwoFactorController(AuthService authService, TwoFactorService twoFactorService, AuthCookies authCookies) {
		this.authService = authService;
		this.twoFactorService = twoFactorService;
		this.authCookies = authCookies;
	}

	/** Second step of sign-in. Public: the HttpOnly challenge cookie proves the password was right. */
	@PostMapping("/verify")
	public SessionResponse verify(@Valid @RequestBody CodeRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
		AuthService.SignedIn signedIn;
		try {
			signedIn = authService.completeTwoFactor(authCookies.readMfa(httpRequest), request.code(), httpRequest);
		} catch (AccountLockedException locked) {
			authCookies.clearMfa(response);
			throw locked;
		}
		// A wrong code keeps the cookie for another try; the server decides when the step is over
		// (expired / too many wrong codes), so a stale cookie is harmless.
		authCookies.clearMfa(response);
		authCookies.writeAccess(response, signedIn.accessToken(), signedIn.accessExpiresAt());
		authCookies.writeRefresh(response, signedIn.refreshToken(), signedIn.sessionExpiresAt());
		return new SessionResponse(signedIn.email(), signedIn.accessExpiresAt(), signedIn.sessionExpiresAt());
	}

	@GetMapping
	public Status status(@AuthenticationPrincipal AppUserPrincipal user) {
		return twoFactorService.status(user.getId());
	}

	@PostMapping("/setup")
	public SetupResponse setup(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody SetupRequest request) {
		return twoFactorService.startSetup(user.getId(), request.currentPassword());
	}

	@PostMapping("/enable")
	public RecoveryCodes enable(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody CodeRequest request) {
		return twoFactorService.enable(user.getId(), user.getSessionId(), request.code());
	}

	@PostMapping("/disable")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disable(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody PasswordAndCodeRequest request) {
		twoFactorService.disable(user.getId(), request.currentPassword(), request.code());
	}

	@PostMapping("/recovery-codes")
	public RecoveryCodes recoveryCodes(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody PasswordAndCodeRequest request) {
		return twoFactorService.regenerateRecoveryCodes(user.getId(), request.currentPassword(), request.code());
	}
}
