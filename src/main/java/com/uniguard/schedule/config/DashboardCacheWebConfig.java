package com.uniguard.schedule.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The dashboard numbers are cached for a minute. Any successful change (people, rooms, exam rooms, room
 * allocation, imports, …) clears that cache, so the dashboard never shows numbers from before a save.
 * One place for every write, so a new feature cannot forget it. The cache is small and refills on the next view.
 */
@Configuration
public class DashboardCacheWebConfig implements WebMvcConfigurer {

	private final CacheManager cacheManager;

	public DashboardCacheWebConfig(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
				if (isChange(request.getMethod()) && exception == null && response.getStatus() < 400) {
					Cache dashboard = cacheManager.getCache(CacheConfig.CACHE_DASHBOARD);
					if (dashboard != null) {
						dashboard.clear();
					}
				}
			}
		}).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**");
	}

	static boolean isChange(String method) {
		return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
	}
}
