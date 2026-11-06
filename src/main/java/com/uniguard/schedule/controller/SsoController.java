package com.uniguard.schedule.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uniguard.schedule.security.AppUserPrincipal;
import com.uniguard.schedule.security.AuthCookies;
import com.uniguard.schedule.security.sso.SsoException;
import com.uniguard.schedule.security.sso.SsoService;
import com.uniguard.schedule.tenant.TenantContext;

import java.util.List;
import java.util.UUID;

/**
 * Sign-in with Microsoft / Google. {@code start} and {@code callback} are browser navigations
 * (redirects), everything else is a normal JSON API.
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoController {

	private final SsoService ssoService;
	private final AuthCookies authCookies;

	public SsoController(SsoService ssoService, AuthCookies authCookies) {
		this.ssoService = ssoService;
		this.authCookies = authCookies;
	}

	public record LinkRequest(
		@NotBlank(message = "Current password is required") @Size(max = 200) String currentPassword,
		@Size(max = 40) String returnPath
	) {
	}

	public record LinkStarted(String url) {
	}

	public record UniversitySsoRequest(@Size(max = 64) String microsoftTenantId) {
	}

	/** Public: which buttons the sign-in page shows. */
	@GetMapping("/providers")
	public List<SsoService.ProviderInfo> providers() {
		return ssoService.enabledProviders();
	}

	/** Public: the "Sign in with ..." button. Sends the browser to Google / Microsoft. */
	@GetMapping("/{provider}/start")
	public ResponseEntity<Void> start(@PathVariable String provider, HttpServletRequest request, HttpServletResponse response) {
		try {
			SsoService.Started started = ssoService.startLogin(provider, request.getRemoteAddr());
			authCookies.writeSso(response, started.state(), started.expiresAt());
			return redirect(started.authorizationUrl());
		} catch (SsoException ex) {
			return redirect(ssoService.frontendUrl(ssoService.errorPathWithoutState(ex.code())));
		}
	}

	/** Signed in: connect a Google / Microsoft account. Answers the URL to open (the page navigates there). */
	@PostMapping("/{provider}/link")
	public LinkStarted link(
		@PathVariable String provider,
		@Valid @RequestBody LinkRequest body,
		@AuthenticationPrincipal AppUserPrincipal user,
		HttpServletResponse response
	) {
		SsoService.Started started;
		try {
			started = ssoService.startLink(provider, user.getId(), body.currentPassword(), body.returnPath());
		} catch (SsoException ex) {
			throw new com.uniguard.schedule.exception.BusinessRuleViolationException(
				com.uniguard.schedule.config.Messages.SSO_PROVIDER_UNAVAILABLE);
		}
		authCookies.writeSso(response, started.state(), started.expiresAt());
		return new LinkStarted(started.authorizationUrl());
	}

	/** Public: Google / Microsoft send the browser back here. Always ends with a redirect into the app. */
	@GetMapping("/{provider}/callback")
	public ResponseEntity<Void> callback(
		@PathVariable String provider,
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String state,
		@RequestParam(required = false) String error,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		String cookieState = authCookies.readSso(request);
		authCookies.clearSso(response);
		SsoService.Outcome outcome;
		try {
			outcome = ssoService.callback(provider, code, state, cookieState, error, request);
		} catch (SsoException ex) {
			return redirect(ssoService.frontendUrl(ssoService.errorPathWithoutState(ex.code())));
		}
		if (outcome.challenge() != null) {
			authCookies.writeMfa(response, outcome.challenge().token(), outcome.challenge().expiresAt());
		}
		if (outcome.signedIn() != null) {
			authCookies.writeAccess(response, outcome.signedIn().accessToken(), outcome.signedIn().accessExpiresAt());
			authCookies.writeRefresh(response, outcome.signedIn().refreshToken(), outcome.signedIn().sessionExpiresAt());
		}
		return redirect(ssoService.frontendUrl(outcome.path()));
	}

	@GetMapping("/connections")
	public List<SsoService.ConnectedIdentity> connections(@AuthenticationPrincipal AppUserPrincipal user) {
		return ssoService.connections(user.getId());
	}

	@DeleteMapping("/connections/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disconnect(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
		ssoService.disconnect(user.getId(), id);
	}

	/** University administrators: the university's own Microsoft organisation (tenant id). */
	@GetMapping("/university")
	public SsoService.UniversitySso universitySettings() {
		return ssoService.universitySettings(TenantContext.require());
	}

	@PutMapping("/university")
	public SsoService.UniversitySso updateUniversitySettings(@Valid @RequestBody UniversitySsoRequest body) {
		return ssoService.updateUniversitySettings(TenantContext.require(), body.microsoftTenantId());
	}

	private static ResponseEntity<Void> redirect(String url) {
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, url)
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.build();
	}
}
