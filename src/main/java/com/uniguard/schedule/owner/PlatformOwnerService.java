package com.uniguard.schedule.owner;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import com.uniguard.schedule.audit.AuditCategory;
import com.uniguard.schedule.audit.AuditEntry;
import com.uniguard.schedule.audit.AuditService;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.entity.AdminUser;
import com.uniguard.schedule.entity.AuditEvent;
import com.uniguard.schedule.entity.University;
import com.uniguard.schedule.entity.enums.AccountTokenPurpose;
import com.uniguard.schedule.entity.enums.AdminRole;
import com.uniguard.schedule.exception.BusinessRuleViolationException;
import com.uniguard.schedule.exception.DuplicateResourceException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.owner.OwnerDtos.AddAdminRequest;
import com.uniguard.schedule.owner.OwnerDtos.AdminInfo;
import com.uniguard.schedule.owner.OwnerDtos.AdminInvitation;
import com.uniguard.schedule.owner.OwnerDtos.CreateUniversityRequest;
import com.uniguard.schedule.owner.OwnerDtos.DeleteUniversityRequest;
import com.uniguard.schedule.owner.OwnerDtos.DeletedUniversity;
import com.uniguard.schedule.owner.OwnerDtos.Overview;
import com.uniguard.schedule.owner.OwnerDtos.PlatformTotals;
import com.uniguard.schedule.owner.OwnerDtos.UniversitySummary;
import com.uniguard.schedule.owner.OwnerDtos.UpdateUniversityRequest;
import com.uniguard.schedule.repository.AdminUserRepository;
import com.uniguard.schedule.repository.AuditEventRepository;
import com.uniguard.schedule.repository.UniversityRepository;
import com.uniguard.schedule.security.PasswordPolicy;
import com.uniguard.schedule.security.SecureTokens;
import com.uniguard.schedule.service.AccountEmailService;
import com.uniguard.schedule.service.AccountTokenService;
import com.uniguard.schedule.service.SessionService;
import com.uniguard.schedule.tenant.TenantContext;
import com.uniguard.schedule.tenant.UniversityProvisioning;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The platform owner's panel: universities, their administrators, switching them off or on, and usage.
 * The owner sees <em>counts</em> only, never a university's people, rooms or schedules.
 *
 * <p>The owner belongs to no university, so Hibernate's university filter would show it nothing.
 * Counts across universities are therefore read with plain SQL (which the filter does not touch)
 * or from the account and activity tables, which are not university-filtered.
 */
