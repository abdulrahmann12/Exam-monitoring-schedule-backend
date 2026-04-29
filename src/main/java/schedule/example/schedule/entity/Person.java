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

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "people",
        indexes = {
                @Index(name = "idx_person_role", columnList = "role"),
                @Index(name = "idx_person_name", columnList = "name"),
                        @Index(name = "idx_person_normalized_name", columnList = "normalized_name", unique = true),
                @Index(name = "idx_person_active", columnList = "active")
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

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 32)
        private PersonRole role;

        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "person_available_days", joinColumns = @JoinColumn(name = "person_id"))
        @Column(name = "available_day", nullable = false, length = 16)
        @Enumerated(EnumType.STRING)
        private Set<WeekDay> availableDays = new LinkedHashSet<>();

        @Column(nullable = false)
        private int totalAssignments = 0;

        /**
         * Soft-delete support — inactive people are excluded from scheduling
         * but preserved for historical assignment reference.
         */
        @Column(nullable = false)
        private boolean active = true;

        /**
         * Maximum rooms this person may supervise in a single time slot.
         * Policy: 2 for CHIEF_INVIGILATOR, 1 for INVIGILATOR.
         */
        @Column(nullable = false)
        private int maxParallelRooms = 1;

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;

        @LastModifiedDate
        @Column(nullable = false)
        private Instant updatedAt;

        public Person() {
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) {
                this.name = NameNormalizationUtil.normalizeWhitespace(name);
                this.normalizedName = NameNormalizationUtil.normalizeForComparison(this.name);
        }

        public String getNormalizedName() { return normalizedName; }
        public void setNormalizedName(String normalizedName) { this.normalizedName = NameNormalizationUtil.normalizeForComparison(normalizedName); }

        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = NameNormalizationUtil.normalizeWhitespace(department); }

        public PersonRole getRole() { return role; }
        public void setRole(PersonRole role) {
                this.role = role;
                this.maxParallelRooms = (role == PersonRole.CHIEF_INVIGILATOR) ? 2 : 1;
        }

        public Set<WeekDay> getAvailableDays() { return availableDays; }
        public void setAvailableDays(Set<WeekDay> availableDays) { this.availableDays = availableDays; }

        public int getTotalAssignments() { return totalAssignments; }
        public void setTotalAssignments(int totalAssignments) { this.totalAssignments = totalAssignments; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public int getMaxParallelRooms() { return maxParallelRooms; }
        public void setMaxParallelRooms(int maxParallelRooms) { this.maxParallelRooms = maxParallelRooms; }

        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
}