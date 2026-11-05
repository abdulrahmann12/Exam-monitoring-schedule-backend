package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.dto.user.CreateUserRequest;
import schedule.example.schedule.dto.user.InviteResponse;
import schedule.example.schedule.dto.user.ResetPasswordRequest;
import schedule.example.schedule.dto.user.UpdateUserRequest;
import schedule.example.schedule.dto.user.UserResponse;
import schedule.example.schedule.security.AppUserPrincipal;
import schedule.example.schedule.service.UserManagementService;

import java.util.List;
import java.util.UUID;

/** User management. Administrators only — enforced in {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserManagementService userManagementService;

	public UserController(UserManagementService userManagementService) {
		this.userManagementService = userManagementService;
	}

	@GetMapping
	public List<UserResponse> list() {
		return userManagementService.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public InviteResponse create(@Valid @RequestBody CreateUserRequest request, @AuthenticationPrincipal AppUserPrincipal actor) {
		return userManagementService.create(request, actor.getId());
	}

	/** Sends a new invitation link to a user who has not signed in yet. */
	@PostMapping("/{id}/invite")
	public InviteResponse invite(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal actor) {
		return userManagementService.invite(id, actor.getId());
	}

	@PutMapping("/{id}")
	public UserResponse update(
		@PathVariable UUID id,
		@Valid @RequestBody UpdateUserRequest request,
		@AuthenticationPrincipal AppUserPrincipal actor
	) {
		return userManagementService.update(id, request, actor.getId());
	}

	@PostMapping("/{id}/unlock")
	public UserResponse unlock(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal actor) {
		return userManagementService.unlock(id, actor.getId());
	}

	@PostMapping("/{id}/reset-password")
	public UserResponse resetPassword(
		@PathVariable UUID id,
		@Valid @RequestBody ResetPasswordRequest request,
		@AuthenticationPrincipal AppUserPrincipal actor
	) {
		return userManagementService.resetPassword(id, request, actor.getId());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal actor) {
		userManagementService.delete(id, actor.getId());
	}
}
