package schedule.example.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature-flag configuration for demo mode.
 *
 * <p>Controlled via {@code app.demo.enabled} in {@code application.properties}.
 * When {@code false}, the demo login endpoint returns 404 and no demo user is seeded.
 */
@ConfigurationProperties(prefix = "app.demo")
public record DemoProperties(boolean enabled) {

    /** Well-known e-mail address for the demo account. Never changes at runtime. */
    public static final String DEMO_EMAIL = "demo@uniguard.com";
}

