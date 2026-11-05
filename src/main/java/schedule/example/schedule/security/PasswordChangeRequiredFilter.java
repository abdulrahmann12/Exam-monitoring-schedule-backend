package schedule.example.schedule.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.common.ApiErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * While an account carries a temporary password (created or reset by an administrator), the server
 * only allows reading the own session, changing the password and logging out. Everything else gets
 * 403, so the temporary password cannot be used to read or change data — not even by calling the
 * API directly.
 *
 * <p>Not a Spring bean on purpose: it is created by {@code SecurityConfig} and only runs inside the
 * security filter chain (right after JWT authentication).
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

	/** "METHOD path" pairs that stay reachable with a temporary password. */
	private static final Set<String> ALLOWED = Set.of(
		"GET /api/auth/me",
		"POST /api/auth/change-password",
		"POST /api/auth/logout",
		"POST /api/auth/logout-all"
	);

	private final ObjectMapper objectMapper;

	public PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain chain
	) throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		boolean mustChange = auth != null
			&& auth.getPrincipal() instanceof AppUserPrincipal principal
			&& principal.isMustChangePassword();
		if (mustChange
			&& !"OPTIONS".equalsIgnoreCase(request.getMethod())
			&& !ALLOWED.contains(request.getMethod().toUpperCase() + " " + request.getRequestURI())) {
			ApiErrorResponse error = new ApiErrorResponse(
				Instant.now(),
				HttpStatus.FORBIDDEN.value(),
				HttpStatus.FORBIDDEN.getReasonPhrase(),
				Messages.AUTH_PASSWORD_CHANGE_REQUIRED,
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
}
