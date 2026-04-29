package schedule.example.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
	List<String> allowedOrigins,
	List<String> allowedOriginPatterns
) {
	public List<String> resolvedAllowedOriginPatterns() {
		if (allowedOriginPatterns != null && !allowedOriginPatterns.isEmpty()) {
			return allowedOriginPatterns;
		}

		if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
			return allowedOrigins;
		}

		return List.of(
			"*"
		);
	}
}