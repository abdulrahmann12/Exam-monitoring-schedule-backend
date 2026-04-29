package schedule.example.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
	name = "rooms",
	indexes = {
			@Index(name = "idx_room_name", columnList = "name"),
			@Index(name = "idx_room_normalized_name", columnList = "normalized_name", unique = true),
		@Index(name = "idx_room_type", columnList = "type"),
		@Index(name = "idx_room_active", columnList = "active")
	}
)
public class Room {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "normalized_name", length = 120, unique = true)
	private String normalizedName;

	@Column(nullable = false)
	private int capacity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private RoomType type;

	@Column(nullable = false)
	private int minInvigilators;

	@Column(nullable = false)
	private boolean active = true;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(nullable = false)
	private Instant updatedAt;

	public Room() {
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = NameNormalizationUtil.normalizeWhitespace(name);
		this.normalizedName = NameNormalizationUtil.normalizeForComparison(this.name);
	}

	public String getNormalizedName() {
		return normalizedName;
	}

	public void setNormalizedName(String normalizedName) {
		this.normalizedName = NameNormalizationUtil.normalizeForComparison(normalizedName);
	}

	public int getCapacity() {
		return capacity;
	}

	public void setCapacity(int capacity) {
		this.capacity = capacity;
	}

	public RoomType getType() {
		return type;
	}

	public void setType(RoomType type) {
		this.type = type;
	}

	public int getMinInvigilators() {
		return minInvigilators;
	}

	public void setMinInvigilators(int minInvigilators) {
		this.minInvigilators = minInvigilators;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}