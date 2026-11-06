package schedule.example.schedule.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Writes and reads the two login cookies. Both are HttpOnly, so page scripts (and therefore an
 * XSS bug) can never read the tokens.
 *
 * <ul>
 *   <li>{@value #ACCESS_COOKIE} — short-lived JWT, sent with every API call ({@code Path=/api}).</li>
 *   <li>{@value #REFRESH_COOKIE} — long-lived random token, sent only to {@code /api/auth}
 *       (login, refresh, logout), never to the rest of the API.</li>
 * </ul>
 */
@Component
public class AuthCookies {

	public static final String ACCESS_COOKIE = "ug_access";
	public static final String REFRESH_COOKIE = "ug_refresh";
	/** Short-lived: "password was right, waiting for the two-step code". Sent only to /api/auth/2fa. */
	public static final String MFA_COOKIE = "ug_mfa";
	static final String MFA_PATH = "/api/auth/2fa";
	static final String ACCESS_PATH = "/api";
	static final String REFRESH_PATH = "/api/auth";

	private final AuthCookieProperties properties;

	public AuthCookies(AuthCookieProperties properties) {
		this.properties = properties;
	}

	public void writeAccess(HttpServletResponse response, String token, Instant expiresAt) {
		add(response, ACCESS_COOKIE, token, ACCESS_PATH, untilNow(expiresAt));
	}

	public void writeRefresh(HttpServletResponse response, String token, Instant expiresAt) {
		add(response, REFRESH_COOKIE, token, REFRESH_PATH, untilNow(expiresAt));
	}

	public void writeMfa(HttpServletResponse response, String token, Instant expiresAt) {
		add(response, MFA_COOKIE, token, MFA_PATH, untilNow(expiresAt));
	}

	public void clearMfa(HttpServletResponse response) {
		add(response, MFA_COOKIE, "", MFA_PATH, Duration.ZERO);
	}

	public String readMfa(HttpServletRequest request) {
		return read(request, MFA_COOKIE);
	}

	/** Deletes both cookies (logout, failed refresh). */
	public void clear(HttpServletResponse response) {
		add(response, ACCESS_COOKIE, "", ACCESS_PATH, Duration.ZERO);
		add(response, REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO);
	}

	public String readAccess(HttpServletRequest request) {
		return read(request, ACCESS_COOKIE);
	}

	public String readRefresh(HttpServletRequest request) {
		return read(request, REFRESH_COOKIE);
	}

	/** True when the request carries one of our cookies, i.e. a browser may have attached it automatically. */
	public static boolean hasAuthCookie(HttpServletRequest request) {
		return read(request, ACCESS_COOKIE) != null || read(request, REFRESH_COOKIE) != null;
	}

	private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
		ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(name, value)
			.httpOnly(true)
			.secure(properties.secure())
			.sameSite(properties.sameSite())
			.path(path)
			.maxAge(maxAge);
		if (properties.domain() != null) {
			cookie.domain(properties.domain());
		}
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
	}

	private static Duration untilNow(Instant expiresAt) {
		Duration left = Duration.between(Instant.now(), expiresAt);
		return left.isNegative() ? Duration.ZERO : left;
	}

	private static String read(HttpServletRequest request, String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
