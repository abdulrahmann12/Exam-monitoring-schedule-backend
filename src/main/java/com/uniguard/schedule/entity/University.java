package com.uniguard.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer (college / university). Every piece of scheduling data belongs to exactly one
 * university and is invisible to all others.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "universities", indexes = @Index(name = "ux_universities_slug", columnList = "slug", unique = true))
public class University {

	/** Assigned by the application (the first university keeps the id of the original settings row). */
	@Id
	private UUID id;

	@Column(nullable = false, length = 160)
	private String name;

	/** Short unique code, e.g. "cairo-uni". */
	@Column(nullable = false, length = 80)
	private String slug;

	/** An inactive university's users cannot sign in (e.g. subscription ended). Its data is kept. */
	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/**
	 * The university's own Microsoft organisation (Entra tenant id), set by its administrator.
	 * Only accounts of this university signing in from this organisation are matched by email.
	 */
	@Column(name = "microsoft_tenant_id", length = 64)
	private String microsoftTenantId;
}
