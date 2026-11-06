package schedule.example.schedule.security.sso;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import schedule.example.schedule.audit.AuditCategory;
import schedule.example.schedule.audit.AuditEntry;
import schedule.example.schedule.audit.AuditService;
import schedule.example.schedule.config.AccountLinkProperties;
import schedule.example.schedule.config.Messages;
import schedule.example.schedule.entity.AdminUser;
import schedule.example.schedule.entity.SsoState;
import schedule.example.schedule.entity.University;
import schedule.example.schedule.entity.UserIdentity;
import schedule.example.schedule.entity.enums.AdminRole;
import schedule.example.schedule.exception.BusinessRuleViolationException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.repository.AdminUserRepository;
import schedule.example.schedule.repository.SsoStateRepository;
import schedule.example.schedule.repository.UniversityRepository;
import schedule.example.schedule.repository.UserIdentityRepository;
import schedule.example.schedule.security.AdminUserDetailsService;
import schedule.example.schedule.security.PasswordPolicy;
import schedule.example.schedule.security.SecureTokens;
import schedule.example.schedule.security.SlidingWindowRateLimiter;
import schedule.example.schedule.service.AuthService;
import schedule.example.schedule.service.LoginAttemptService;
import schedule.example.schedule.service.TwoFactorService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sign-in with Google or Microsoft (OpenID Connect, authorization code flow with PKCE).
 *
 * <p>Rules:
 * <ul>
 *   <li>No self-registration: only existing accounts can sign in this way.</li>
 *   <li>An account is found by the provider's stable subject once connected. The first time, it is
 *       found by email only where the email is trustworthy: Google with {@code email_verified}, or
 *       Microsoft from the university's own organisation (its tenant id is set by the university's
 *       administrator). Otherwise the user connects the account from Settings while signed in.</li>
 *   <li>The state is bound to the browser (cookie) and single use; nonce and PKCE protect the code.</li>
 *   <li>Disabled, locked or paused accounts are refused; two-step sign-in still asks for the code.</li>
 * </ul>
 */
