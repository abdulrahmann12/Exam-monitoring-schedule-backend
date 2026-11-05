package schedule.example.schedule.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import schedule.example.schedule.security.AdminUserDetailsService;
import schedule.example.schedule.security.CsrfProtectionFilter;
import schedule.example.schedule.security.DemoLoginRateLimiterFilter;
import schedule.example.schedule.security.DemoReadOnlyFilter;
import schedule.example.schedule.security.JwtAuthenticationFilter;
import schedule.example.schedule.security.LoginRateLimiterFilter;
import schedule.example.schedule.security.PasswordChangeRequiredFilter;
import schedule.example.schedule.security.RestAccessDeniedHandler;
import schedule.example.schedule.security.RestAuthenticationEntryPoint;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final String ADMIN = "ADMIN";
	private static final String SCHEDULER = "SCHEDULER";
	private static final String DEMO = "DEMO_ADMIN";

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final LoginRateLimiterFilter loginRateLimiterFilter;
	private final DemoLoginRateLimiterFilter demoLoginRateLimiterFilter;
	private final DemoReadOnlyFilter demoReadOnlyFilter;
	private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
	private final RestAccessDeniedHandler restAccessDeniedHandler;
	private final AdminUserDetailsService adminUserDetailsService;
	private final CorsProperties corsProperties;
	private final ObjectMapper objectMapper;

	public SecurityConfig(
		JwtAuthenticationFilter jwtAuthenticationFilter,
		LoginRateLimiterFilter loginRateLimiterFilter,
		DemoLoginRateLimiterFilter demoLoginRateLimiterFilter,
		DemoReadOnlyFilter demoReadOnlyFilter,
		RestAuthenticationEntryPoint restAuthenticationEntryPoint,
		RestAccessDeniedHandler restAccessDeniedHandler,
		AdminUserDetailsService adminUserDetailsService,
		CorsProperties corsProperties,
		ObjectMapper objectMapper
	) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.loginRateLimiterFilter = loginRateLimiterFilter;
		this.demoLoginRateLimiterFilter = demoLoginRateLimiterFilter;
		this.demoReadOnlyFilter = demoReadOnlyFilter;
		this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
		this.restAccessDeniedHandler = restAccessDeniedHandler;
		this.adminUserDetailsService = adminUserDetailsService;
		this.corsProperties = corsProperties;
		this.objectMapper = objectMapper;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, DaoAuthenticationProvider authenticationProvider) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(restAuthenticationEntryPoint)
				.accessDeniedHandler(restAccessDeniedHandler))
			// Who may do what. Rules are checked top to bottom; the first match wins.
			// Default for anything not listed: ADMIN or SCHEDULER (viewers are read-only).
			.authorizeHttpRequests(authorize -> authorize
				// Public: sign in, renew the session from the refresh cookie, and log out
				// (logout must work even when the access token has already expired).
				.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/demo-login", "/api/auth/refresh", "/api/auth/logout").permitAll()
				// Public: "forgot password" and setting a password from an emailed link.
				.requestMatchers(HttpMethod.POST, "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/reset-password/check").permitAll()
				// Own session: every signed-in user.
				.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
				.requestMatchers(HttpMethod.POST, "/api/auth/logout-all", "/api/auth/change-password").authenticated()
				// User management: administrators only (also for reading — it lists emails).
				.requestMatchers("/api/users", "/api/users/**").hasRole(ADMIN)
				// System settings: everyone reads (branding), only administrators change.
				.requestMatchers(HttpMethod.PUT, "/api/settings").hasRole(ADMIN)
				// Reading schedules, people, rooms, … : every signed-in role.
				.requestMatchers(HttpMethod.GET, "/**").authenticated()
				// Auto-assign preview writes nothing; the demo may try it, viewers have no use for it.
				.requestMatchers(HttpMethod.POST, "/api/assignments/generate-preview").hasAnyRole(ADMIN, SCHEDULER, DEMO)
				// Every other change. DEMO is listed so DemoReadOnlyFilter (which runs earlier)
				// answers with its clear "disabled in demo mode" message; it blocks all demo writes.
				.anyRequest().hasAnyRole(ADMIN, SCHEDULER, DEMO))
			.authenticationProvider(authenticationProvider)
			// Anti-forgery check first: requests forged by another website never reach anything else.
			.addFilterBefore(new CsrfProtectionFilter(corsConfigurationSource(), objectMapper), UsernamePasswordAuthenticationFilter.class)
			// Rate limiter runs before JWT filter to reject brute-force before token parsing
			.addFilterBefore(loginRateLimiterFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(demoLoginRateLimiterFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			// Demo sessions are read-only server-side, regardless of what the browser sends.
			.addFilterAfter(demoReadOnlyFilter, JwtAuthenticationFilter.class)
			// Accounts with a temporary password may only change it (or log out).
			.addFilterAfter(new PasswordChangeRequiredFilter(objectMapper), DemoReadOnlyFilter.class);

		return http.build();
	}

	@Bean
	public DaoAuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
		// Use the Spring Security 6.5 constructor to avoid the deprecated setUserDetailsService().
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(adminUserDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return provider;
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/**
	 * CORS configuration.
	 * allowCredentials(true) requires an explicit origin — wildcard is not allowed.
	 * Allowed origins are configured via CORS_ORIGINS env var (default: http://localhost:3000).
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		List<String> origins = corsProperties.resolvedAllowedOriginPatterns();

		// Wildcard + credentials is rejected by browsers and signals a misconfiguration.
		if (origins.contains("*")) {
			throw new IllegalStateException(
				"CORS wildcard '*' cannot be used with allowCredentials=true. " +
				"Set app.cors.allowed-origin-patterns to your exact frontend origin."
			);
		}

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(origins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	/** Prevents Spring Boot from registering JwtAuthenticationFilter in the root servlet chain. */
	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
		FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/** Prevents Spring Boot from registering LoginRateLimiterFilter in the root servlet chain. */
	@Bean
	public FilterRegistrationBean<LoginRateLimiterFilter> rateLimiterFilterRegistration(LoginRateLimiterFilter filter) {
		FilterRegistrationBean<LoginRateLimiterFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/** Prevents Spring Boot from registering DemoReadOnlyFilter in the root servlet chain. */
	@Bean
	public FilterRegistrationBean<DemoReadOnlyFilter> demoReadOnlyFilterRegistration(DemoReadOnlyFilter filter) {
		FilterRegistrationBean<DemoReadOnlyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	/** Prevents Spring Boot from registering DemoLoginRateLimiterFilter in the root servlet chain. */
	@Bean
	public FilterRegistrationBean<DemoLoginRateLimiterFilter> demoRateLimiterFilterRegistration(DemoLoginRateLimiterFilter filter) {
		FilterRegistrationBean<DemoLoginRateLimiterFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}