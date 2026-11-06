package schedule.example.schedule.entity;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A date range (inclusive) during which a person cannot be assigned — leave, conference,
 * sickness, etc. Complements the weekly {@link Person#getAvailableDays()} pattern.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
	name = "person_unavailability",
	indexes = {
		@Index(name = "idx_unavailability_person", columnList = "person_id"),
		@Index(name = "idx_unavailability_range", columnList = "start_date, end_date")
	}
)
public class PersonUnavailability {

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

	@ToString.Exclude
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "person_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Person person;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(length = 200)
	private String reason;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** True when {@code date} falls inside this range (both ends inclusive). */
	public boolean covers(LocalDate date) {
		return !date.isBefore(startDate) && !date.isAfter(endDate);
	}
}
