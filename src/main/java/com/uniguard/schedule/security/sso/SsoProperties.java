package com.uniguard.schedule.security.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in with Google / Microsoft. A provider is offered only when its client id and secret are set.
 *
 * @param callbackBaseUrl where the browser comes back after signing in, without the path
 *                        (normally the frontend address, which forwards /api to the backend).
 *                        The full redirect URI is {@code <callbackBaseUrl>/api/auth/sso/<provider>/callback}
 *                        and must be registered exactly like that with Google / Microsoft.
 */
@ConfigurationProperties(prefix = "app.sso")
public record SsoProperties(String callbackBaseUrl, Google google, Microsoft microsoft) {

	public record Google(String clientId, String clientSecret, String authorizationUri, String tokenUri, String jwksUri, String issuer) {
		public boolean configured() {
			return notBlank(clientId) && notBlank(clientSecret);
		}
	}

	/**
	 * @param tenant    "organizations" (any work/school account, recommended), "common" (also personal
	 *                  Microsoft accounts) or one organisation's tenant id (only that organisation)
	 * @param authority normally https://login.microsoftonline.com
	 */
	public record Microsoft(String clientId, String clientSecret, String tenant, String authority) {
		public boolean configured() {
			return notBlank(clientId) && notBlank(clientSecret);
		}
	}

	static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
