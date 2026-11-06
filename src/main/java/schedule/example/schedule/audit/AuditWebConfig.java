package schedule.example.schedule.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the data-change recorder in front of every API controller. */
@Configuration
public class AuditWebConfig implements WebMvcConfigurer {

	private final DataChangeAuditInterceptor interceptor;

	public AuditWebConfig(DataChangeAuditInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(interceptor).addPathPatterns("/api/**");
	}
}
