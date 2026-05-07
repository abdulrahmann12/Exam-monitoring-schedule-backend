package schedule.example.schedule.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed in-memory cache configuration.
 *
 * <p>Uses {@link SimpleCacheManager} with individually configured {@link CaffeineCache} instances
 * so each named cache can have its own TTL and maximum size.
 *
 * <p>Named caches:
 * <ul>
 *   <li>{@code userDetails}  — Admin user lookup results. TTL 5 min, max 50 entries.
 *                              Eliminates a DB round-trip on every authenticated request.</li>
 *   <li>{@code dashboard}    — Aggregated dashboard stats. TTL 60 sec, max 1 entry.
 *                              Prevents 4 serial COUNT queries on every dashboard load.</li>
 * </ul>
 *
 * <p><strong>NOTE:</strong> This is a single-node cache. For multi-instance deployments,
 * replace with a distributed cache (Redis via spring-boot-starter-data-redis).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache name for admin user details (AdminUserDetailsService). */
    public static final String CACHE_USER_DETAILS = "userDetails";

    /** Cache name for dashboard summary aggregates (DashboardService). */
    public static final String CACHE_DASHBOARD = "dashboard";

    @Bean
    public CacheManager cacheManager() {
        // User details: TTL 5 minutes, small max size (admin users are few)
        CaffeineCache userDetailsCache = new CaffeineCache(
            CACHE_USER_DETAILS,
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(50)
                .build()
        );

        // Dashboard: TTL 60 seconds, single-entry (one system-wide summary)
        CaffeineCache dashboardCache = new CaffeineCache(
            CACHE_DASHBOARD,
            Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(1)
                .build()
        );

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(userDetailsCache, dashboardCache));
        return manager;
    }
}



