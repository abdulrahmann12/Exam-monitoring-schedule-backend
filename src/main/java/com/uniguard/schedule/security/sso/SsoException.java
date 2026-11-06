package com.uniguard.schedule.security.sso;

/**
 * A sign-in with Google / Microsoft that cannot continue. Only the short {@link #code()} reaches the
 * browser (as ?sso_error=code); details stay in the server log, never in the page.
 */
public class SsoException extends RuntimeException {

	public static final String CANCELLED = "cancelled";
	public static final String EXPIRED = "expired";
	public static final String FAILED = "failed";
	public static final String NOT_LINKED = "not_linked";
	public static final String IN_USE = "in_use";
	public static final String ALREADY_LINKED = "already_linked";
	public static final String DISABLED = "disabled";
	public static final String LOCKED = "locked";
	public static final String PAUSED = "paused";
	public static final String UNAVAILABLE = "unavailable";
	public static final String BUSY = "busy";

	private final String code;

	public SsoException(String code, String detail) {
		super(detail);
		this.code = code;
	}

	public SsoException(String code, String detail, Throwable cause) {
		super(detail, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
