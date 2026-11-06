package com.uniguard.schedule.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.uniguard.schedule.config.ApplicationDefaults;
import com.uniguard.schedule.repository.SettingsRepository;
import com.uniguard.schedule.tenant.TenantContext;

/**
 * Schedule-wide write lock.
 *
 * <p>Conflict validation is "read the current schedule, then write". Without a lock, two admins
 * saving at the same moment can both pass validation and double-book the same person or room.
 * Every transaction that changes assignments calls {@link #acquire()} first; it blocks until any
 * other schedule write has committed, so the reads that follow always see the latest data.
 *
 * <p>Implemented as {@code SELECT ... FOR UPDATE} on the settings row, which works on MySQL and
 * H2 and is released automatically at commit/rollback. Schedule writes are rare and short, so
 * serialising them costs nothing noticeable. (When multi-tenancy arrives this becomes the
 * tenant's settings row, i.e. a per-tenant lock.)
 */
@Component
@RequiredArgsConstructor
public class ScheduleWriteLock {

	private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleWriteLock.class);

	private final SettingsRepository settingsRepository;

	@Transactional(propagation = Propagation.MANDATORY)
	public void acquire() {
		if (settingsRepository.lockById(TenantContext.require()).isEmpty()) {
			LOGGER.warn("Settings row missing — schedule write lock could not be taken.");
		}
	}
}
