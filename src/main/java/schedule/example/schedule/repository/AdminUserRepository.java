package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import schedule.example.schedule.entity.AdminUser;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

	Optional<AdminUser> findByEmailIgnoreCase(String email);
}