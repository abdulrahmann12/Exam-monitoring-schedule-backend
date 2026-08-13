package schedule.example.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Logical exam period (e.g. Summer 2026). People and rooms stay global;
 * time slots and assignments belong to one group.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
		name = "schedule_groups",
		indexes = {
				@Index(name = "idx_schedule_group_name", columnList = "name", unique = true),
				@Index(name = "idx_schedule_group_active", columnList = "active")
		}
)
public class ScheduleGroup {

	@Id
	private UUID id;

	@Column(nullable = false, length = 120, unique = true)
	private String name;

	@Column(length = 500)
	private String description;

	@Builder.Default
	@Column(nullable = false)
	private boolean active = true;

	@Builder.Default
	@Column(nullable = false)
	private boolean archived = false;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(nullable = false)
	private Instant updatedAt;

	@PrePersist
	void assignIdIfMissing() {
		if (id == null) {
			id = UUID.randomUUID();
		}
	}
}
