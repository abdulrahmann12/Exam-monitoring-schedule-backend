package schedule.example.schedule.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.common.ApiErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Makes demo sessions strictly read-only on the server.
 *
 * <p>The demo token is public (anyone can call {@code /api/auth/demo-login}), so the browser-side
 * sandbox is not a security boundary — a script can call the API directly. This filter rejects
 * every state-changing request from the demo role with 403, except a short allow-list of
 * requests that never write data.
 *
 * <p>Runs right after {@link JwtAuthenticationFilter}, so the role is already known.
 */
@Component
public class DemoReadOnlyFilter extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(DemoReadOnlyFilter.class);

	private static final SimpleGrantedAuthority DEMO_ROLE = new SimpleGrantedAuthority("ROLE_DEMO_ADMIN");
	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	/** POST endpoints that do not modify data. */
	private static final Set<String> READ_ONLY_POSTS = Set.of(
		"/api/auth/logout",
		"/api/assignments/generate-preview"
	);

	private final ObjectMapper objectMapper;

	public DemoReadOnlyFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain chain
	) throws ServletException, IOException {
		if (isBlockedDemoWrite(request)) {
			LOGGER.warn("[DEMO] Blocked {} {} from ip={}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
			ApiErrorResponse error = new ApiErrorResponse(
				Instant.now(),
				HttpStatus.FORBIDDEN.value(),
				HttpStatus.FORBIDDEN.getReasonPhrase(),
				Messages.DEMO_OPERATION_NOT_ALLOWED,
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

	private static boolean isBlockedDemoWrite(HttpServletRequest request) {
		if (SAFE_METHODS.contains(request.getMethod().toUpperCase())) {
			return false;
		}
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.getAuthorities().contains(DEMO_ROLE)) {
			return false;
		}
		return !("POST".equalsIgnoreCase(request.getMethod()) && READ_ONLY_POSTS.contains(request.getRequestURI()));
	}
}
