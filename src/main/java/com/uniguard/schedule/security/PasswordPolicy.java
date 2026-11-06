package com.uniguard.schedule.security;

import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.exception.ValidationException;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;

/**
 * One place for password rules, used by every path that sets a password.
 *
 * <p>The 72-byte limit comes from BCrypt: longer passwords make the encoder throw (which used to
 * surface as a server error). Bytes, not characters, are counted — an Arabic letter is 2 bytes.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 10;
	public static final int MAX_BYTES = 72;

	private PasswordPolicy() {
	}

	public static void validate(String password) {
		if (password == null || password.length() < MIN_LENGTH) {
			throw new ValidationException(MessageFormat.format(Messages.AUTH_PASSWORD_TOO_SHORT, MIN_LENGTH));
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			throw new ValidationException(MessageFormat.format(Messages.AUTH_PASSWORD_TOO_LONG, MAX_BYTES));
		}
	}

	/** True when the password could never be a valid one (lets login skip BCrypt for oversized input). */
	public static boolean exceedsMaximum(String password) {
		return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
	}
}
