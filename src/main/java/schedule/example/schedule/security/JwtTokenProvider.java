package schedule.example.schedule.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT token provider.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>The {@link SecretKey} is computed <em>once</em> at startup via {@link PostConstruct},
 *       not on every call — avoiding repeated byte-encoding on every request.</li>
 *   <li>{@link #parseAndValidate(String)} performs a single HMAC-SHA256 verification that
 *       both extracts claims AND validates the signature/expiry in one pass. Callers should
 *       not call any secondary validation method after this succeeds.</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

	private final JwtProperties jwtProperties;

	/** Immutable key computed once at startup. Thread-safe. */
	private SecretKey signingKey;

	public JwtTokenProvider(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
	}

	@PostConstruct
	private void initSigningKey() {
		byte[] keyBytes = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException(
				"JWT secret must be at least 256 bits (32 bytes). Current length: " + keyBytes.length);
		}
		this.signingKey = Keys.hmacShaKeyFor(keyBytes);
	}

	public TokenDetails generateToken(String subject) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(jwtProperties.expirationMinutes(), ChronoUnit.MINUTES);

		String token = Jwts.builder()
			.subject(subject)
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(expiresAt))
			.signWith(signingKey)
			.compact();

		return new TokenDetails(token, expiresAt);
	}

	/**
	 * Parses, signature-verifies, and expiry-checks the token in a single JJWT pass.
	 *
	 * @param token the raw JWT string (without "Bearer " prefix)
	 * @return the validated {@link Claims} payload
	 * @throws JwtException if the token is malformed, has an invalid signature, or is expired
	 */
	public Claims parseAndValidate(String token) {
		return Jwts.parser()
			.verifyWith(signingKey)
			.build()
			.parseSignedClaims(token)
			.getPayload();
	}


	public record TokenDetails(String token, Instant expiresAt) {
	}
}