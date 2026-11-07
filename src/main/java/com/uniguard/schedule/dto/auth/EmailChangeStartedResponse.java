package com.uniguard.schedule.dto.auth;

import java.time.Instant;

/**
 * A confirmation link was sent to the new address; nothing changes until it is opened.
 *
 * @param devLink only in local development without email (app.account.log-links=true): the link itself,
 *                so the change can be tried out. Always null in production.
 */
public record EmailChangeStartedResponse(String newEmail, Instant expiresAt, boolean emailSent, String devLink) {
}
