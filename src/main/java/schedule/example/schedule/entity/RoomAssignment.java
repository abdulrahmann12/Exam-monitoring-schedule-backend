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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import schedule.example.schedule.entity.enums.AssignmentSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
                @Index(name = "idx_room_assignment_locked", columnList = "is_locked")
        }
)
public class RoomAssignment {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        /** The concrete exam date for this assignment */
        @Column(name = "exam_date", nullable = false)
        private LocalDate examDate;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "room_id", nullable = false)
        private Room room;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "time_slot_id", nullable = false)
        private TimeSlot timeSlot;

        @Column(length = 160)
        private String subjectName;

        @Column(length = 40)
        private String subjectCode;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "chief_invigilator_id")
        private Person chiefInvigilator;

        @Column(name = "is_locked", nullable = false)
        private boolean locked = false;

        /**
         * Monotonically increasing version set on each generation run.
         * Allows the frontend to reset-to-generated using this baseline.
         */
        @Column(name = "generation_version", nullable = false)
        private int generationVersion = 0;

        /**
         * Tracks whether the assignment was generated, manually set, or a mix.
         * Matches the frontend's origination tracking.
         */
        @Enumerated(EnumType.STRING)
        @Column(name = "source", nullable = false, length = 16)
        private AssignmentSource source = AssignmentSource.MANUAL;

        @OneToMany(mappedBy = "roomAssignment", cascade = CascadeType.ALL, orphanRemoval = true)
        @OrderColumn(name = "position_index")
        private List<InvigilatorAssignment> invigilatorAssignments = new ArrayList<>();

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;

        @LastModifiedDate
        @Column(nullable = false)
        private Instant updatedAt;

        public RoomAssignment() {
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }

        public LocalDate getExamDate() { return examDate; }
        public void setExamDate(LocalDate examDate) { this.examDate = examDate; }

        public Room getRoom() { return room; }
        public void setRoom(Room room) { this.room = room; }

        public TimeSlot getTimeSlot() { return timeSlot; }
        public void setTimeSlot(TimeSlot timeSlot) { this.timeSlot = timeSlot; }

        public String getSubjectName() { return subjectName; }
        public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

        public String getSubjectCode() { return subjectCode; }
        public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }

        public Person getChiefInvigilator() { return chiefInvigilator; }
        public void setChiefInvigilator(Person chiefInvigilator) { this.chiefInvigilator = chiefInvigilator; }

        public boolean isLocked() { return locked; }
        public void setLocked(boolean locked) { this.locked = locked; }

        public int getGenerationVersion() { return generationVersion; }
        public void setGenerationVersion(int generationVersion) { this.generationVersion = generationVersion; }

        public AssignmentSource getSource() { return source; }
        public void setSource(AssignmentSource source) { this.source = source; }

        public List<InvigilatorAssignment> getInvigilatorAssignments() { return invigilatorAssignments; }
        public void setInvigilatorAssignments(List<InvigilatorAssignment> invigilatorAssignments) {
                this.invigilatorAssignments = invigilatorAssignments;
        }

        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }

        public void addInvigilatorAssignment(InvigilatorAssignment assignment) {
                if (assignment == null) return;
                assignment.setRoomAssignment(this);
                invigilatorAssignments.add(assignment);
        }

        public void replaceInvigilatorAssignments(List<InvigilatorAssignment> assignments) {
                invigilatorAssignments.clear();
                if (assignments == null) return;
                assignments.forEach(this::addInvigilatorAssignment);
        }
}