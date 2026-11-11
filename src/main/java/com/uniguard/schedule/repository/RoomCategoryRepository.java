package com.uniguard.schedule.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uniguard.schedule.entity.RoomCategory;

/** The current university's room types (tenant-filtered). */
public interface RoomCategoryRepository extends JpaRepository<RoomCategory, UUID> {

	List<RoomCategory> findAllByOrderBySortOrderAscNameAsc();

	Optional<RoomCategory> findByNormalizedName(String normalizedName);

	boolean existsBy();
}
