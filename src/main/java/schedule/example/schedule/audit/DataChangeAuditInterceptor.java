package schedule.example.schedule.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;
import java.util.Set;

/**
 * Records every successful request that changes data (POST/PUT/PATCH/DELETE answered with 2xx).
 *
 * <p>Before the change it reads the target's name (so a deleted room is still named in the log);
 * after the change it uses the response body for details. Failed requests change nothing and are
 * not recorded here. Request bodies are never stored.
 */
@ControllerAdvice
public class DataChangeAuditInterceptor implements HandlerInterceptor, ResponseBodyAdvice<Object> {

	private static final Set<String> CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
	private static final String NAME_ATTRIBUTE = DataChangeAuditInterceptor.class.getName() + ".targetName";
	private static final String BODY_ATTRIBUTE = DataChangeAuditInterceptor.class.getName() + ".body";

	private final AuditService auditService;
	private final AuditTargetNames targetNames;

	public DataChangeAuditInterceptor(AuditService auditService, AuditTargetNames targetNames) {
		this.auditService = auditService;
		this.targetNames = targetNames;
	}

	@Override
	public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
		if (!isRecorded(request)) {
			return true;
		}
		AuditRoutes.Route route = AuditRoutes.find(request.getMethod(), pattern(request));
		String targetId = targetId(request, route);
		if (route != null && targetId != null) {
			request.setAttribute(NAME_ATTRIBUTE, targetNames.find(route.targetType(), targetId));
		}
		return true;
	}

	@Override
	public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
		if (ex != null || response.getStatus() < 200 || response.getStatus() >= 300 || !isRecorded(request)) {
			return;
		}
		String pattern = pattern(request);
		AuditRoutes.Route route = AuditRoutes.find(request.getMethod(), pattern);
		if (route == null) {
			auditService.record(AuditEntry.success("DATA_CHANGED", AuditCategory.OTHER, null, null,
				request.getMethod() + " " + request.getRequestURI()));
			return;
		}
		Object body = request.getAttribute(BODY_ATTRIBUTE);
		String targetName = (String) request.getAttribute(NAME_ATTRIBUTE);
		String targetId = targetId(request, route);
		if (targetId == null) {
			targetId = idOf(body);
		}
		auditService.record(AuditEntry.success(route.action(), route.category(), route.targetType(), targetId,
			route.describer().describe(body, targetName)));
	}

	// ── ResponseBodyAdvice: keeps the response of changing requests for the summary ──

	@Override
	public boolean supports(@NonNull MethodParameter returnType, @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, @NonNull MethodParameter returnType, @NonNull MediaType contentType,
		@NonNull Class<? extends HttpMessageConverter<?>> converterType, @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
		if (request instanceof ServletServerHttpRequest servlet && CHANGING_METHODS.contains(servlet.getServletRequest().getMethod())) {
			servlet.getServletRequest().setAttribute(BODY_ATTRIBUTE, body);
		}
		return body;
	}

	// ── helpers ──

	private static boolean isRecorded(HttpServletRequest request) {
		return CHANGING_METHODS.contains(request.getMethod()) && AuditRoutes.recordedHere(request.getRequestURI());
	}

	private static String pattern(HttpServletRequest request) {
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		return pattern == null ? request.getRequestURI() : pattern.toString();
	}

	@SuppressWarnings("unchecked")
	private static String targetId(HttpServletRequest request, AuditRoutes.Route route) {
		if (route == null || route.idVariable() == null) {
			return null;
		}
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		return variables instanceof Map<?, ?> map ? (String) ((Map<String, String>) map).get(route.idVariable()) : null;
	}

	/** Id of a created item, read from the response record's {@code id()} accessor when it has one. */
	private static String idOf(Object body) {
		if (body == null || !body.getClass().isRecord()) {
			return null;
		}
		try {
			Object id = body.getClass().getMethod("id").invoke(body);
			return id == null ? null : id.toString();
		} catch (ReflectiveOperationException ex) {
			return null;
		}
	}
}
