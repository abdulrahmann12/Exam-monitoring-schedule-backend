package schedule.example.schedule.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import schedule.example.schedule.entity.enums.PersonRole;
import schedule.example.schedule.entity.enums.WeekDay;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "people",
        indexes = {
                @Index(name = "idx_person_role", columnList = "role"),
                @Index(name = "idx_person_name", columnList = "name"),
                @Index(name = "idx_person_normalized_name", columnList = "normalized_name", unique = true),
                @Index(name = "idx_person_active", columnList = "active"),
                @Index(name = "idx_person_department", columnList = "department")
        }
)
public class Person {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(nullable = false, length = 160)
        private String name;

        @Column(name = "normalized_name", length = 160, unique = true)
        private String normalizedName;

        @Column(nullable = false, length = 160)
        private String department;

        @Setter(AccessLevel.NONE)
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 32)
        private PersonRole role;

        /** Lazy-loaded with @BatchSize(50) to avoid N+1 on paginated queries. */
        @Builder.Default
        @ElementCollection(fetch = FetchType.LAZY)
        @CollectionTable(
                name = "person_available_days",
                joinColumns = @JoinColumn(name = "person_id"),
                indexes = @Index(name = "idx_person_available_days_person_id", columnList = "person_id")
        )
        @Column(name = "available_day", nullable = false, length = 16)
        @Enumerated(EnumType.STRING)
        @BatchSize(size = 50)
        private Set<WeekDay> availableDays = new LinkedHashSet<>();

        @Builder.Default
        @Column(nullable = false)
        private int totalAssignments = 0;

        /** Soft-delete flag — inactive people are hidden from scheduling but kept for history. */
        @Builder.Default
        @Column(nullable = false)
        private boolean active = true;

        /** Max rooms per time slot: 2 for CHIEF_INVIGILATOR, 1 for INVIGILATOR. Set via setRole(). */
        @Builder.Default
        @Column(nullable = false)
        private int maxParallelRooms = 1;

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

        /** Sets role and auto-derives maxParallelRooms (CHIEF=2, INVIGILATOR=1). */
        public void setRole(PersonRole role) {
                this.role = role;
                this.maxParallelRooms = (role == PersonRole.CHIEF_INVIGILATOR) ? 2 : 1;
        }
}