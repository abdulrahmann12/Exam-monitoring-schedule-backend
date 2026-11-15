package com.uniguard.schedule.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.uniguard.schedule.util.NameNormalizationUtil;

/**
 * A room type of one university, e.g. "Classroom", "Computer Lab", "Drawing Studio" (V18).
 * {@code special} rooms are only given to exams that ask for exactly this type; normal exams never get them.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "room_categories", uniqueConstraints = @UniqueConstraint(
	name = "ux_room_category_university_name", columnNames = {"university_id", "normalized_name"}))
public class RoomCategory {

	public static final int NAME_MAX_LENGTH = 60;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@TenantId
	@Column(name = "university_id", nullable = false, updatable = false)
	private UUID universityId;

	@Setter(lombok.AccessLevel.NONE)
	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Setter(lombok.AccessLevel.NONE)
	@Column(name = "normalized_name", nullable = false, length = NAME_MAX_LENGTH)
	private String normalizedName;

	@Column(nullable = false)
	private boolean special;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public RoomCategory(String name, boolean special, int sortOrder) {
		setName(name);
		this.special = special;
		this.sortOrder = sortOrder;
	}

	/** Sets the name and keeps the comparison key in sync. */
	public void setName(String name) {
		this.name = NameNormalizationUtil.normalizeWhitespace(name);
		this.normalizedName = NameNormalizationUtil.normalizeForComparison(this.name);
	}
}
