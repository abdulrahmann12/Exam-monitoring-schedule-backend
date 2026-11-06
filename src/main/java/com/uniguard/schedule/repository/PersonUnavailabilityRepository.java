package com.uniguard.schedule.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.uniguard.schedule.entity.PersonUnavailability;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PersonUnavailabilityRepository extends JpaRepository<PersonUnavailability, UUID> {

	List<PersonUnavailability> findAllByPersonIdOrderByStartDateAsc(UUID personId);

	/** Every leave period (any person) that intersects {@code [from, to]}. */
	@Query("""
		select u from PersonUnavailability u
		where u.startDate <= :to and u.endDate >= :from
		""")
	List<PersonUnavailability> findAllOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
