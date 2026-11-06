package schedule.example.schedule.entity;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.entity.enums.HallGender;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
	name = "rooms",
	indexes = {
		@Index(name = "idx_room_name", columnList = "name"),
		@Index(name = "ux_room_university_normalized_name", columnList = "university_id, normalized_name", unique = true),
		@Index(name = "idx_room_type", columnList = "type"),
		@Index(name = "idx_room_active", columnList = "active")
	}
)
public class Room {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	/**
	 * The university this row belongs to. Set automatically from the signed-in user's university;
	 * Hibernate also adds it to every query, so other universities' rows are never visible.
	 */
	@TenantId
	@Column(name = "university_id", nullable = false, updatable = false)
	private UUID universityId;

	@Setter(AccessLevel.NONE)
	@Column(nullable = false, length = 120)
	private String name;

	@Setter(AccessLevel.NONE)
	@Column(name = "normalized_name", length = 120)
	private String normalizedName;

	@Column(nullable = false)
	private int capacity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private RoomType type;

	@Column(nullable = false)
	private int minInvigilators;

	@Builder.Default
	@Column(nullable = false)
	private boolean active = true;

	/** MIXED = anyone may supervise; MALE/FEMALE = only staff of that gender (gender-separated halls). */
	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(name = "hall_gender", nullable = false, length = 8)
	private HallGender hallGender = HallGender.MIXED;

	@PrePersist
	@PreUpdate
	void defaultHallGender() {
		if (hallGender == null) {
			hallGender = HallGender.MIXED;
		}
		// The mapper builds entities without setName(): keep normalizedName in sync here, or
		// duplicate names go unnoticed and name search misses the room.
		setName(name);
	}

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(nullable = false)
	private Instant updatedAt;

	// ── Custom setters ─────────────────────────────────────────────────────

	/** Sets name and auto-updates normalizedName. */
	public void setName(String name) {
		this.name = NameNormalizationUtil.normalizeWhitespace(name);
		this.normalizedName = NameNormalizationUtil.normalizeForComparison(this.name);
	}

	/** Use setName() instead — it keeps normalizedName in sync. */
	public void setNormalizedName(String normalizedName) {
		this.normalizedName = NameNormalizationUtil.normalizeForComparison(normalizedName);
	}
}