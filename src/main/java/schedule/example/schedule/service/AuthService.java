package schedule.example.schedule.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import schedule.example.schedule.config.MessageResolver;
import schedule.example.schedule.dto.auth.AuthResponse;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.security.JwtTokenProvider;

@Service
public class AuthService {

	private final AuthenticationManager authenticationManager;
	private final JwtTokenProvider jwtTokenProvider;
	private final MessageResolver messageResolver;

	public AuthService(
		AuthenticationManager authenticationManager,
		JwtTokenProvider jwtTokenProvider,
		MessageResolver messageResolver
	) {
		this.authenticationManager = authenticationManager;
		this.jwtTokenProvider = jwtTokenProvider;
		this.messageResolver = messageResolver;
	}

	public AuthResponse login(LoginRequest request) {
		try {
			Authentication authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password())
			);

			JwtTokenProvider.TokenDetails tokenDetails = jwtTokenProvider.generateToken(authentication.getName());

			return new AuthResponse(tokenDetails.token(), "Bearer", tokenDetails.expiresAt(), authentication.getName());
		} catch (AuthenticationException ex) {
			throw new BadCredentialsException(messageResolver.get("auth.invalid-credentials"), ex);
		}
	}
}