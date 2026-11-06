package com.uniguard.schedule.security.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts authenticator secrets before they are stored (AES-256-GCM). The key comes from
 * {@code MFA_ENCRYPTION_KEY} (32 random bytes, Base64) and is separate from the JWT secret on
 * purpose: rotating the JWT secret must never lock everyone out of two-step sign-in.
 *
 * <p>Without a key, two-step sign-in cannot be switched on (the rest of the app works normally).
 */
@Component
public class SecretCipher {

	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec key;

	public SecretCipher(@Value("${app.mfa.encryption-key:}") String base64Key) {
		if (base64Key == null || base64Key.isBlank()) {
			this.key = null;
			return;
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64Key.trim());
		} catch (IllegalArgumentException ex) {
			throw new IllegalStateException("MFA_ENCRYPTION_KEY must be Base64 (generate: openssl rand -base64 32).", ex);
		}
		if (bytes.length != 32) {
			throw new IllegalStateException("MFA_ENCRYPTION_KEY must be exactly 32 bytes (generate: openssl rand -base64 32).");
		}
		this.key = new SecretKeySpec(bytes, "AES");
	}

	public boolean isConfigured() {
		return key != null;
	}

	public String encrypt(String plain) {
		requireKey();
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not encrypt the authenticator secret", ex);
		}
	}

	public String decrypt(String stored) {
		requireKey();
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("Unknown authenticator secret format");
		}
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
			return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			// Wrong key (it was changed) or tampered data.
			throw new IllegalStateException("Could not decrypt the authenticator secret: MFA_ENCRYPTION_KEY changed?", ex);
		}
	}

	private void requireKey() {
		if (key == null) {
			throw new IllegalStateException("MFA_ENCRYPTION_KEY is not set");
		}
	}
}
