package com.uniguard.schedule.entity;

import jakarta.persistence.CollectionTable;
import org.hibernate.annotations.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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

import com.uniguard.schedule.entity.enums.PersonRole;
import com.uniguard.schedule.entity.enums.Gender;
import com.uniguard.schedule.entity.enums.WeekDay;
import com.uniguard.schedule.util.NameNormalizationUtil;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
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
                @Index(name = "ux_person_university_normalized_name", columnList = "university_id, normalized_name", unique = true),
                @Index(name = "idx_person_active", columnList = "active"),
                @Index(name = "idx_person_department", columnList = "department"),
                @Index(name = "ux_person_university_email", columnList = "university_id, email", unique = true)
        }
)
public class Person {

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

        @Column(nullable = false, length = 160)
        private String name;

        @Column(name = "normalized_name", length = 160)
        private String normalizedName;

        @Column(nullable = false, length = 160)
        private String department;

        /** Optional unique address used for schedule notification emails. */
        @Column(length = 255)
        private String email;

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

        /**
         * Legacy role default (2 for CHIEF_INVIGILATOR, 1 for INVIGILATOR), kept for old API clients.
         * The scheduling rules do NOT use it: the chief limit is the university setting
         * {@code chief_max_parallel_rooms} (see {@code SchedulingSettings#parallelRoomsFor}).
         */
        @Builder.Default
        @Column(nullable = false)
        private int maxParallelRooms = 1;

        /** Optional — required only to staff gender-separated halls. */
        @Enumerated(EnumType.STRING)
        @Column(length = 8)
        private Gender gender;

        /** Subject codes this person teaches; they may not invigilate those exams. Stored upper-case. */
        @Builder.Default
        @ElementCollection(fetch = FetchType.LAZY)
        @CollectionTable(
                name = "person_subject_codes",
                joinColumns = @JoinColumn(name = "person_id"),
                indexes = @Index(name = "idx_person_subject_codes_person_id", columnList = "person_id")
        )
        @Column(name = "subject_code", nullable = false, length = 40)
        @BatchSize(size = 50)
        private Set<String> subjectCodes = new LinkedHashSet<>();


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

        /** Blank values are stored as null so the unique index allows many people without email. */
        public void setEmail(String email) {
                if (email == null || email.isBlank()) {
                        this.email = null;
                        return;
                }
                this.email = email.trim().toLowerCase(Locale.ROOT);
        }

        /** Sets role and auto-derives maxParallelRooms (CHIEF=2, INVIGILATOR=1). */
        public void setRole(PersonRole role) {
                this.role = role;
                this.maxParallelRooms = parallelRoomsFor(role);
        }

        /**
         * Safety net: the Lombok builder (used by MapStruct on create) bypasses {@link #setRole},
         * which left chiefs created through the API with a limit of 1. Always derive it on save.
         */
        @PrePersist
        @PreUpdate
        void deriveMaxParallelRooms() {
                this.maxParallelRooms = parallelRoomsFor(role);
                this.subjectCodes = normalizeSubjectCodes(subjectCodes);
                // The mapper builds entities without the setters, so repeat their clean-up here:
                // otherwise normalizedName stays empty (duplicate names go unnoticed, name search
                // misses the person) and a blank email is stored as "" instead of null.
                setName(name);
                setEmail(email);
        }

        public void setSubjectCodes(Set<String> codes) {
                this.subjectCodes = normalizeSubjectCodes(codes);
        }

        /** True when this person teaches {@code subjectCode} (case-insensitive). */
        public boolean teaches(String subjectCode) {
                if (subjectCode == null || subjectCode.isBlank() || subjectCodes == null) {
                        return false;
                }
                return subjectCodes.contains(subjectCode.trim().toUpperCase(Locale.ROOT));
        }

        private static Set<String> normalizeSubjectCodes(Set<String> codes) {
                Set<String> normalized = new LinkedHashSet<>();
                if (codes != null) {
                        for (String code : codes) {
                                if (code != null && !code.isBlank()) {
                                        normalized.add(code.trim().toUpperCase(Locale.ROOT));
                                }
                        }
                }
                return normalized;
        }

        private static int parallelRoomsFor(PersonRole role) {
                return role == PersonRole.CHIEF_INVIGILATOR ? 2 : 1;
        }
}