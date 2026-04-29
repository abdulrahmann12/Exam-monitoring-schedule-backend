package schedule.example.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import schedule.example.schedule.entity.Settings;

import java.util.UUID;

public interface SettingsRepository extends JpaRepository<Settings, UUID> {
}