package schedule.example.schedule.entity;

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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "invigilator_assignments",
        indexes = {
                @Index(name = "idx_invigilator_assignment_person", columnList = "invigilator_id")
        }
)
public class InvigilatorAssignment {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "room_assignment_id", nullable = false)
        private RoomAssignment roomAssignment;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "invigilator_id")
        private Person invigilator;

        /**
         * Ordered position index within the room assignment — mapped by @OrderColumn.
         * Stored here as a field for explicit querying.
         */
        @Column(name = "position_index", nullable = false)
        private int positionIndex = 0;

        /**
         * True for positions up to room.minInvigilators (mandatory slots).
         * False for extra positions added dynamically by the admin.
         */
        @Column(nullable = false)
        private boolean required = true;

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;

        public InvigilatorAssignment() {
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }

        public RoomAssignment getRoomAssignment() { return roomAssignment; }
        public void setRoomAssignment(RoomAssignment roomAssignment) { this.roomAssignment = roomAssignment; }

        public Person getInvigilator() { return invigilator; }
        public void setInvigilator(Person invigilator) { this.invigilator = invigilator; }

        public int getPositionIndex() { return positionIndex; }
        public void setPositionIndex(int positionIndex) { this.positionIndex = positionIndex; }

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }

        public Instant getCreatedAt() { return createdAt; }
}