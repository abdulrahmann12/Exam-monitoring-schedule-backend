package com.uniguard.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.uniguard.schedule.entity.enums.AccountTokenPurpose;

import java.time.Instant;
import java.util.UUID;

/** A one-time link (invitation or password reset). Only the hash of the secret is stored. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "account_tokens",
	indexes = {
		@Index(name = "ux_account_tokens_hash", columnList = "token_hash", unique = true),
		@Index(name = "idx_account_tokens_user_id", columnList = "user_id")
	}
)
public class AccountToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountTokenPurpose purpose;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	public boolean isUsable(Instant now) {
		return usedAt == null && expiresAt.isAfter(now);
	}
}
