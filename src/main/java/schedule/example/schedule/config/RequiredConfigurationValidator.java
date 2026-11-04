package schedule.example.schedule.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fails fast, before any bean is created, when a required secret is missing — with one clear
 * message listing every missing environment variable instead of a confusing downstream error.
 * Registered in {@code META-INF/spring.factories}.
 */
public class RequiredConfigurationValidator implements EnvironmentPostProcessor {

	/** property → environment variable that normally provides it */
	private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

	static {
		REQUIRED.put("spring.datasource.url", "DB_URL");
		REQUIRED.put("spring.datasource.username", "DB_USERNAME");
		REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
		REQUIRED.put("app.jwt.secret", "JWT_SECRET");
	}

	private static final int MIN_JWT_SECRET_LENGTH = 32;

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		List<String> problems = new ArrayList<>();
		REQUIRED.forEach((property, envVar) -> {
			String value;
			try {
				value = environment.getProperty(property);
			} catch (IllegalArgumentException unresolvedPlaceholder) {
				value = null;
			}
			if (value == null || value.isBlank()) {
				problems.add(envVar + " is not set");
			} else if (property.equals("app.jwt.secret") && value.length() < MIN_JWT_SECRET_LENGTH) {
				problems.add(envVar + " must be at least " + MIN_JWT_SECRET_LENGTH + " characters (generate one with: openssl rand -base64 48)");
			}
		});
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Missing required configuration: " + String.join("; ", problems)
				+ ". See backend/.env.example.");
		}
	}
}
