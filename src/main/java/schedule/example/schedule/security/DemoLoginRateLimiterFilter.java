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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import schedule.example.schedule.dto.common.ApiErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP-based rate limiter for the demo login endpoint ({@code POST /api/auth/demo-login}).
 *
 * <p>Allows at most {@value MAX_ATTEMPTS} demo-login requests per IP per
 * {@value WINDOW_MILLIS}ms. This prevents automated abuse of the public demo endpoint
 * without affecting any existing authentication flow.
 *
 * <p><strong>NOTE:</strong> Single-node, in-memory implementation.
 * Replace with Redis-backed Bucket4j for multi-instance deployments.
 */
@Component
public class DemoLoginRateLimiterFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoLoginRateLimiterFilter.class);

    private static final String DEMO_LOGIN_PATH = "/api/auth/demo-login";
    private static final String POST = "POST";

    /** Maximum demo-login attempts allowed within the sliding window. */
    private static final int MAX_ATTEMPTS = 5;

    /** Sliding window duration in milliseconds (1 minute). */
    private static final long WINDOW_MILLIS = 60_000L;

    /** Periodic cleanup threshold — prune stale entries every N requests. */
    private static final int CLEANUP_INTERVAL = 200;

    private final ConcurrentHashMap<String, long[]> ipWindows = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final ObjectMapper objectMapper;

    public DemoLoginRateLimiterFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        if (!POST.equalsIgnoreCase(request.getMethod()) || !DEMO_LOGIN_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        if (requestCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            pruneStaleEntries();
        }

        String ip = resolveClientIp(request);
        long now = System.currentTimeMillis();

        long[] window = ipWindows.compute(ip, (_, existing) -> {
            if (existing == null || now - existing[1] > WINDOW_MILLIS) {
                return new long[]{1L, now};
            }
            existing[0]++;
            return existing;
        });

        if (window[0] > MAX_ATTEMPTS) {
            LOGGER.warn("[DEMO] Rate limit exceeded for demo-login from IP={}, attempts={}", ip, window[0]);
            writeRateLimitResponse(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    private void writeRateLimitResponse(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ApiErrorResponse error = new ApiErrorResponse(
            Instant.now(),
            HttpStatus.TOO_MANY_REQUESTS.value(),
            "Too Many Requests",
            "Too many demo login attempts. Please wait 1 minute before trying again.",
            request.getRequestURI(),
            List.of()
        );
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void pruneStaleEntries() {
        long now = System.currentTimeMillis();
        ipWindows.entrySet().removeIf(entry -> now - entry.getValue()[1] > WINDOW_MILLIS);
    }
}

