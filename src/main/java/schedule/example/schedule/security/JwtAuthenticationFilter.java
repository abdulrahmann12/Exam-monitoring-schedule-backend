package schedule.example.schedule.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String TOKEN_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;
	private final AdminUserDetailsService adminUserDetailsService;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;

	public JwtAuthenticationFilter(
		JwtTokenProvider jwtTokenProvider,
		AdminUserDetailsService adminUserDetailsService,
		RestAuthenticationEntryPoint authenticationEntryPoint
	) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.adminUserDetailsService = adminUserDetailsService;
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

		if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith(TOKEN_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = authorizationHeader.substring(TOKEN_PREFIX.length());

		try {
			if (SecurityContextHolder.getContext().getAuthentication() == null) {
				String subject = jwtTokenProvider.extractSubject(token);
				UserDetails userDetails = adminUserDetailsService.loadUserByUsername(subject);

				if (jwtTokenProvider.isValid(token, userDetails)) {
					UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
						userDetails,
						null,
						userDetails.getAuthorities()
					);
					authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
					SecurityContextHolder.getContext().setAuthentication(authentication);
				}
			}

			filterChain.doFilter(request, response);
		} catch (JwtException | IllegalArgumentException | UsernameNotFoundException ex) {
			SecurityContextHolder.clearContext();
			authenticationEntryPoint.commence(
				request,
				response,
				new BadCredentialsException("Invalid authentication token", ex)
			);
		}
	}
}