@Service
@Validated
public class PlatformOwnerService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PlatformOwnerService.class);
	/** 3–60 characters: lowercase letters, digits and inner dashes. */
	private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$");
	private static final String INVITED_BY = "The Uni-Guard Schedules team";

	private final UniversityRepository universities;
	private final AdminUserRepository users;
	private final AuditEventRepository auditEvents;
	private final UniversityProvisioning provisioning;
	private final AccountTokenService accountTokenService;
	private final AccountEmailService accountEmailService;
	private final SessionService sessionService;
	private final AuditService auditService;
	private final PasswordEncoder passwordEncoder;
	private final JdbcTemplate jdbc;
	private final com.uniguard.schedule.service.TwoFactorService twoFactorService;
	private final TransactionTemplate transaction;
	private final TransactionTemplate readOnly;

	public PlatformOwnerService(
		UniversityRepository universities,
		AdminUserRepository users,
		AuditEventRepository auditEvents,
		UniversityProvisioning provisioning,
		AccountTokenService accountTokenService,
		AccountEmailService accountEmailService,
		SessionService sessionService,
		AuditService auditService,
		PasswordEncoder passwordEncoder,
		JdbcTemplate jdbc,
		com.uniguard.schedule.service.TwoFactorService twoFactorService,
		PlatformTransactionManager transactionManager
	) {
		this.universities = universities;
		this.users = users;
		this.auditEvents = auditEvents;
		this.provisioning = provisioning;
		this.accountTokenService = accountTokenService;
		this.accountEmailService = accountEmailService;
		this.sessionService = sessionService;
		this.auditService = auditService;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
		this.twoFactorService = twoFactorService;
		this.transaction = new TransactionTemplate(transactionManager);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
	}

	// ─────────────────────────── Reading ───────────────────────────

	public Overview overview() {
		return readOnly.execute(status -> {
			Usage usage = usage();
			List<UniversitySummary> list = universities.findAll(Sort.by("createdAt", "name")).stream()
				.map(university -> summary(university, usage))
				.toList();
			PlatformTotals totals = new PlatformTotals(
				list.size(),
				list.stream().filter(UniversitySummary::active).count(),
				list.stream().mapToLong(UniversitySummary::accounts).sum(),
				list.stream().mapToLong(UniversitySummary::people).sum(),
				list.stream().mapToLong(UniversitySummary::scheduledExamRooms).sum());
			return new Overview(totals, list);
		});
	}

	/** Platform-level activity: the owner's own actions and sign-ins of accounts without a university. */
	public Page<AuditEvent> activity(Pageable pageable) {
		return readOnly.execute(status -> auditEvents.findByUniversityIdIsNull(pageable));
	}

	// ─────────────────────────── Changes ───────────────────────────

	/**
	 * Creates an empty, ready-to-use university and invites its first administrator.
	 * Everything is checked <em>before</em> anything is created, so a refused request leaves nothing behind.
	 * Not one transaction on purpose: the university's own data must be written in its own scope
	 * (see {@link UniversityProvisioning}).
	 */
	public AdminInvitation createUniversity(@Valid CreateUniversityRequest request) {
		String name = request.name().trim();
		String slug = normalizeSlug(request.slug());
		String email = normalizeEmail(request.adminEmail());
		requireFreeName(name, null);
		if (universities.existsBySlugIgnoreCase(slug)) {
			throw new DuplicateResourceException(Messages.OWNER_SLUG_EXISTS);
		}
		if (users.findByEmailIgnoreCase(email).isPresent()) {
			throw new DuplicateResourceException(Messages.OWNER_EMAIL_IN_OTHER_UNIVERSITY);
		}

		University university = provisioning.create(name, slug);
		auditService.record(entry("UNIVERSITY_CREATED", university, "Created university '" + name + "' (" + slug + ")"));
		LOGGER.warn("[OWNER] Created university '{}' ({}).", name, slug);

		Invited invited = transaction.execute(status -> {
			AdminUser admin = new AdminUser();
			admin.setEmail(email);
			admin.setDisplayName(cleanName(request.adminName()));
			admin.setRole(AdminRole.ADMIN);
			admin.setEnabled(true);
			admin.setUniversityId(university.getId());
			setUnusablePassword(admin);
			return invite(users.saveAndFlush(admin), university, "Invited " + email + " as first administrator of '" + name + "'");
		});
		return new AdminInvitation(summaryOf(university.getId()), email, invited.link().link(), invited.link().expiresAt(), invited.emailSent());
	}

	/** Renames a university and/or switches it off or on. Switching off signs all its users out at once. */
	public UniversitySummary updateUniversity(UUID id, @Valid UpdateUniversityRequest request) {
		transaction.executeWithoutResult(status -> {
			University university = requireUniversity(id);
			String name = request.name().trim();
			if (!name.equals(university.getName())) {
				requireFreeName(name, id);
				auditService.recordAfterCommit(entry("UNIVERSITY_RENAMED", university,
					"Renamed university '" + university.getName() + "' → '" + name + "'"));
				university.setName(name);
			}
			boolean active = request.active();
			if (active != university.isActive()) {
				university.setActive(active);
				if (active) {
					auditService.recordAfterCommit(entry("UNIVERSITY_ACTIVATED", university, "Switched on university '" + name + "'"));
				} else {
					int ended = 0;
					for (AdminUser user : users.findByUniversityId(id)) {
						ended += sessionService.revokeAllForUser(user.getId());
					}
					auditService.recordAfterCommit(entry("UNIVERSITY_DEACTIVATED", university,
						"Switched off university '" + name + "' (" + ended + " signed-in device(s) signed out)"));
				}
				LOGGER.warn("[OWNER] University '{}' switched {}.", name, active ? "ON" : "OFF");
			}
			universities.save(university);
		});
		return summaryOf(id);
	}

	/**
	 * Adds an administrator to a university, or sends a fresh link to one who has never signed in
	 * (e.g. the first invitation got lost or expired).
	 */
	public AdminInvitation addAdministrator(UUID universityId, @Valid AddAdminRequest request) {
		String email = normalizeEmail(request.email());
		Invited invited = transaction.execute(status -> {
			University university = requireUniversity(universityId);
			AdminUser existing = users.findByEmailIgnoreCase(email).orElse(null);
			if (existing == null) {
				AdminUser admin = new AdminUser();
				admin.setEmail(email);
				admin.setDisplayName(cleanName(request.displayName()));
				admin.setRole(AdminRole.ADMIN);
				admin.setEnabled(true);
				admin.setUniversityId(universityId);
				setUnusablePassword(admin);
				return invite(users.saveAndFlush(admin), university, "Invited " + email + " as administrator of '" + university.getName() + "'");
			}
			boolean sameUniversityAdmin = universityId.equals(existing.getUniversityId()) && existing.getRole() == AdminRole.ADMIN;
			if (!sameUniversityAdmin) {
				throw new DuplicateResourceException(Messages.OWNER_EMAIL_IN_OTHER_UNIVERSITY);
			}
			if (existing.getLastLoginAt() != null) {
				throw new BusinessRuleViolationException(Messages.OWNER_ADMIN_ALREADY_ACTIVE);
			}
			// A fresh link: any earlier link or temporary password stops working.
			setUnusablePassword(existing);
			existing.setEnabled(true);
			existing.setTokenVersion(existing.getTokenVersion() + 1);
			users.save(existing);
			sessionService.revokeAllForUser(existing.getId());
			return invite(existing, university, "Sent a new invitation to " + email + " (administrator of '" + university.getName() + "')");
		});
		return new AdminInvitation(summaryOf(universityId), email, invited.link().link(), invited.link().expiresAt(), invited.emailSent());
	}

	/**
	 * A university administrator lost their phone and recovery codes (e.g. the only administrator):
	 * the owner removes two-step sign-in; the administrator is signed out and sets it up again.
	 */
	public UniversitySummary resetAdministratorTwoFactor(UUID universityId, UUID userId) {
		transaction.executeWithoutResult(status -> {
			University university = requireUniversity(universityId);
			AdminUser admin = users.findById(userId)
				.filter(found -> universityId.equals(found.getUniversityId()) && found.getRole() == AdminRole.ADMIN)
				.orElseThrow(() -> new NotFoundException(Messages.USER_NOT_FOUND));
			if (!admin.isTotpEnabled()) {
				throw new BusinessRuleViolationException(Messages.MFA_RESET_NOT_ON);
			}
			twoFactorService.clear(admin);
			admin.setTokenVersion(admin.getTokenVersion() + 1);
			users.save(admin);
			sessionService.revokeAllForUser(admin.getId());
			auditService.recordAfterCommit(entry("UNIVERSITY_ADMIN_2FA_RESET", university,
				"Reset two-step sign-in of " + admin.getEmail() + " (administrator of '" + university.getName() + "')"));
		});
		return summaryOf(universityId);
	}

	/**
	 * Permanently deletes a university and <em>everything</em> in it: people, rooms, time slots, exam periods,
	 * assignments, settings, emails, its activity log and its accounts (with their sessions, links, two-step
	 * and SSO data). One transaction: either all of it goes or nothing does.
	 *
	 * <p>Guards: the owner's own password, the university's exact name, the university must already be
	 * switched off (so nobody works in it meanwhile; switching off signed everyone out), and the first
	 * university can never be deleted (the server re-creates it on start and the demo account lives there).
	 *
	 * <p>Plain SQL on purpose, children before parents, every table explicitly: the local H2 database has no
	 * "on delete cascade" rules, and Hibernate's university filter would hide the rows from the owner.
	 * The university is addressed through its unique short code, so no id has to be bound as BINARY(16) vs UUID.
	 * Only a platform-level record of the deletion stays (in the owner's activity log).
	 */
	public DeletedUniversity deleteUniversity(UUID id, UUID ownerId, @Valid DeleteUniversityRequest request) {
		AdminUser owner = users.findById(ownerId)
			.filter(user -> user.getRole() == AdminRole.SUPER_ADMIN)
			.orElseThrow(() -> new NotFoundException(Messages.USER_NOT_FOUND));
		String password = request.password();
		if (PasswordPolicy.exceedsMaximum(password) || !passwordEncoder.matches(password, owner.getPasswordHash())) {
			throw new ValidationException(Messages.AUTH_CURRENT_PASSWORD_WRONG);
		}
		if (TenantContext.DEFAULT_UNIVERSITY_ID.equals(id)) {
			throw new BusinessRuleViolationException(Messages.OWNER_DELETE_DEFAULT);
		}

		DeletedUniversity deleted = transaction.execute(status -> {
			University university = requireUniversity(id);
			if (university.isActive()) {
				throw new BusinessRuleViolationException(Messages.OWNER_DELETE_STILL_ACTIVE);
			}
			if (!university.getName().equals(request.confirmName().trim())) {
				throw new ValidationException(Messages.OWNER_DELETE_NAME_MISMATCH);
			}
			String slug = university.getSlug();
			String uni = "(select u.id from universities u where u.slug = ?)";
			String ofUsers = "user_id in (select a.id from admin_users a where a.university_id = " + uni + ")";
			String ofPeople = "person_id in (select p.id from people p where p.university_id = " + uni + ")";

			jdbc.update("delete from invigilator_assignments where university_id = " + uni, slug);
			long examRooms = jdbc.update("delete from room_assignments where university_id = " + uni, slug);
			jdbc.update("delete from person_unavailability where university_id = " + uni, slug);
			jdbc.update("delete from person_subject_codes where " + ofPeople, slug);
			jdbc.update("delete from person_available_days where " + ofPeople, slug);
			long people = jdbc.update("delete from people where university_id = " + uni, slug);
			jdbc.update("delete from time_slots where university_id = " + uni, slug);
			jdbc.update("delete from schedule_groups where university_id = " + uni, slug);
			long rooms = jdbc.update("delete from rooms where university_id = " + uni, slug);
			jdbc.update("delete from settings where university_id = " + uni, slug);
			long emails = jdbc.update("delete from email_outbox where university_id = " + uni, slug);
			jdbc.update("delete from audit_events where university_id = " + uni, slug);
			for (String table : List.of("user_sessions", "account_tokens", "mfa_recovery_codes", "mfa_challenges", "user_identities", "sso_states")) {
				jdbc.update("delete from " + table + " where " + ofUsers, slug);
			}
			long accounts = jdbc.update("delete from admin_users where university_id = " + uni, slug);
			if (jdbc.update("delete from universities where slug = ?", slug) != 1) {
				throw new IllegalStateException("University '" + slug + "' was not deleted");
			}

			String name = university.getName();
			auditService.recordAfterCommit(entry("UNIVERSITY_DELETED", university, "Deleted university '" + name + "' (" + slug + "): "
				+ accounts + " account(s), " + people + " people, " + rooms + " room(s), " + examRooms + " exam room(s), " + emails + " email(s)"));
			return new DeletedUniversity(name, accounts, people, rooms, examRooms, emails);
		});
		LOGGER.warn("[OWNER] Deleted university '{}' ({} accounts, {} people).", deleted.name(), deleted.accounts(), deleted.people());
		return deleted;
	}

	// ─────────────────────────── Helpers ───────────────────────────

	private record Invited(AccountTokenService.Issued link, boolean emailSent) {
	}

	/** Issues the one-time link; the email goes out only after the account is safely saved. */
	private Invited invite(AdminUser admin, University university, String summary) {
		AccountTokenService.Issued link = accountTokenService.issue(admin, AccountTokenPurpose.INVITE);
		boolean emailSent = accountEmailService.canSend();
		afterCommit(() -> accountEmailService.sendInvite(admin, link, INVITED_BY));
		auditService.recordAfterCommit(entry("UNIVERSITY_ADMIN_INVITED", university, summary));
		return new Invited(link, emailSent);
	}

	private UniversitySummary summaryOf(UUID id) {
		return readOnly.execute(status -> summary(requireUniversity(id), usage()));
	}

	private University requireUniversity(UUID id) {
		return universities.findById(id).orElseThrow(() -> new NotFoundException(Messages.OWNER_UNIVERSITY_NOT_FOUND));
	}

	private void requireFreeName(String name, UUID self) {
		universities.findFirstByNameIgnoreCase(name)
			.filter(other -> !other.getId().equals(self))
			.ifPresent(other -> {
				throw new DuplicateResourceException(Messages.OWNER_NAME_EXISTS);
			});
	}

	private UniversitySummary summary(University university, Usage usage) {
		UUID id = university.getId();
		List<AdminInfo> admins = users.findByUniversityIdAndRoleOrderByEmailAsc(id, AdminRole.ADMIN).stream()
			.map(admin -> new AdminInfo(admin.getId(), admin.getEmail(), admin.getDisplayName(), admin.isEnabled(),
				admin.getLastLoginAt() != null, admin.getLastLoginAt() == null && accountTokenService.hasPendingInvite(admin.getId()),
				admin.isTotpEnabled()))
			.toList();
		return new UniversitySummary(id, university.getName(), university.getSlug(), university.isActive(), university.getCreatedAt(),
			usage.accounts().getOrDefault(id, 0L), usage.people().getOrDefault(id, 0L), usage.rooms().getOrDefault(id, 0L),
			usage.examRooms().getOrDefault(id, 0L), usage.lastSignIn().get(id), usage.lastActivity().get(id), admins);
	}

	private record Usage(Map<UUID, Long> accounts, Map<UUID, Instant> lastSignIn, Map<UUID, Long> people, Map<UUID, Long> rooms,
		Map<UUID, Long> examRooms, Map<UUID, Instant> lastActivity) {
	}

	private Usage usage() {
		Map<UUID, Long> accounts = new HashMap<>();
		Map<UUID, Instant> lastSignIn = new HashMap<>();
		for (Object[] row : users.accountUsageByUniversity()) {
			accounts.put((UUID) row[0], ((Number) row[1]).longValue());
			if (row[2] != null) {
				lastSignIn.put((UUID) row[0], (Instant) row[2]);
			}
		}
		Map<UUID, Instant> lastActivity = new HashMap<>();
		for (Object[] row : auditEvents.lastActivityByUniversity()) {
			lastActivity.put((UUID) row[0], (Instant) row[1]);
		}
		return new Usage(accounts, lastSignIn,
			countPerUniversity("SELECT university_id, COUNT(*) FROM people WHERE active = TRUE GROUP BY university_id"),
			countPerUniversity("SELECT university_id, COUNT(*) FROM rooms WHERE active = TRUE GROUP BY university_id"),
			countPerUniversity("SELECT university_id, COUNT(*) FROM room_assignments GROUP BY university_id"),
			lastActivity);
	}

	/** Plain SQL on purpose: counts across all universities (Hibernate's filter would hide them from the owner). */
	private Map<UUID, Long> countPerUniversity(String sql) {
		Map<UUID, Long> counts = new HashMap<>();
		jdbc.query(sql, rs -> {
			counts.put(toUuid(rs.getObject(1)), rs.getLong(2));
		});
		return counts;
	}

	/** MySQL returns BINARY(16) as bytes; H2 may return a UUID. */
	static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		if (value instanceof byte[] bytes && bytes.length == 16) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			return new UUID(buffer.getLong(), buffer.getLong());
		}
		throw new IllegalStateException("Unexpected university id type: " + (value == null ? "null" : value.getClass()));
	}

	private static String normalizeSlug(String slug) {
		String normalized = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
		if (!SLUG.matcher(normalized).matches()) {
			throw new ValidationException(Messages.OWNER_SLUG_INVALID);
		}
		return normalized;
	}

	private static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static String cleanName(String name) {
		return name == null || name.isBlank() ? null : name.trim();
	}

	/** Random password nobody knows: the account can only be entered through the emailed link. */
	private void setUnusablePassword(AdminUser user) {
		user.setPasswordHash(passwordEncoder.encode(SecureTokens.newToken()));
		user.setMustChangePassword(false);
	}

	private static AuditEntry entry(String action, University university, String summary) {
		return AuditEntry.success(action, AuditCategory.PLATFORM, "UNIVERSITY", university.getId(), summary);
	}

	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		} else {
			action.run();
		}
	}
}
