package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.dto.auth.AuthResponse;
import schedule.example.schedule.dto.auth.LoginRequest;
import schedule.example.schedule.dto.auth.MeResponse;
import schedule.example.schedule.service.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	/** Resolves the current authenticated session — used by the frontend on startup. */
	@GetMapping("/me")
	public MeResponse me(@AuthenticationPrincipal UserDetails userDetails) {
		return new MeResponse(userDetails.getUsername(), userDetails.getAuthorities()
				.stream().map(a -> a.getAuthority()).toList());
	}
}