package schedule.example.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A reusable slot template (e.g. "Morning Session 08:00–11:00").
 * It has NO exam date — the date lives on RoomAssignment.examDate.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "time_slots",
        indexes = {
                @Index(name = "idx_time_slot_sort_order", columnList = "sort_order"),
                @Index(name = "idx_time_slot_active", columnList = "active")
        }
)
public class TimeSlot {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        /** Optional display label, e.g. "Morning Session" */
        @Column(length = 120)
        private String label;

        @Column(nullable = false)
        private LocalTime startTime;

        @Column(nullable = false)
        private LocalTime endTime;

        /** Controls ordering in UI lists */
        @Column(name = "sort_order", nullable = false)
        private int sortOrder = 0;

        @Column(nullable = false)
        private boolean active = true;

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;

        @LastModifiedDate
        @Column(nullable = false)
        private Instant updatedAt;

        public TimeSlot() {
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
}