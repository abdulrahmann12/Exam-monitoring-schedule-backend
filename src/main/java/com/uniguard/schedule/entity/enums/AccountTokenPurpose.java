package com.uniguard.schedule.entity.enums;

/** What a one-time email link is for. */
public enum AccountTokenPurpose {
	/** New user chooses their first password. */
	INVITE,
	/** Existing user forgot their password. */
	PASSWORD_RESET,
	/** Signed-in user confirms a new sign-in email (the link goes to the new address). */
	EMAIL_CHANGE
}
