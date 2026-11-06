package com.uniguard.schedule.security.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * Time-based one-time passwords (RFC 6238), as used by Google Authenticator, Microsoft
 * Authenticator, Authy, 1Password and others: HMAC-SHA1, 30-second steps, 6 digits.
 */
public final class Totp {

	public static final int DIGITS = 6;
	public static final long STEP_SECONDS = 30;
	/** Codes from one step before or after are accepted too (phone clock a little off). */
	static final int WINDOW = 1;
	private static final int SECRET_BYTES = 20; // 160 bits, the RFC 4226 recommendation
	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	/** A new random secret, Base32-encoded (what authenticator apps expect). */
	public static String newSecret() {
		byte[] bytes = new byte[SECRET_BYTES];
		RANDOM.nextBytes(bytes);
		return base32Encode(bytes);
	}

	public static long stepAt(Instant time) {
		return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
	}

	/** The code for one time step. */
	public static String codeAt(String base32Secret, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
				| ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
			return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % 1_000_000);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA1 is not available", ex);
		}
	}

	/**
	 * Checks a code and returns the time step it matched, or -1. A step at or before
	 * {@code lastUsedStep} is refused, so a code that was already used cannot be used again.
	 */
	public static long verify(String base32Secret, String code, Instant now, Long lastUsedStep) {
		String digits = code == null ? "" : code.replaceAll("\\s", "");
		if (!digits.matches("\\d{" + DIGITS + "}")) {
			return -1;
		}
		long current = stepAt(now);
		long matched = -1;
		for (long step = current - WINDOW; step <= current + WINDOW; step++) {
			// Check every step (no early exit): the time taken does not reveal which one matched.
			boolean equal = MessageDigest.isEqual(codeAt(base32Secret, step).getBytes(), digits.getBytes());
			if (equal && (lastUsedStep == null || step > lastUsedStep) && matched < 0) {
				matched = step;
			}
		}
		return matched;
	}

	/** otpauth:// link for the QR code. Label and issuer are URL-encoded. */
	public static String otpauthUri(String issuer, String account, String base32Secret) {
		String label = encode(issuer) + ":" + encode(account);
		return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(issuer)
			+ "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
	}

	private static String encode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
	}

	static String base32Encode(byte[] data) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}

	static byte[] base32Decode(String text) {
		String clean = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
		ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (char c : clean.toCharArray()) {
			int value = BASE32.indexOf(c);
			if (value < 0) {
				throw new IllegalArgumentException("Not a Base32 secret");
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.put((byte) ((buffer >> (bits - 8)) & 0xff));
				bits -= 8;
			}
		}
		return java.util.Arrays.copyOf(out.array(), out.position());
	}
}
