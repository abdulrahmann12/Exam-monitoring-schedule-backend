package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import schedule.example.schedule.entity.UserIdentity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

	Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

	Optional<UserIdentity> findByUserIdAndProvider(UUID userId, String provider);

	List<UserIdentity> findByUserIdOrderByProviderAsc(UUID userId);
}
