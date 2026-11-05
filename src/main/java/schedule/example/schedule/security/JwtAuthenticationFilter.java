package schedule.example.schedule.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates API calls with the short-lived access JWT.
 *
 * <p>The browser sends it in the HttpOnly {@code ug_access} cookie. An {@code Authorization: Bearer}
 * header is also accepted for scripts and tests — browsers never attach that header on their own,
 * so it cannot be abused by another website (CSRF).
 *
 * <p>Every token must carry a session id ({@code sid}); the session must still be open, so logging
 * out ends the token at once instead of when it expires.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String TOKEN_PREFIX = "Bearer ";

	/**
	 * Endpoints that work without (or with an expired) access token. Skipping them here matters:
	 * an expired cookie must not block the very request that renews it, or a fresh sign-in.
	 */
	private static final Set<String> SESSION_ENDPOINTS = Set.of(
		"/api/auth/login",
		"/api/auth/demo-login",
		"/api/auth/refresh",
		"/api/auth/logout"
	);

	private final JwtTokenProvider jwtTokenProvider;
	private final AdminUserDetailsService adminUserDetailsService;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;
	private final AuthCookies authCookies;

	public JwtAuthenticationFilter(
		JwtTokenProvider jwtTokenProvider,
		AdminUserDetailsService adminUserDetailsService,
		RestAuthenticationEntryPoint authenticationEntryPoint,
		AuthCookies authCookies
	) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.adminUserDetailsService = adminUserDetailsService;
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.authCookies = authCookies;
	}

	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return SESSION_ENDPOINTS.contains(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain filterChain
	) throws ServletException, IOException {
		String token = resolveToken(request);
		if (token == null || SecurityContextHolder.getContext().getAuthentication() != null) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			Claims claims = jwtTokenProvider.parseAndValidate(token);
			Integer tokenVersion = claims.get(JwtTokenProvider.TOKEN_VERSION_CLAIM, Integer.class);
			String sid = claims.get(JwtTokenProvider.SESSION_CLAIM, String.class);
			if (sid == null) {
				throw new UsernameNotFoundException("Token has no session");
			}
			AppUserPrincipal principal = adminUserDetailsService.loadForToken(claims.getSubject(), tokenVersion, UUID.fromString(sid));

			UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
			authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} catch (JwtException | IllegalArgumentException | UsernameNotFoundException ex) {
			SecurityContextHolder.clearContext();
			authenticationEntryPoint.commence(
				request,
				response,
				new BadCredentialsException("Invalid or expired authentication token", ex)
			);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (StringUtils.hasText(header) && header.startsWith(TOKEN_PREFIX)) {
			return header.substring(TOKEN_PREFIX.length());
		}
		return authCookies.readAccess(request);
	}
}
