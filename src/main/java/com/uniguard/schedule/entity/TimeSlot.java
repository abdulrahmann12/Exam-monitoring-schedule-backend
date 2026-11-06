package com.uniguard.schedule.entity;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A slot template (e.g. "Morning Session 08:00–11:00") scoped to one schedule group.
 * It has NO exam date — the date lives on RoomAssignment.examDate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "time_slots",
        indexes = {
                @Index(name = "idx_time_slot_sort_order", columnList = "sort_order"),
                @Index(name = "idx_time_slot_active", columnList = "active"),
                @Index(name = "idx_time_slot_schedule_group", columnList = "schedule_group_id")
        }
)
public class TimeSlot {

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

        /**
         * Nullable only so existing rows can be attached on startup.
         * New slots always receive a group; deleting a group cascades to its slots.
         */
        @ToString.Exclude
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "schedule_group_id")
        @OnDelete(action = OnDeleteAction.CASCADE)
        private ScheduleGroup scheduleGroup;

        /** Optional display label, e.g. "Morning Session" */
        @Column(length = 120)
        private String label;

        @Column(nullable = false)
        private LocalTime startTime;

        @Column(nullable = false)
        private LocalTime endTime;

        /** Controls ordering in UI lists */
        @Builder.Default
        @Column(name = "sort_order", nullable = false)
        private int sortOrder = 0;

        @Builder.Default
        @Column(nullable = false)
        private boolean active = true;


        /**
         * Optimistic-locking version. Two concurrent edits of the same row fail with
         * HTTP 409 instead of silently overwriting each other.
         */
        @Version
        @Column(nullable = false)
        private long version;

        @CreatedDate
        @Column(nullable = false, updatable = false)
        private Instant createdAt;

        @LastModifiedDate
        @Column(nullable = false)
        private Instant updatedAt;
}