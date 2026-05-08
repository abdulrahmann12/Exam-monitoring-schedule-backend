package schedule.example.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap-admin")
public record BootstrapAdminProperties(
	String email,
	String password
) {

}