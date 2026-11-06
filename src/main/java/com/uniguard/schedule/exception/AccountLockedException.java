package com.uniguard.schedule.exception;

/** Sign-in refused because the account is temporarily locked after too many failed attempts. */
public class AccountLockedException extends RuntimeException {

	public AccountLockedException(String message) {
		super(message);
	}
}
