package com.uniguard.schedule.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uniguard.schedule.entity.RoomAllocationBatch;

/** Saved room allocations of the current university (tenant-filtered). */
public interface RoomAllocationBatchRepository extends JpaRepository<RoomAllocationBatch, UUID> {

	List<RoomAllocationBatch> findAllByScheduleGroupIdOrderByCreatedAtDesc(UUID scheduleGroupId);
}
