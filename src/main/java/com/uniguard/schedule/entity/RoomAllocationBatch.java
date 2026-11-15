package com.uniguard.schedule.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One "Save rooms" of the Room Allocation page (V19): its exam rooms can be undone together. */
@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "room_allocation_batches")
public class RoomAllocationBatch {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@TenantId
	@Column(name = "university_id", nullable = false, updatable = false)
	private UUID universityId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "schedule_group_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private ScheduleGroup scheduleGroup;

	@Column(name = "file_name", length = 255)
	private String fileName;

	@Column(name = "created_by", length = 255)
	private String createdBy;

	@Column(nullable = false)
	private int exams;

	@Column(name = "exam_rooms", nullable = false)
	private int examRooms;

	@Column(name = "students_unplaced", nullable = false)
	private int studentsUnplaced;

	/** Set when the batch was undone (its exam rooms deleted). */
	@Column(name = "undone_at")
	private Instant undoneAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
