package schedule.example.schedule.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JwtTokenProvider {

	private final JwtProperties jwtProperties;

	public JwtTokenProvider(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
	}

	public TokenDetails generateToken(String subject) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(jwtProperties.expirationMinutes(), ChronoUnit.MINUTES);

		String token = Jwts.builder()
			.subject(subject)
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(expiresAt))
			.signWith(signingKey())
			.compact();

		return new TokenDetails(token, expiresAt);
	}

	public String extractSubject(String token) {
		return parseClaims(token).getSubject();
	}

	public boolean isValid(String token, UserDetails userDetails) {
		try {
			Claims claims = parseClaims(token);
			return claims.getSubject().equalsIgnoreCase(userDetails.getUsername())
				&& claims.getExpiration().after(new Date());
		} catch (JwtException | IllegalArgumentException ex) {
			return false;
		}
	}

	private Claims parseClaims(String token) {
		return Jwts.parser()
			.verifyWith(signingKey())
			.build()
			.parseSignedClaims(token)
			.getPayload();
	}

	private SecretKey signingKey() {
		return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
	}

	public record TokenDetails(String token, Instant expiresAt) {
	}
}