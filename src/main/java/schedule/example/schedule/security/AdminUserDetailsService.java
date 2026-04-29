package schedule.example.schedule.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import schedule.example.schedule.repository.AdminUserRepository;

@Service
public class AdminUserDetailsService implements UserDetailsService {

	private final AdminUserRepository adminUserRepository;

	public AdminUserDetailsService(AdminUserRepository adminUserRepository) {
		this.adminUserRepository = adminUserRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String username) {
		return adminUserRepository.findByEmailIgnoreCase(username)
			.map(admin -> User.builder()
				.username(admin.getEmail())
				.password(admin.getPasswordHash())
				.roles("ADMIN")
				.build())
			.orElseThrow(() -> new UsernameNotFoundException("Admin user not found"));
	}
}