package com.uniguard.schedule.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.uniguard.schedule.audit.AuditCategory;
import com.uniguard.schedule.audit.AuditEntry;
import com.uniguard.schedule.audit.AuditService;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.common.ApiErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Stops other websites from making a signed-in browser change data (CSRF).
 *
 * <p>Cookies are sent automatically by the browser, so for every state-changing request that
 * carries a login cookie (and for the login/logout endpoints themselves) two checks apply:
 * <ol>
 *   <li>The custom header {@value #HEADER} must be present. A plain HTML form cannot set it, and
 *       a script on another site can only set it after a CORS pre-flight, which only the configured
 *       frontend origins pass.</li>
 *   <li>If the browser sends an {@code Origin}, it must be an allowed frontend origin or the API's
 *       own origin.</li>
 * </ol>
 * Requests that authenticate with an {@code Authorization} header (scripts) carry no cookie and
 * are not affected. Not a Spring bean: created by {@code SecurityConfig}.
 */
public class CsrfProtectionFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Requested-With";
	private static final Logger LOGGER = LoggerFactory.getLogger(CsrfProtectionFilter.class);
	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	private final CorsConfigurationSource corsConfigurationSource;
	private final ObjectMapper objectMapper;
	private final AuditService auditService;

	public CsrfProtectionFilter(CorsConfigurationSource corsConfigurationSource, ObjectMapper objectMapper, AuditService auditService) {
		this.corsConfigurationSource = corsConfigurationSource;
		this.objectMapper = objectMapper;
		this.auditService = auditService;
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain chain
	) throws ServletException, IOException {
		if (needsCheck(request) && !passes(request)) {
			LOGGER.warn("[CSRF] Blocked {} {} origin={} ip={}", request.getMethod(), request.getRequestURI(),
				request.getHeader(HttpHeaders.ORIGIN), request.getRemoteAddr());
			String origin = request.getHeader(HttpHeaders.ORIGIN);
			auditService.record(new AuditEntry("FORGED_REQUEST_BLOCKED", AuditCategory.SECURITY, null, null,
				"Blocked a request that did not come from the app: " + request.getMethod() + " " + request.getRequestURI()
					+ (origin != null ? " (from " + origin + ")" : ""), AuditEntry.Outcome.DENIED, null));
			ApiErrorResponse error = new ApiErrorResponse(
				Instant.now(),
				HttpStatus.FORBIDDEN.value(),
				HttpStatus.FORBIDDEN.getReasonPhrase(),
				Messages.AUTH_CSRF_REJECTED,
				request.getRequestURI(),
				List.of()
			);
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			objectMapper.writeValue(response.getOutputStream(), error);
			return;
		}
		chain.doFilter(request, response);
	}

	private static boolean needsCheck(HttpServletRequest request) {
		if (SAFE_METHODS.contains(request.getMethod().toUpperCase())) {
			return false;
		}
		return AuthCookies.hasAuthCookie(request) || request.getRequestURI().startsWith("/api/auth/");
	}

	private boolean passes(HttpServletRequest request) {
		String marker = request.getHeader(HEADER);
		if (marker == null || marker.isBlank()) {
			return false;
		}
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin == null || origin.isBlank()) {
			return true; // same-origin requests through a proxy may omit it; the header check above still applies
		}
		if (origin.equalsIgnoreCase(ownOrigin(request))) {
			return true;
		}
		CorsConfiguration cors = corsConfigurationSource.getCorsConfiguration(request);
		return cors != null && cors.checkOrigin(origin) != null;
	}

	private static String ownOrigin(HttpServletRequest request) {
		int port = request.getServerPort();
		boolean defaultPort = ("https".equalsIgnoreCase(request.getScheme()) && port == 443)
			|| ("http".equalsIgnoreCase(request.getScheme()) && port == 80);
		return request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
	}
}
