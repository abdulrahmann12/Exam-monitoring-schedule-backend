package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import schedule.example.schedule.entity.University;

import java.util.UUID;

public interface UniversityRepository extends JpaRepository<University, UUID> {

	boolean existsBySlugIgnoreCase(String slug);
}
