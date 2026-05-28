package schedule.example.schedule.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import schedule.example.schedule.entity.enums.AssignmentSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "room_assignments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_room_assignment_date_slot_room",
                        columnNames = {"exam_date", "time_slot_id", "room_id"})
        },
        indexes = {
                @Index(name = "idx_room_assignment_date", columnList = "exam_date"),
                @Index(name = "idx_room_assignment_time_slot", columnList = "time_slot_id"),
                @Index(name = "idx_room_assignment_chief", columnList = "chief_invigilator_id"),
                @Index(name = "idx_room_assignment_locked", columnList = "is_locked"),
                @Index(name = "idx_ra_slot_date_chief", columnList = "time_slot_id, exam_date, chief_invigilator_id"),
                @Index(name = "idx_ra_date_slot", columnList = "exam_date, time_slot_id")
        }
)
public class RoomAssignment {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        /** Exam date for this assignment. */
        @Column(name = "exam_date", nullable = false)
        private LocalDate examDate;

        @ToString.Exclude
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "room_id", nullable = false)
        private Room room;

        @ToString.Exclude
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "time_slot_id", nullable = false)
        private TimeSlot timeSlot;

        @Column(length = 160)
        private String subjectName;

        @Column(length = 40)
        private String subjectCode;

        @ToString.Exclude
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "chief_invigilator_id")
        private Person chiefInvigilator;

        @Builder.Default
        @Column(name = "is_locked", nullable = false)
        private boolean locked = false;

        /** Increments on each generation run. */
        @Builder.Default
        @Column(name = "generation_version", nullable = false)
        private int generationVersion = 0;

        /** Whether the assignment was generated, manually set, or mixed. */
        @Builder.Default
        @Enumerated(EnumType.STRING)
        @Column(name = "source", nullable = false, length = 16)
        private AssignmentSource source = AssignmentSource.MANUAL;

        /** Invigilators for this room. Batched to avoid N+1 outside EntityGraph paths. */
        @ToString.Exclude
        @Builder.Default
        @BatchSize(size = 25)
        @OneToMany(mappedBy = "roomAssignment", cascade = CascadeType.ALL, orphanRemoval = true)
        @OrderColumn(name = "position_index")
        private List<InvigilatorAssignment> invigilatorAssignments = new ArrayList<>();

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;

        @LastModifiedDate
        @Column(nullable = false)
        private Instant updatedAt;

        // ── Business utility methods ────────────────────────────────────────────

        /** Adds one invigilator and sets back-reference. */
        public void addInvigilatorAssignment(InvigilatorAssignment assignment) {
                if (assignment == null) return;
                assignment.setRoomAssignment(this);
                invigilatorAssignments.add(assignment);
        }

        /** Clears and replaces all invigilator assignments. */
        public void replaceInvigilatorAssignments(List<InvigilatorAssignment> assignments) {
                invigilatorAssignments.clear();
                if (assignments == null) return;
                assignments.forEach(this::addInvigilatorAssignment);
        }
}