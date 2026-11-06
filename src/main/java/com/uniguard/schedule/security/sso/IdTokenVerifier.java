package com.uniguard.schedule.security.sso;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks an ID token from Google / Microsoft: RSA signature with the provider's published keys,
 * audience (our client id), expiry. Issuer and nonce are checked by the caller per provider.
 * Unsigned tokens and tokens signed with a shared secret are refused (only RSA keys are accepted).
 */
@Component
public class IdTokenVerifier {

	private static final Duration KEYS_FRESH_FOR = Duration.ofHours(6);
	/** Re-read the keys for an unknown key id at most this often (providers rotate keys). */
	private static final Duration MIN_REFRESH_GAP = Duration.ofMinutes(1);

	private final OidcHttpClient http;
	private final Map<String, CachedKeys> cache = new ConcurrentHashMap<>();

	public IdTokenVerifier(OidcHttpClient http) {
		this.http = http;
	}

	private record CachedKeys(Map<String, PublicKey> keys, Instant fetchedAt) {
	}

	public Claims verify(String idToken, String jwksUri, String clientId) {
		if (idToken == null || idToken.isBlank()) {
			throw new SsoException(SsoException.FAILED, "No ID token in the answer");
		}
		Claims claims;
		try {
			claims = Jwts.parser()
				.keyLocator(new LocatorAdapter<Key>() {
					@Override
					protected Key locate(JwsHeader header) {
						return key(jwksUri, header.getKeyId());
					}
				})
				.requireAudience(clientId)
				.clockSkewSeconds(60)
				.build()
				.parseSignedClaims(idToken)
				.getPayload();
		} catch (JwtException | IllegalArgumentException ex) {
			throw new SsoException(SsoException.FAILED, "ID token rejected: " + ex.getMessage(), ex);
		}
		// OpenID Connect: a token for several audiences must name us as the authorized party.
		if (claims.getAudience() != null && claims.getAudience().size() > 1 && !clientId.equals(claims.get("azp", String.class))) {
			throw new SsoException(SsoException.FAILED, "ID token has several audiences and is not authorized for us");
		}
		return claims;
	}

	private PublicKey key(String jwksUri, String kid) {
		CachedKeys cached = cache.get(jwksUri);
		Instant now = Instant.now();
		boolean fresh = cached != null && cached.fetchedAt().plus(KEYS_FRESH_FOR).isAfter(now);
		if (fresh && kid != null && cached.keys().containsKey(kid)) {
			return cached.keys().get(kid);
		}
		if (cached == null || cached.fetchedAt().plus(MIN_REFRESH_GAP).isBefore(now) || !fresh) {
			cached = new CachedKeys(fetch(jwksUri), now);
			cache.put(jwksUri, cached);
		}
		PublicKey key = kid == null ? null : cached.keys().get(kid);
		if (key == null) {
			throw new SsoException(SsoException.FAILED, "Unknown signing key " + kid);
		}
		return key;
	}

	@SuppressWarnings("unchecked")
	private Map<String, PublicKey> fetch(String jwksUri) {
		Object list = http.getJson(jwksUri).get("keys");
		Map<String, PublicKey> keys = new HashMap<>();
		if (list instanceof List<?> entries) {
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> raw) {
					Map<String, Object> jwk = (Map<String, Object>) raw;
					if ("RSA".equals(jwk.get("kty")) && jwk.get("kid") != null && !"enc".equals(jwk.get("use"))) {
						keys.put(String.valueOf(jwk.get("kid")), rsaKey(String.valueOf(jwk.get("n")), String.valueOf(jwk.get("e"))));
					}
				}
			}
		}
		return keys;
	}

	static PublicKey rsaKey(String n, String e) {
		try {
			Base64.Decoder decoder = Base64.getUrlDecoder();
			return KeyFactory.getInstance("RSA").generatePublic(
				new RSAPublicKeySpec(new BigInteger(1, decoder.decode(n)), new BigInteger(1, decoder.decode(e))));
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new SsoException(SsoException.FAILED, "Bad signing key from the provider", ex);
		}
	}
}
