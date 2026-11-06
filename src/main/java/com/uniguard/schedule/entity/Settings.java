package com.uniguard.schedule.entity;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.uniguard.schedule.entity.enums.ThemeMode;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "settings")
public class Settings {

	@Id
	private UUID id;

	/**
	 * The university this row belongs to. Set automatically from the signed-in user's university;
	 * Hibernate also adds it to every query, so other universities' rows are never visible.
	 */
	@TenantId
	@Column(name = "university_id", nullable = false, updatable = false)
	private UUID universityId;

	@Column(nullable = false, length = 160)
	private String systemName;

	@Column(length = 200)
	private String appTagline;

	@Column(length = 500)
	private String logoUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ThemeMode theme;

	@Column(nullable = false, length = 200)
	private String universityName;

	@Column(length = 160)
	private String department;

	@Column(nullable = false, length = 120)
	private String examPeriod;

	/**
	 * Maximum number of separate (non-overlapping) sittings one person may cover on a single day.
	 * {@code 0} means unlimited.
	 */
	@Builder.Default
	@Column(name = "max_duties_per_day", nullable = false)
	private int maxDutiesPerDay = 0;

	/** Max separate duty sessions per person in one exam period (schedule group); 0 = unlimited. */
	@Builder.Default
	@Column(name = "max_duties_per_period", nullable = false)
	private int maxDutiesPerPeriod = 0;

	/** Minimum break, in minutes, between two sessions of the same person on one day; 0 = none. */
	@Builder.Default
	@Column(name = "min_rest_minutes", nullable = false)
	private int minRestMinutes = 0;

	/**
	 * One invigilator per this many students (e.g. 30). Rooms with a student count then need
	 * max(room minimum, ceil(students / value)) invigilators. 0 = always use the room minimum.
	 */
	@Builder.Default
	@Column(name = "students_per_invigilator", nullable = false)
	private int studentsPerInvigilator = 0;
}