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
 * JWT token provider. Signs and validates HMAC-SHA256 tokens.
 * The signing key is built once at startup and reused on every request.
 */
@Component
public class JwtTokenProvider {

	private final JwtProperties jwtProperties;

	/** Signing key built once at startup. Thread-safe. */
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
	 * Parses the token, verifies signature, and checks expiry in one pass.
	 *
	 * @param token raw JWT string (without "Bearer " prefix)
	 * @return validated Claims payload
	 * @throws JwtException if the token is malformed, invalid, or expired
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