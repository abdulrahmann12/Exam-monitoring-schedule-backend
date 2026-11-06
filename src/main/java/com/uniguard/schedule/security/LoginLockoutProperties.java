package com.uniguard.schedule.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Account lockout after repeated wrong passwords.
 *
 * @param maxFailedAttempts consecutive failures that lock the account (0 disables lockout)
 * @param durationMinutes   how long the account stays locked (default 2880 = 2 days)
 */
@ConfigurationProperties(prefix = "app.security.lockout")
public record LoginLockoutProperties(int maxFailedAttempts, long durationMinutes) {
}
