package schedule.example.schedule.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import schedule.example.schedule.dto.common.ApiErrorResponse;

import org.springframework.lang.NonNull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory IP-based rate limiter for the login endpoint.
 *
 * <p>Limits each IP to {@value MAX_ATTEMPTS} login attempts per
 * {@value WINDOW_MILLIS}ms sliding window. Responds with 429 when exceeded.
 *
 * <p><strong>NOTE:</strong> This is a single-node implementation. For multi-instance
 * deployments, replace with a Redis-backed solution (e.g. Bucket4j + Redis).
 */
@Component
@Order(1)
public class LoginRateLimiterFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginRateLimiterFilter.class);

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String POST = "POST";

    /** Maximum login attempts allowed within the time window. */
    private static final int MAX_ATTEMPTS = 10;

    /** Sliding window duration in milliseconds (1 minute). */
    private static final long WINDOW_MILLIS = 60_000L;

    /** Periodic cleanup threshold — prune stale entries every N requests. */
    private static final int CLEANUP_INTERVAL = 500;

    private final ConcurrentHashMap<String, long[]> ipWindows = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final ObjectMapper objectMapper;

    public LoginRateLimiterFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        if (!POST.equalsIgnoreCase(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // Periodic cleanup of stale entries to prevent unbounded memory growth
        if (requestCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            pruneStaleEntries();
        }

        String ip = resolveClientIp(request);
        long now = System.currentTimeMillis();

        long[] window = ipWindows.compute(ip, (_, existing) -> {
            if (existing == null || now - existing[1] > WINDOW_MILLIS) {
                // New or expired window: [count=1, windowStart=now]
                return new long[]{1L, now};
            }
            existing[0]++;
            return existing;
        });

        long attempts = window[0];
        if (attempts > MAX_ATTEMPTS) {
            LOGGER.warn("Rate limit exceeded for login from IP={}, attempts={}", ip, attempts);
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
            "Too many login attempts. Please wait 1 minute before trying again.",
            request.getRequestURI(),
            List.of()
        );
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }

    /**
     * Resolves the real client IP, respecting X-Forwarded-For for reverse-proxy setups.
     * Takes only the first entry to avoid IP spoofing via header manipulation.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Take the leftmost (originating) IP from the chain
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Removes entries whose sliding window has expired to prevent unbounded map growth. */
    private void pruneStaleEntries() {
        long now = System.currentTimeMillis();
        ipWindows.entrySet().removeIf(entry -> now - entry.getValue()[1] > WINDOW_MILLIS);
    }
}





