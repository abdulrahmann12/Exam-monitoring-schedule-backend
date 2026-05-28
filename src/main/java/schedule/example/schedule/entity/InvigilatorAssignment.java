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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "invigilator_assignments",
        indexes = {
                @Index(name = "idx_invigilator_assignment_person", columnList = "invigilator_id"),
                @Index(name = "idx_invigilator_assignment_room", columnList = "room_assignment_id")
        }
)
public class InvigilatorAssignment {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @ToString.Exclude
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "room_assignment_id", nullable = false)
        private RoomAssignment roomAssignment;

        @ToString.Exclude
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "invigilator_id")
        private Person invigilator;

        /** Position within the room assignment (mapped by @OrderColumn). */
        @Builder.Default
        @Column(name = "position_index", nullable = false)
        private int positionIndex = 0;

        /** True for mandatory slots (up to room.minInvigilators). */
        @Builder.Default
        @Column(nullable = false)
        private boolean required = true;

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;
}