@Service
public class SsoService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SsoService.class);
	public static final String GOOGLE = "google";
	public static final String MICROSOFT = "microsoft";
	static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
	private static final Pattern TENANT_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	private static final List<String> RETURN_PATHS = List.of("/settings", "/owner");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SsoProperties properties;
	private final AccountLinkProperties links;
	private final OidcHttpClient http;
	private final IdTokenVerifier verifier;
	private final SsoStateRepository states;
	private final UserIdentityRepository identities;
	private final AdminUserRepository users;
	private final UniversityRepository universities;
	private final AuthService authService;
	private final TwoFactorService twoFactorService;
	private final LoginAttemptService loginAttemptService;
	private final AuditService auditService;
	private final PasswordEncoder passwordEncoder;
	private final TransactionTemplate transaction;
	private final SlidingWindowRateLimiter startLimiter = new SlidingWindowRateLimiter(20, Duration.ofMinutes(1));

	public SsoService(
		SsoProperties properties,
		AccountLinkProperties links,
		OidcHttpClient http,
		IdTokenVerifier verifier,
		SsoStateRepository states,
		UserIdentityRepository identities,
		AdminUserRepository users,
		UniversityRepository universities,
		AuthService authService,
		TwoFactorService twoFactorService,
		LoginAttemptService loginAttemptService,
		AuditService auditService,
		PasswordEncoder passwordEncoder,
		PlatformTransactionManager transactionManager
	) {
		this.properties = properties;
		this.links = links;
		this.http = http;
		this.verifier = verifier;
		this.states = states;
		this.identities = identities;
		this.users = users;
		this.universities = universities;
		this.authService = authService;
		this.twoFactorService = twoFactorService;
		this.loginAttemptService = loginAttemptService;
		this.auditService = auditService;
		this.passwordEncoder = passwordEncoder;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	// ─────────────────────────── Providers ───────────────────────────

	public record ProviderInfo(String id, String name) {
	}

	private record Provider(String id, String name, String clientId, String clientSecret, String authorizationUri, String tokenUri, String jwksUri) {
	}

	/** Providers this server is set up for (shown as buttons on the sign-in page). */
	public List<ProviderInfo> enabledProviders() {
		List<ProviderInfo> list = new ArrayList<>();
		if (properties.microsoft() != null && properties.microsoft().configured()) {
			list.add(new ProviderInfo(MICROSOFT, "Microsoft"));
		}
		if (properties.google() != null && properties.google().configured()) {
			list.add(new ProviderInfo(GOOGLE, "Google"));
		}
		return list;
	}

	private Provider provider(String id) {
		if (GOOGLE.equals(id) && properties.google() != null && properties.google().configured()) {
			SsoProperties.Google g = properties.google();
			return new Provider(GOOGLE, "Google", g.clientId(), g.clientSecret(), g.authorizationUri(), g.tokenUri(), g.jwksUri());
		}
		if (MICROSOFT.equals(id) && properties.microsoft() != null && properties.microsoft().configured()) {
			SsoProperties.Microsoft m = properties.microsoft();
			String base = trimSlash(m.authority()) + "/" + m.tenant();
			return new Provider(MICROSOFT, "Microsoft", m.clientId(), m.clientSecret(),
				base + "/oauth2/v2.0/authorize", base + "/oauth2/v2.0/token", base + "/discovery/v2.0/keys");
		}
		throw new SsoException(SsoException.UNAVAILABLE, "Provider '" + id + "' is not set up on this server");
	}

	// ─────────────────────────── Start ───────────────────────────

	/** Where to send the browser, and the state to keep in the browser-binding cookie. */
	public record Started(String authorizationUrl, String state, Instant expiresAt) {
	}

	/** Sign-in from the sign-in page (nobody signed in). */
	public Started startLogin(String providerId, String clientIp) {
		if (!startLimiter.tryAcquire(clientIp == null ? "unknown" : clientIp)) {
			throw new SsoException(SsoException.BUSY, "Too many SSO starts from " + clientIp);
		}
		return start(provider(providerId), SsoState.LOGIN, null, null);
	}

	/** Connect a Google / Microsoft account to the signed-in account (password confirmed first). */
	public Started startLink(String providerId, UUID userId, String currentPassword, String returnPath) {
		Provider provider = provider(providerId);
		AdminUser user = users.findById(userId).orElseThrow(() -> new NotFoundException(Messages.USER_NOT_FOUND));
		if (user.getRole() == AdminRole.DEMO_ADMIN) {
			throw new BusinessRuleViolationException(Messages.DEMO_OPERATION_NOT_ALLOWED);
		}
		if (currentPassword == null || PasswordPolicy.exceedsMaximum(currentPassword)
			|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new ValidationException(Messages.AUTH_CURRENT_PASSWORD_WRONG);
		}
		if (identities.findByUserIdAndProvider(userId, provider.id()).isPresent()) {
			throw new BusinessRuleViolationException(Messages.SSO_ALREADY_CONNECTED);
		}
		String path = returnPath != null && RETURN_PATHS.contains(returnPath) ? returnPath : "/settings";
		return start(provider, SsoState.LINK, userId, path);
	}

	private Started start(Provider provider, String purpose, UUID userId, String returnPath) {
		String state = SecureTokens.newToken();
		String nonce = SecureTokens.newToken();
		String verifierValue = randomUrlSafe(48);
		Instant now = Instant.now();
		SsoState row = new SsoState();
		row.setStateHash(SecureTokens.sha256(state));
		row.setProvider(provider.id());
		row.setPurpose(purpose);
		row.setUserId(userId);
		row.setNonce(nonce);
		row.setCodeVerifier(verifierValue);
		row.setReturnPath(returnPath);
		row.setCreatedAt(now);
		row.setExpiresAt(now.plus(STATE_LIFETIME));
		transaction.executeWithoutResult(status -> states.save(row));

		Map<String, String> params = new LinkedHashMap<>();
		params.put("response_type", "code");
		params.put("client_id", provider.clientId());
		params.put("redirect_uri", redirectUri(provider.id()));
		params.put("scope", "openid email profile");
		params.put("state", state);
		params.put("nonce", nonce);
		params.put("code_challenge", challenge(verifierValue));
		params.put("code_challenge_method", "S256");
		params.put("prompt", "select_account");
		StringBuilder url = new StringBuilder(provider.authorizationUri()).append('?');
		params.forEach((key, value) -> url.append(encode(key)).append('=').append(encode(value)).append('&'));
		url.setLength(url.length() - 1);
		return new Started(url.toString(), state, row.getExpiresAt());
	}

	// ─────────────────────────── Callback ───────────────────────────

	/**
	 * What happened, for the controller: where to send the browser (a fixed app path, never taken from
	 * the request), and the session or two-step challenge to put into cookies.
	 */
	public record Outcome(String path, AuthService.SignedIn signedIn, TwoFactorService.Challenge challenge) {
	}

	public Outcome callback(String providerId, String code, String state, String cookieState, String providerError, HttpServletRequest request) {
		SsoState started = consumeState(providerId, state, cookieState);
		boolean linking = SsoState.LINK.equals(started.getPurpose());
		try {
			if (providerError != null) {
				throw new SsoException(SsoException.CANCELLED, "Provider returned error=" + providerError);
			}
			if (code == null || code.isBlank()) {
				throw new SsoException(SsoException.FAILED, "No code in the callback");
			}
			Provider provider = provider(providerId);
			Verified verified = redeem(provider, code, started);
			return linking ? link(started, verified) : login(verified, request);
		} catch (SsoException ex) {
			LOGGER.warn("[SSO] {} with {} failed ({}): {}", linking ? "Connecting" : "Sign-in", providerId, ex.code(), ex.getMessage());
			auditService.record(AuditEntry.success("SSO_FAILED", AuditCategory.SECURITY, "SSO", null,
					(linking ? "Connecting " : "Sign-in with ") + displayName(providerId) + " failed (" + ex.code() + ")")
				.withOutcome(AuditEntry.Outcome.FAILURE));
			return new Outcome(errorPath(started, ex.code()), null, null);
		}
	}

	/** Errors before a state is known (missing / forged / expired) go to the sign-in page. */
	public String errorPathWithoutState(String code) {
		return "/login?sso_error=" + code;
	}

	private SsoState consumeState(String providerId, String state, String cookieState) {
		if (state == null || cookieState == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), cookieState.getBytes(StandardCharsets.UTF_8))) {
			// No cookie or a different one: the callback was not started in this browser (login CSRF).
			throw new SsoException(SsoException.EXPIRED, "State missing or not bound to this browser");
		}
		SsoState found = transaction.execute(status -> states.lockByStateHash(SecureTokens.sha256(state)).map(row -> {
			states.delete(row); // single use
			return row;
		}).orElse(null));
		if (found == null || found.getExpiresAt().isBefore(Instant.now()) || !found.getProvider().equals(providerId)) {
			throw new SsoException(SsoException.EXPIRED, "State unknown, expired or for another provider");
		}
		return found;
	}

	private record Verified(String provider, String subject, String email, boolean emailTrusted, String tenantId) {
	}

	private Verified redeem(Provider provider, String code, SsoState started) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", redirectUri(provider.id()));
		form.put("client_id", provider.clientId());
		form.put("client_secret", provider.clientSecret());
		form.put("code_verifier", started.getCodeVerifier());
		Object idToken = http.postForm(provider.tokenUri(), form).get("id_token");
		Claims claims = verifier.verify(idToken == null ? null : idToken.toString(), provider.jwksUri(), provider.clientId());

		if (!started.getNonce().equals(claims.get("nonce", String.class))) {
			throw new SsoException(SsoException.FAILED, "Nonce does not match");
		}
		String email = lower(claims.get("email", String.class));
		if (GOOGLE.equals(provider.id())) {
			String issuer = claims.getIssuer();
			String expected = properties.google().issuer();
			if (issuer == null || !(issuer.equals(expected) || ("https://" + issuer).equals(expected))) {
				throw new SsoException(SsoException.FAILED, "Unexpected Google issuer " + issuer);
			}
			boolean verifiedEmail = Boolean.TRUE.equals(claims.get("email_verified", Boolean.class))
				|| "true".equals(String.valueOf(claims.get("email_verified")));
			return new Verified(GOOGLE, claims.getSubject(), email, verifiedEmail && email != null, null);
		}
		String tid = lower(claims.get("tid", String.class));
		String expectedIssuer = trimSlash(properties.microsoft().authority()) + "/" + tid + "/v2.0";
		if (tid == null || !expectedIssuer.equals(claims.getIssuer())) {
			throw new SsoException(SsoException.FAILED, "Unexpected Microsoft issuer " + claims.getIssuer());
		}
		String configuredTenant = lower(properties.microsoft().tenant());
		if (configuredTenant != null && TENANT_ID.matcher(configuredTenant).matches() && !configuredTenant.equals(tid)) {
			throw new SsoException(SsoException.FAILED, "Microsoft account from another organisation");
		}
		String oid = claims.get("oid", String.class);
		String subject = tid + "/" + (oid != null ? lower(oid) : claims.getSubject());
		if (email == null) {
			email = lower(claims.get("preferred_username", String.class));
		}
		// Microsoft email addresses are trusted only for a university that names this organisation (see login()).
		return new Verified(MICROSOFT, subject, email, false, tid);
	}

	private Outcome login(Verified verified, HttpServletRequest request) {
		AdminUser user = transaction.execute(status -> findOrConnect(verified));
		if (user == null) {
			throw new SsoException(SsoException.NOT_LINKED, "No connected account for this " + verified.provider() + " identity");
		}
		if (user.getRole() == AdminRole.DEMO_ADMIN) {
			throw new SsoException(SsoException.NOT_LINKED, "The demo account cannot use SSO");
		}
		if (!user.isEnabled()) {
			throw new SsoException(SsoException.DISABLED, "Account disabled");
		}
		if (loginAttemptService.isLocked(user)) {
			throw new SsoException(SsoException.LOCKED, "Account locked");
		}
		if (!AdminUserDetailsService.universityActive(user, universities)) {
			throw new SsoException(SsoException.PAUSED, "University switched off");
		}
		if (user.isTotpEnabled()) {
			TwoFactorService.Challenge challenge = twoFactorService.beginChallenge(user);
			audit("SSO_PASSWORD_STEP", AuditCategory.AUTH, user, "Signed in with " + displayName(verified.provider()) + ", waiting for the two-step code");
			return new Outcome("/login?step=two-factor", null, challenge);
		}
		loginAttemptService.recordSuccess(user.getId());
		AuthService.SignedIn signedIn = authService.openSession(user, request);
		audit("LOGIN_SUCCEEDED", AuditCategory.AUTH, user, "Signed in with " + displayName(verified.provider()));
		return new Outcome("/dashboard", signedIn, null);
	}

	/** Connected identity first; otherwise a trustworthy email match, which is then connected for next time. */
	private AdminUser findOrConnect(Verified verified) {
		UserIdentity known = identities.findByProviderAndSubject(verified.provider(), verified.subject()).orElse(null);
		if (known != null) {
			known.setLastUsedAt(Instant.now());
			known.setEmail(verified.email());
			identities.save(known);
			return users.findById(known.getUserId()).orElse(null);
		}
		if (verified.email() == null) {
			return null;
		}
		AdminUser byEmail = users.findByEmailIgnoreCase(verified.email()).orElse(null);
		if (byEmail == null || byEmail.getRole() == AdminRole.DEMO_ADMIN) {
			return null;
		}
		boolean trusted = verified.emailTrusted()
			|| (MICROSOFT.equals(verified.provider()) && byEmail.getUniversityId() != null
				&& universities.findById(byEmail.getUniversityId()).map(University::getMicrosoftTenantId)
					.map(tenant -> tenant.equalsIgnoreCase(verified.tenantId())).orElse(false));
		if (!trusted || identities.findByUserIdAndProvider(byEmail.getId(), verified.provider()).isPresent()) {
			// Not trustworthy, or the account is already connected to a different identity of this provider.
			return null;
		}
		connect(byEmail, verified);
		audit("SSO_LINKED", AuditCategory.SECURITY, byEmail, "Connected " + displayName(verified.provider()) + " account " + verified.email()
			+ " automatically at first sign-in (" + (verified.emailTrusted() ? "verified email" : "university's Microsoft organisation") + ")");
		return byEmail;
	}

	private Outcome link(SsoState started, Verified verified) {
		String back = started.getReturnPath() == null ? "/settings" : started.getReturnPath();
		transaction.executeWithoutResult(status -> {
			AdminUser user = users.findById(started.getUserId()).orElseThrow(() -> new SsoException(SsoException.EXPIRED, "Account gone"));
			if (!user.isEnabled()) {
				throw new SsoException(SsoException.DISABLED, "Account disabled");
			}
			UserIdentity existing = identities.findByProviderAndSubject(verified.provider(), verified.subject()).orElse(null);
			if (existing != null && !existing.getUserId().equals(user.getId())) {
				throw new SsoException(SsoException.IN_USE, "Identity already connected to another account");
			}
			if (existing == null) {
				if (identities.findByUserIdAndProvider(user.getId(), verified.provider()).isPresent()) {
					throw new SsoException(SsoException.ALREADY_LINKED, "Account already has a " + verified.provider() + " identity");
				}
				connect(user, verified);
			}
			audit("SSO_LINKED", AuditCategory.SECURITY, user, "Connected " + displayName(verified.provider()) + " account "
				+ (verified.email() == null ? "" : verified.email()));
		});
		return new Outcome(back + "?sso=linked", null, null);
	}

	private void connect(AdminUser user, Verified verified) {
		UserIdentity identity = new UserIdentity();
		identity.setUserId(user.getId());
		identity.setProvider(verified.provider());
		identity.setSubject(verified.subject());
		identity.setEmail(verified.email());
		identity.setCreatedAt(Instant.now());
		identity.setLastUsedAt(Instant.now());
		identities.save(identity);
	}

	// ─────────────────────────── Own connections ───────────────────────────

	public record ConnectedIdentity(UUID id, String provider, String name, String email, Instant connectedAt, Instant lastUsedAt) {
	}

	@Transactional(readOnly = true)
	public List<ConnectedIdentity> connections(UUID userId) {
		return identities.findByUserIdOrderByProviderAsc(userId).stream()
			.map(identity -> new ConnectedIdentity(identity.getId(), identity.getProvider(), displayName(identity.getProvider()),
				identity.getEmail(), identity.getCreatedAt(), identity.getLastUsedAt()))
			.toList();
	}

	@Transactional
	public void disconnect(UUID userId, UUID identityId) {
		UserIdentity identity = identities.findById(identityId)
			.filter(found -> found.getUserId().equals(userId)) // someone else's: answer as if missing
			.orElseThrow(() -> new NotFoundException(Messages.SSO_CONNECTION_NOT_FOUND));
		identities.delete(identity);
		users.findById(userId).ifPresent(user -> audit("SSO_UNLINKED", AuditCategory.SECURITY, user,
			"Disconnected " + displayName(identity.getProvider()) + " account " + (identity.getEmail() == null ? "" : identity.getEmail())));
	}

	// ─────────────────────────── University setting ───────────────────────────

	public record UniversitySso(String microsoftTenantId) {
	}

	@Transactional(readOnly = true)
	public UniversitySso universitySettings(UUID universityId) {
		return new UniversitySso(requireUniversity(universityId).getMicrosoftTenantId());
	}

	/** The university's own Microsoft organisation: its members may then sign in with Microsoft by email. */
	@Transactional
	public UniversitySso updateUniversitySettings(UUID universityId, String microsoftTenantId) {
		University university = requireUniversity(universityId);
		String tenant = lower(microsoftTenantId == null ? null : microsoftTenantId.trim());
		if (tenant != null && tenant.isEmpty()) {
			tenant = null;
		}
		if (tenant != null && !TENANT_ID.matcher(tenant).matches()) {
			throw new ValidationException(Messages.SSO_TENANT_INVALID);
		}
		String before = university.getMicrosoftTenantId();
		university.setMicrosoftTenantId(tenant);
		universities.save(university);
		if (!java.util.Objects.equals(before, tenant)) {
			auditService.recordAfterCommit(AuditEntry.success("SSO_TENANT_CHANGED", AuditCategory.SECURITY, "UNIVERSITY", universityId,
				"Microsoft organisation for sign-in " + (before == null ? "-" : before) + " → " + (tenant == null ? "-" : tenant)));
		}
		return new UniversitySso(tenant);
	}

	@Scheduled(cron = "0 23 * * * *")
	@Transactional
	public void removeExpiredStates() {
		states.deleteExpired(Instant.now());
	}

	// ─────────────────────────── Helpers ───────────────────────────

	private String errorPath(SsoState started, String code) {
		if (SsoState.LINK.equals(started.getPurpose())) {
			return (started.getReturnPath() == null ? "/settings" : started.getReturnPath()) + "?sso_error=" + code;
		}
		return "/login?sso_error=" + code;
	}

	public String frontendUrl(String path) {
		return trimSlash(links.frontendBaseUrl()) + path;
	}

	private String redirectUri(String providerId) {
		String base = properties.callbackBaseUrl() == null || properties.callbackBaseUrl().isBlank()
			? links.frontendBaseUrl() : properties.callbackBaseUrl();
		return trimSlash(base) + "/api/auth/sso/" + providerId + "/callback";
	}

	private University requireUniversity(UUID universityId) {
		return universities.findById(universityId).orElseThrow(() -> new NotFoundException(Messages.OWNER_UNIVERSITY_NOT_FOUND));
	}

	private void audit(String action, AuditCategory category, AdminUser user, String summary) {
		auditService.record(AuditEntry.success(action, category, "USER", user.getId(), summary).forAccount(user.getEmail()));
	}

	static String displayName(String providerId) {
		return MICROSOFT.equals(providerId) ? "Microsoft" : GOOGLE.equals(providerId) ? "Google" : providerId;
	}

	static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String randomUrlSafe(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static String lower(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}
}
