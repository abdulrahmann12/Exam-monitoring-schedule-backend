package schedule.example.schedule.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import schedule.example.schedule.dto.common.ApiErrorResponse;

import schedule.example.schedule.audit.AuditCategory;
import schedule.example.schedule.audit.AuditEntry;
import schedule.example.schedule.audit.AuditService;
import schedule.example.schedule.config.Messages;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;
	private final AuditService auditService;

	public RestAccessDeniedHandler(ObjectMapper objectMapper, AuditService auditService) {
		this.objectMapper = objectMapper;
		this.auditService = auditService;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
		throws IOException, ServletException {
		// The UI hides what a role may not do, so a refusal here is worth a look.
		auditService.record(new AuditEntry("ACCESS_DENIED", AuditCategory.SECURITY, null, null,
			"Access denied: " + request.getMethod() + " " + request.getRequestURI(), AuditEntry.Outcome.DENIED, null));
		ApiErrorResponse error = new ApiErrorResponse(
			Instant.now(),
			HttpStatus.FORBIDDEN.value(),
			HttpStatus.FORBIDDEN.getReasonPhrase(),
			Messages.AUTH_ACCESS_DENIED,
			request.getRequestURI(),
			List.of()
		);

		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), error);
	}
}