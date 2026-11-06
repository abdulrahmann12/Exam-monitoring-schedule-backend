package com.uniguard.schedule.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.uniguard.schedule.entity.Settings;

import java.util.Optional;
import java.util.UUID;

public interface SettingsRepository extends JpaRepository<Settings, UUID> {

	/**
	 * {@code SELECT ... FOR UPDATE} on the settings row. Used as the schedule-wide write lock:
	 * every transaction that changes assignments takes it first, so conflict checks and the
	 * writes that follow them can never interleave with another admin's changes.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Settings s where s.id = :id")
	Optional<Settings> lockById(@Param("id") UUID id);
}
