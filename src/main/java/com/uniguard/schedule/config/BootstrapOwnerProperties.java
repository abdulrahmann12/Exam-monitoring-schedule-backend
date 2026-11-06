package com.uniguard.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform owner (super-admin) account, from {@code BOOTSTRAP_OWNER_EMAIL} / {@code BOOTSTRAP_OWNER_PASSWORD}.
 * Optional: without them no owner is created.
 */
@ConfigurationProperties(prefix = "app.bootstrap-owner")
public record BootstrapOwnerProperties(
	String email,
	String password,
	/** When true, the owner's password is reset to {@link #password()} on this startup. */
	boolean resetPassword
) {
}
