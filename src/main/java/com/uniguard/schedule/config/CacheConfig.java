package com.uniguard.schedule.config;

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
 *   <li>{@code dashboard}    — Aggregated dashboard stats per university + exam period. TTL 60 sec.
 *                              Cleared after every successful change ({@link DashboardCacheWebConfig}).</li>
 *   <li>{@code settings}     — Each university's settings. TTL 5 min, cleared when they are saved.</li>
 * </ul>
 * <p>The per-request sign-in check is NOT cached (logout, disabling and switching a university off must
 * work at once); it is one database query instead (AdminUserRepository.findForRequest).
 * <ul>
 * </ul>
 *
 * <p><strong>NOTE:</strong> This is a single-node cache. For multi-instance deployments,
 * replace with a distributed cache (Redis via spring-boot-starter-data-redis).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache name for dashboard summary aggregates (DashboardService). */
    public static final String CACHE_DASHBOARD = "dashboard";

    /** Cache name for application settings (SettingsService). */
    public static final String CACHE_SETTINGS = "settings";

    @Bean
    public CacheManager cacheManager() {
        // Dashboard: TTL 60 seconds, one entry per schedule group
        CaffeineCache dashboardCache = new CaffeineCache(
            CACHE_DASHBOARD,
            Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(500)
                .build()
        );

        // Settings: TTL 5 minutes, one entry per university
        CaffeineCache settingsCache = new CaffeineCache(
            CACHE_SETTINGS,
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .build()
        );

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(dashboardCache, settingsCache));
        return manager;
    }
}



