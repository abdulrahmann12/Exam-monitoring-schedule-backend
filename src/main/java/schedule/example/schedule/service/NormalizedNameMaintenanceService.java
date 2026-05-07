package schedule.example.schedule.service;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.repository.PersonRepository;
import schedule.example.schedule.repository.RoomRepository;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time backfill service that populates the {@code normalizedName} column for legacy
 * records created before the column existed.
 *
 * <p><strong>Performance fix — paginated batching:</strong> The previous implementation
 * called {@code findAllByNormalizedNameIsNull()} which loaded the entire table into the
 * JPA first-level cache in one query. At 100,000+ records this risks OOM and long GC pauses.
 *
 * <p>This version uses a paginated LIMIT + OFFSET loop to process entities in batches of
 * {@value #BATCH_SIZE}. After each batch is flushed, the session is cleared
 * ({@code entityManager.clear()}) so that processed entities are detached and eligible for GC.
 * This keeps heap usage bounded to {@code BATCH_SIZE * entity_size} regardless of total table size.
 *
 * <p><strong>Thread safety:</strong> Uses {@link AtomicBoolean} so concurrent calls to
 * {@link #synchronizePeople()} / {@link #synchronizeRooms()} short-circuit correctly
 * without a race window.
 */
@Service
public class NormalizedNameMaintenanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizedNameMaintenanceService.class);
    private static final int BATCH_SIZE = 500;

    private final PersonRepository personRepository;
    private final RoomRepository roomRepository;
    private final EntityManager entityManager;

    private final AtomicBoolean personsSynchronized = new AtomicBoolean(false);
    private final AtomicBoolean roomsSynchronized   = new AtomicBoolean(false);

    public NormalizedNameMaintenanceService(
            PersonRepository personRepository,
            RoomRepository roomRepository,
            EntityManager entityManager) {
        this.personRepository = personRepository;
        this.roomRepository   = roomRepository;
        this.entityManager    = entityManager;
    }

    /**
     * Runs both synchronizations once at startup, before the application begins serving requests.
     * Uses {@link ApplicationReadyEvent} to ensure the JPA context is fully initialized.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void synchronizeOnStartup() {
        LOGGER.info("Running normalized name backfill check...");
        doSynchronizePeople();
        doSynchronizeRooms();
        LOGGER.info("Normalized name backfill complete.");
    }

    /**
     * Synchronizes people with null normalizedName.
     * Fast-path: returns immediately if already synchronized (no DB query).
     */
    @Transactional
    public synchronized void synchronizePeople() {
        if (personsSynchronized.get()) return;
        doSynchronizePeople();
    }

    /**
     * Synchronizes rooms with null normalizedName.
     * Fast-path: returns immediately if already synchronized (no DB query).
     */
    @Transactional
    public synchronized void synchronizeRooms() {
        if (roomsSynchronized.get()) return;
        doSynchronizeRooms();
    }

    /**
     * Processes people in batches of {@value #BATCH_SIZE} to avoid loading the full table
     * into memory. After each batch is flushed, the session is cleared so GC can reclaim
     * the detached entities.
     */
    private void doSynchronizePeople() {
        int totalFixed = 0;
        List<Person> batch;
        do {
            // Always query page 0 — after flush+clear, processed entities are gone from the DB
            // query result, so the next iteration fetches the next un-processed batch.
            batch = personRepository.findByNormalizedNameIsNullPaged(PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) break;
            batch.forEach(person -> person.setName(person.getName()));
            personRepository.saveAll(batch);
            personRepository.flush();
            entityManager.clear(); // detach to keep session heap bounded
            totalFixed += batch.size();
        } while (batch.size() == BATCH_SIZE);

        if (totalFixed > 0) {
            LOGGER.info("Backfilled normalizedName for {} person record(s).", totalFixed);
        }
        personsSynchronized.set(true);
    }

    /**
     * Processes rooms in batches of {@value #BATCH_SIZE} — same approach as
     * {@link #doSynchronizePeople()}.
     */
    private void doSynchronizeRooms() {
        int totalFixed = 0;
        List<Room> batch;
        do {
            batch = roomRepository.findByNormalizedNameIsNullPaged(PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) break;
            batch.forEach(room -> room.setName(room.getName()));
            roomRepository.saveAll(batch);
            roomRepository.flush();
            entityManager.clear();
            totalFixed += batch.size();
        } while (batch.size() == BATCH_SIZE);

        if (totalFixed > 0) {
            LOGGER.info("Backfilled normalizedName for {} room record(s).", totalFixed);
        }
        roomsSynchronized.set(true);
    }
}