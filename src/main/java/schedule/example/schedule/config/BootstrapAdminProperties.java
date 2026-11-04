package schedule.example.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap-admin")
public record BootstrapAdminProperties(
	String email,
	String password,
	/** When true, the bootstrap admin's password is reset to {@link #password()} on this startup. */
	boolean resetPassword
) {

}