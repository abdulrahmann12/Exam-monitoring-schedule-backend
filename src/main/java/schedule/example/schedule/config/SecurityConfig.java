package schedule.example.schedule.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
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
import schedule.example.schedule.security.DemoLoginRateLimiterFilter;
import schedule.example.schedule.security.JwtAuthenticationFilter;
import schedule.example.schedule.security.LoginRateLimiterFilter;
import schedule.example.schedule.security.RestAccessDeniedHandler;
import schedule.example.schedule.security.RestAuthenticationEntryPoint;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final LoginRateLimiterFilter loginRateLimiterFilter;
	private final DemoLoginRateLimiterFilter demoLoginRateLimiterFilter;
	private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
	private final RestAccessDeniedHandler restAccessDeniedHandler;
	private final AdminUserDetailsService adminUserDetailsService;
	private final CorsProperties corsProperties;

	public SecurityConfig(
		JwtAuthenticationFilter jwtAuthenticationFilter,
		LoginRateLimiterFilter loginRateLimiterFilter,
		DemoLoginRateLimiterFilter demoLoginRateLimiterFilter,
		RestAuthenticationEntryPoint restAuthenticationEntryPoint,
		RestAccessDeniedHandler restAccessDeniedHandler,
		AdminUserDetailsService adminUserDetailsService,
		CorsProperties corsProperties
	) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.loginRateLimiterFilter = loginRateLimiterFilter;
		this.demoLoginRateLimiterFilter = demoLoginRateLimiterFilter;
		this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
		this.restAccessDeniedHandler = restAccessDeniedHandler;
		this.adminUserDetailsService = adminUserDetailsService;
		this.corsProperties = corsProperties;
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
			.authorizeHttpRequests(authorize -> authorize
				// Public auth endpoints — login + demo-login + deployment diagnostic
				.requestMatchers("/api/auth/login", "/api/auth/demo-login", "/api/auth/admin-check").permitAll()
				// Actuator health & info are publicly readable; all others require auth
				.requestMatchers("/actuator/health", "/actuator/info").permitAll()
				.requestMatchers("/actuator/**").hasRole("ADMIN")
				// Swagger UI — public in dev; set SWAGGER_ENABLED=false in production
				.requestMatchers(
					"/swagger-ui.html",
					"/swagger-ui/**",
					"/v3/api-docs/**"
				).permitAll()
				.anyRequest().authenticated())
			.authenticationProvider(authenticationProvider)
			// Rate limiter runs before JWT filter to reject brute-force before token parsing
			.addFilterBefore(loginRateLimiterFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(demoLoginRateLimiterFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

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
		configuration.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
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

	/** Prevents Spring Boot from registering DemoLoginRateLimiterFilter in the root servlet chain. */
	@Bean
	public FilterRegistrationBean<DemoLoginRateLimiterFilter> demoRateLimiterFilterRegistration(DemoLoginRateLimiterFilter filter) {
		FilterRegistrationBean<DemoLoginRateLimiterFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}