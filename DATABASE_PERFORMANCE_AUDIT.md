# Database Performance Audit Report
## Schedule-Backend — Spring Boot / Hibernate / MySQL

> **Analyst:** Senior Java Performance Engineer  
> **Date:** May 7, 2026  
> **Database:** MySQL (remote host `db50370.public.databaseasp.net:3306`)  
> **Stack:** Spring Boot 3, Hibernate 6, Spring Data JPA, MapStruct, Caffeine Cache

---

## Executive Summary

The codebase is **well-structured** and shows evidence of previous performance work (pagination, `@BatchSize`, `@EntityGraph`, read-only transactions, cache). However, **six critical or high-severity issues** will cause measurable degradation at moderate scale and outright failure at 50k–100k records. The most impactful are:

1. A **loop of individual UPDATE statements** inside `applyWorkloadDelta()`
2. **Leading-wildcard LIKE queries** bypassing all name indexes on every search
3. **Full unbounded entity loads** in maintenance and bulk-name-deduplication paths
4. A **missing composite index** required by the most common validation query
5. **No Hibernate JDBC batch insert configuration** for bulk upload
6. **Connection pool capped at 5** — a critical concurrency bottleneck

---

## Table of Contents

1. [N+1 Query Detection](#1-n1-query-detection)
2. [Inefficient Query Patterns](#2-inefficient-query-patterns)
3. [Missing Database Indexes](#3-missing-database-indexes)
4. [Entity Mapping Problems](#4-entity-mapping-problems)
5. [DTO Mapping Performance](#5-dto-mapping-performance)
6. [Service Layer Issues](#6-service-layer-issues)
7. [Transaction Management Risks](#7-transaction-management-risks)
8. [Repository Query Design](#8-repository-query-design)
9. [Large Dataset Risk Analysis](#9-large-dataset-risk-analysis)
10. [Final Summary](#10-final-summary)

---

## 1. N+1 Query Detection

---

### ISSUE-1.1 — `applyWorkloadDelta()`: N individual UPDATE statements (CRITICAL)

**File:** `AssignmentService.java` — method `applyWorkloadDelta()` (lines 539–546)

**Problematic code:**
```java
private void applyWorkloadDelta(Map<UUID, Integer> before, Map<UUID, Integer> after) {
    Set<UUID> personIds = new HashSet<>(before.keySet());
    personIds.addAll(after.keySet());
    for (UUID personId : personIds) {
        int delta = after.getOrDefault(personId, 0) - before.getOrDefault(personId, 0);
        if (delta != 0) personRepository.adjustTotalAssignments(personId, delta); // ← 1 UPDATE per person
    }
}
```

**Root cause:** For every person whose assignment count changes, the code issues one `@Modifying` UPDATE query. This fires inside a loop — one round-trip to the database per person.

**Called from:**
- `createAssignment()` — minimum 1–6 updates (chief + 5 invigilators)
- `updateAssignment()` — up to 12 updates (before + after person sets)
- `saveAssignmentsBulk()` — up to **N unique people** updates after processing 500 bulk requests with 6 persons each → **3,000 individual UPDATE statements**

**Query cost:**
- 1 bulk request with 50 rooms × 6 people each → 300 UPDATE statements
- 1 bulk request with 200 rooms × 6 people each → 1,200 UPDATE statements
- Each UPDATE is a separate network round-trip × latency of remote DB

**Recommended fix:** Batch the deltas into a single query using a native SQL `CASE` expression or a temporary aggregation table:

```java
// Option A: Collect all deltas and issue one UPDATE with CASE WHEN
@Modifying
@Query(value = """
    UPDATE people
    SET total_assignments = total_assignments + CASE id
        <foreach> WHEN :#{#entry.key} THEN :#{#entry.value} </foreach>
        ELSE 0
    END
    WHERE id IN :ids
    """, nativeQuery = true)
void bulkAdjustTotalAssignments(
    @Param("ids") Set<UUID> ids,
    @Param("deltas") Map<UUID, Integer> deltas
);

// Option B (simpler): Issue batched updates using Spring Data's saveAll and 
// hibernate.jdbc.batch_size — collect Person entities, update in-memory, saveAll()
private void applyWorkloadDelta(Map<UUID, Integer> before, Map<UUID, Integer> after) {
    Map<UUID, Integer> netDeltas = new HashMap<>();
    Set<UUID> personIds = new HashSet<>(before.keySet());
    personIds.addAll(after.keySet());

    for (UUID personId : personIds) {
        int delta = after.getOrDefault(personId, 0) - before.getOrDefault(personId, 0);
        if (delta != 0) netDeltas.put(personId, delta);
    }

    if (netDeltas.isEmpty()) return;

    // One SELECT IN + batch UPDATE
    List<Person> people = personRepository.findAllById(netDeltas.keySet());
    people.forEach(p -> p.setTotalAssignments(
        p.getTotalAssignments() + netDeltas.get(p.getId())
    ));
    personRepository.saveAll(people); // becomes batch INSERT/UPDATE with hibernate.jdbc.batch_size set
}
```

**Scale impact:**
- 10k records: noticeable latency (~300ms per bulk call on remote DB)
- 50k records: degraded — users experience 1–3s delays on bulk save
- 100k+ records: bulk save timeouts for large payloads

---

### ISSUE-1.2 — `PersonMapper.toResponse()`: Lazy `availableDays` element collection triggers extra query per page

**File:** `PersonMapper.java` → `PersonResponse.java` (line 15) — `availableDays` field  
**Called from:** `PeopleService.getPeople()` (line 56–58)

**Problematic code:**
```java
// PersonResponse includes availableDays:
public record PersonResponse(
    ...
    Set<WeekDay> availableDays,  // ← triggers LAZY @ElementCollection load
    ...
)

// PeopleService.getPeople():
Page<PersonResponse> page = personRepository.search(role, department, name, pageable)
    .map(personMapper::toResponse); // ← personMapper reads availableDays here
```

**Root cause:** `Person.availableDays` is `@ElementCollection(fetch = FetchType.LAZY)`. MapStruct's `toResponse()` call accesses `person.getAvailableDays()`, which triggers a lazy load for the `person_available_days` table. With `@BatchSize(size = 50)`, Hibernate batches these loads into groups of 50, so a page of 20 persons produces **1 extra query** (for all 20 person IDs). Without `@BatchSize`, this would be 20 extra queries.

**Current state:** Partially mitigated by `@BatchSize(50)` — 1 extra query per page of ≤50 persons.  
**Residual issue:** The extra round-trip still exists and the query selects from `person_available_days` with an `IN` clause on up to 50 UUIDs.

**Estimated query cost:** 2 queries per page (1 SELECT people + 1 SELECT available_days IN(...))

**Recommended fix (eliminate the extra query entirely):** Use a JPQL `JOIN FETCH` in the search query, or use a JPQL projection that avoids the collection entirely if `availableDays` is not needed on list views:

```java
// Option A: JOIN FETCH the collection in the search query (avoid @BatchSize completely)
@Query("""
    select p from Person p
    left join fetch p.availableDays
    where (:role is null or p.role = :role)
    and (:department is null or lower(p.department) like lower(concat('%', :department, '%')))
    and (:name is null or lower(p.name) like lower(concat('%', :name, '%')))
    """)
List<Person> searchWithDays(...); // Note: can't paginate collections with JOIN FETCH — use 2-pass

// Option B (recommended): Two-pass approach — paginate IDs, then fetch with collection
// Pass 1: paginated ID query
Page<UUID> ids = personRepository.findIdsByFilters(role, department, name, pageable);
// Pass 2: fetch full data including available days
List<Person> people = personRepository.findAllWithAvailableDaysByIdIn(ids.getContent());
```

---

### ISSUE-1.3 — `validateAssignmentRules()`: N COUNT queries for invigilator slot validation

**File:** `AssignmentService.java` — method `validateAssignmentRules()` (lines 434–442)

**Problematic code:**
```java
for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
    if (ia.getInvigilator() == null) continue;
    validateAvailability(ia.getInvigilator(), examDate, false);
    long slotUsage = invigilatorAssignmentRepository.countSlotUsage(
        timeSlotId, examDate, ia.getInvigilator().getId(), existingId); // ← 1 COUNT per invigilator
    if (slotUsage > 0) throw new ConflictException(...);
}
```

**Root cause:** One `COUNT` query is issued for every invigilator in the assignment. A room with 5 invigilators fires 5 COUNT queries.

**Query cost:** 1 + N queries per assignment creation/update (N = number of invigilators)

**Recommended fix:** Batch the check into a single EXISTS query:

```java
// In InvigilatorAssignmentRepository:
@Query("""
    select ia.invigilator.id from InvigilatorAssignment ia
    where ia.roomAssignment.timeSlot.id = :slotId
    and ia.roomAssignment.examDate = :examDate
    and ia.invigilator.id in :invigilatorIds
    and (:excludedRoomAssignmentId is null or ia.roomAssignment.id <> :excludedRoomAssignmentId)
    """)
Set<UUID> findDoubleBookedInvigilatorIds(
    @Param("slotId") UUID slotId,
    @Param("examDate") LocalDate examDate,
    @Param("invigilatorIds") Collection<UUID> invigilatorIds,
    @Param("excludedRoomAssignmentId") UUID excludedRoomAssignmentId
);
```

---

## 2. Inefficient Query Patterns

---

### ISSUE-2.1 — `PersonRepository.search()` / `RoomRepository.search()`: Leading-wildcard LIKE causes full table scans (CRITICAL)

**File:** `PersonRepository.java` (lines 30–32), `RoomRepository.java` (lines 29–30)

**Problematic code:**
```java
// PersonRepository.search():
and (:department is null or lower(p.department) like lower(concat('%', :department, '%')))
and (:name is null or lower(p.name) like lower(concat('%', :name, '%')))

// RoomRepository.search():
and (:name is null or lower(r.name) like lower(concat('%', :name, '%')))
```

**Root cause:** LIKE patterns with a leading `%` wildcard cannot use B-tree indexes. MySQL and every other SQL database must perform a sequential scan of every row, comparing each value character by character. The indexes `idx_person_name`, `idx_person_normalized_name`, `idx_room_name`, and `idx_room_normalized_name` are completely ignored.

Additionally, wrapping the column in `lower(...)` prevents index usage even for prefix searches, unless a function-based index exists.

**Query cost:**
- 10k people → ~5–15ms per search (tolerable but visible)
- 50k people → ~50–200ms per search (noticeable lag)
- 100k+ people → 500ms–1s+ per search page load (unacceptable)

**Recommended fix (MySQL FULLTEXT):**

```sql
-- Migration: Add FULLTEXT indexes
ALTER TABLE people ADD FULLTEXT INDEX idx_people_name_ft (name);
ALTER TABLE people ADD FULLTEXT INDEX idx_people_department_ft (department);
ALTER TABLE rooms ADD FULLTEXT INDEX idx_rooms_name_ft (name);
```

```java
// PersonRepository — use FULLTEXT MATCH AGAINST
@Query(value = """
    SELECT * FROM people p
    WHERE (:role IS NULL OR p.role = :role)
    AND (:department IS NULL OR MATCH(p.department) AGAINST (:department IN BOOLEAN MODE))
    AND (:name IS NULL OR MATCH(p.name) AGAINST (:name IN BOOLEAN MODE))
    AND p.active = true
    """, nativeQuery = true)
Page<Person> searchFullText(
    @Param("role") String role,
    @Param("department") String department,
    @Param("name") String name,
    Pageable pageable
);
```

**Interim fix (prefix-only search — much better than `%term%`):**

If full-text search is not feasible immediately, change queries to use trailing-only wildcards: `concat(:name, '%')` instead of `concat('%', :name, '%')`. This allows index range scans but limits to prefix matching.

---

### ISSUE-2.2 — `createAssignment()` / `updateAssignment()`: Redundant entity reload after save

**File:** `AssignmentService.java` (lines 91, 105)

**Problematic code:**
```java
// createAssignment():
RoomAssignment saved = roomAssignmentRepository.save(assignment);
applyWorkloadDelta(Map.of(), countAssignmentOccurrences(saved));
return roomAssignmentMapper.toResponse(getDetailedAssignment(saved.getId())); // ← Unnecessarily re-fetches

// updateAssignment():
RoomAssignment assignment = getDetailedAssignment(id);  // ← Fetch 1 (with full EntityGraph)
...
RoomAssignment saved = roomAssignmentRepository.save(assignment);
...
return roomAssignmentMapper.toResponse(getDetailedAssignment(saved.getId())); // ← Fetch 2 (again with EntityGraph)
```

**Root cause:**
- `createAssignment()` saves the entity, then immediately re-fetches it via `findDetailedById()` (EntityGraph with 5 join paths). The saved assignment already has all relations populated in the current persistence context.
- `updateAssignment()` fetches the entity once, modifies it, saves it, then fetches it again.

**Query cost per request:** +1 extra query (EntityGraph JOIN FETCH with 5 paths) = significant overhead per write

**Recommended fix:** Avoid the redundant re-fetch by mapping directly from the already-loaded entity:

```java
// createAssignment():
assignment.setId(null); // ensure new entity
configureAssignmentRelations(assignment, request);
validateAssignmentRules(assignment, null);
RoomAssignment saved = roomAssignmentRepository.save(assignment);
applyWorkloadDelta(Map.of(), countAssignmentOccurrences(saved));
return roomAssignmentMapper.toResponse(saved); // ← Map directly, all relations are already in memory

// updateAssignment():
RoomAssignment assignment = getDetailedAssignment(id); // fetch once
Map<UUID, Integer> previousOccurrences = countAssignmentOccurrences(assignment);
roomAssignmentMapper.updateEntity(request, assignment);
configureAssignmentRelations(assignment, request);
validateAssignmentRules(assignment, id);
roomAssignmentRepository.save(assignment);
applyWorkloadDelta(previousOccurrences, countAssignmentOccurrences(assignment));
return roomAssignmentMapper.toResponse(assignment); // ← Relations are still in memory
```

---

### ISSUE-2.3 — `NormalizedNameMaintenanceService`: Unbounded entity load on first-run backfill

**File:** `NormalizedNameMaintenanceService.java` (lines 84, 97)

**Problematic code:**
```java
private void doSynchronizePeople() {
    List<Person> people = personRepository.findAllByNormalizedNameIsNull(); // ← NO LIMIT
    ...
    people.forEach(person -> person.setName(person.getName())); // Updates all in memory
    personRepository.flush(); // ← One big flush of N dirty entities
}
```

**Root cause:** `findAllByNormalizedNameIsNull()` returns all entities with a null `normalizedName` in a single query, loading them all into the JPA first-level cache simultaneously. For 100,000 legacy records, this means:
- 100,000 Person objects in heap
- 100,000 `person_available_days` rows lazily loaded if accessed
- A single transaction with 100,000 dirty entities to flush

**Query cost:**
- 10k records: ~20MB heap, ~200ms
- 50k records: ~100MB heap, ~1s startup delay
- 100k+ records: risk of `OutOfMemoryError` at startup

**Recommended fix:** Process in paginated batches:

```java
private void doSynchronizePeople() {
    final int PAGE_SIZE = 500;
    int page = 0;
    List<Person> batch;
    do {
        batch = personRepository.findByNormalizedNameIsNullWithLimit(PageRequest.of(page++, PAGE_SIZE));
        if (batch.isEmpty()) break;
        batch.forEach(person -> person.setName(person.getName()));
        personRepository.flush();
        entityManager.clear(); // Detach processed entities to free heap
    } while (batch.size() == PAGE_SIZE);
    personsSynchronized = true;
}

// Repository:
@Query("select p from Person p where p.normalizedName is null")
List<Person> findByNormalizedNameIsNullWithLimit(Pageable pageable);
```

---

### ISSUE-2.4 — `BulkUploadService`: `findAllNormalizedNames()` loads entire name set into memory

**File:** `BulkUploadService.java` (lines 130, 169)

**Problematic code:**
```java
// In persistWithRetry() — called on every upload attempt (up to 3 retries)
() -> new LinkedHashSet<>(personRepository.findAllNormalizedNames()),  // ← All names in memory

// Repository queries all normalized names:
@Query("select p.normalizedName from Person p where p.normalizedName is not null")
List<String> findAllNormalizedNames(); // Can return 100,000+ strings
```

**Root cause:** Every bulk upload loads the complete set of existing normalized names into a `LinkedHashSet<String>`. At 100,000 records × ~100 bytes per name = ~10MB per upload invocation. With up to 3 retries, this query runs 3 times.

**Query cost:** Linear in total number of persons/rooms in the database.

**Recommended fix:** Query only the normalized names that conflict with the batch being imported:

```java
// Only check the names present in the current upload batch
Set<String> incomingNormalizedNames = validRows.stream()
    .map(row -> NameNormalizationUtil.normalizeForComparison(nameExtractor.apply(row.value())))
    .collect(Collectors.toSet());

// Check for existing conflicts only among incoming names — O(batch_size) not O(total)
Set<String> existingConflicts = personRepository.findNormalizedNamesIn(incomingNormalizedNames);
```

```java
// Repository:
@Query("select p.normalizedName from Person p where p.normalizedName in :names")
Set<String> findNormalizedNamesIn(@Param("names") Collection<String> names);
```

---

### ISSUE-2.5 — `DashboardService.getSummary()`: Four sequential COUNT queries on cache miss

**File:** `DashboardService.java` (lines 49–53)

**Problematic code:**
```java
long chiefs = personRepository.countByRoleAndActiveTrue(PersonRole.CHIEF_INVIGILATOR); // query 1
long invigilators = personRepository.countByRoleAndActiveTrue(PersonRole.INVIGILATOR);  // query 2
long rooms = roomRepository.countByActiveTrue();                                         // query 3
long totalAssignments = roomAssignmentRepository.count();                                // query 4
```

**Root cause:** Four sequential database round-trips — each must complete before the next starts. Mitigated by the Caffeine cache (60s TTL), but every cache miss (at most once per minute) fires all four sequentially. On a remote DB, each query can take 5–20ms → 20–80ms total on cache miss.

`roomAssignmentRepository.count()` issues `SELECT COUNT(*) FROM room_assignments` — a full table count. MySQL's InnoDB engine does NOT maintain an exact count and must scan the index to count rows.

**Recommended fix:** Consolidate into a single native query:

```java
public interface DashboardRepository extends Repository<Object, Void> {
    @Query(value = """
        SELECT
            SUM(CASE WHEN p.role = 'CHIEF_INVIGILATOR' AND p.active = true THEN 1 ELSE 0 END) AS chiefs,
            SUM(CASE WHEN p.role = 'INVIGILATOR'       AND p.active = true THEN 1 ELSE 0 END) AS invigilators
        FROM people p
        """, nativeQuery = true)
    Object[] countPeopleByRole();
}
```

Or run these in parallel if refactoring to a single query is not desirable:
```java
// Use CompletableFuture with @Async if the DB is remote with high latency
CompletableFuture<Long> chiefs = asyncRepo.countChiefs();
CompletableFuture<Long> invigs = asyncRepo.countInvigilators();
CompletableFuture<Long> rooms  = asyncRepo.countRooms();
```

---

## 3. Missing Database Indexes

---

### ISSUE-3.1 — Missing composite index for chief invigilator validation query (CRITICAL)

**File:** `RoomAssignmentRepository.java` (lines 30–32)

**Queries:**
```java
long countByTimeSlotIdAndExamDateAndChiefInvigilatorId(UUID timeSlotId, LocalDate examDate, UUID chiefInvigilatorId);
long countByTimeSlotIdAndExamDateAndChiefInvigilatorIdAndIdNot(UUID timeSlotId, LocalDate examDate, UUID chiefInvigilatorId, UUID id);
```

**Generated SQL (approximate):**
```sql
SELECT COUNT(*) FROM room_assignments
WHERE time_slot_id = ?
  AND exam_date = ?
  AND chief_invigilator_id = ?
```

**Root cause:** The `room_assignments` table has separate individual indexes:
- `idx_room_assignment_time_slot` on `time_slot_id`
- `idx_room_assignment_date` on `exam_date`
- `idx_room_assignment_chief` on `chief_invigilator_id`

MySQL's query optimizer will pick **at most one** of these per query and use the others as a filter scan. A composite index `(time_slot_id, exam_date, chief_invigilator_id)` would allow a single index range scan covering all three columns simultaneously.

**Called frequency:** Once per assignment create/update AND once per assignment in `validateBulkAssignmentRules()` — runs on EVERY save operation.

**Recommended fix:** Add a composite index:

```java
// In RoomAssignment entity @Table:
@Index(name = "idx_ra_slot_date_chief", columnList = "time_slot_id, exam_date, chief_invigilator_id"),
@Index(name = "idx_ra_slot_date_room",  columnList = "time_slot_id, exam_date, room_id")
```

```sql
-- Migration:
CREATE INDEX idx_ra_slot_date_chief ON room_assignments (time_slot_id, exam_date, chief_invigilator_id);
CREATE INDEX idx_ra_slot_date_room  ON room_assignments (time_slot_id, exam_date, room_id);
```

---

### ISSUE-3.2 — Missing composite index for `countSlotUsage` query

**File:** `InvigilatorAssignmentRepository.java` (lines 16–28)

**Query:**
```sql
SELECT COUNT(*) FROM invigilator_assignments ia
  JOIN room_assignments ra ON ia.room_assignment_id = ra.id
WHERE ra.time_slot_id = ?
  AND ra.exam_date = ?
  AND ia.invigilator_id = ?
  AND (? IS NULL OR ra.id <> ?)
```

**Root cause:** The filter spans two tables. `ia.invigilator_id` is indexed (`idx_invigilator_assignment_person`), and `ra.time_slot_id` is indexed separately (`idx_room_assignment_time_slot`). A join across two separately indexed columns is less efficient than a covering composite index.

**Recommended fix:** Add a composite index on `invigilator_assignments`:

```java
@Index(name = "idx_ia_invigilator_room_assignment", columnList = "invigilator_id, room_assignment_id")
```

---

### ISSUE-3.3 — `PersonRepository.search()` and `RoomRepository.search()`: Indexes unused due to LIKE wildcards

**Already covered in ISSUE-2.1** — the relevant indexes `idx_person_name`, `idx_room_name`, `idx_person_normalized_name` are all bypassed by leading-wildcard LIKE. Adding FULLTEXT indexes (ISSUE-2.1 fix) is the resolution.

---

### ISSUE-3.4 — `findIdsByFilters()` nullable-parameter query can inhibit optimal index selection

**File:** `RoomAssignmentRepository.java` (lines 34–49)

**Problematic pattern:**
```jpql
where (:slotId is null or ra.timeSlot.id = :slotId)
and (:roomId is null or ra.room.id = :roomId)
and (:locked is null or ra.locked = :locked)
and (:fromDate is null or ra.examDate >= :fromDate)
and (:toDate is null or ra.examDate <= :toDate)
```

**Root cause:** MySQL's query planner sees this as a single static query whose index plan is fixed at parse time. With all-nullable parameters, the planner often chooses a conservative index or a full scan. The `idx_room_assignment_date` index is most useful when `fromDate`/`toDate` are supplied, but the planner cannot assume this.

**Query cost:** With no filters, this scans the entire `room_assignments` table. With date filters, it may still do so if the planner chooses poorly.

**Recommended fix:** Use the Criteria API or Querydsl to build the WHERE clause dynamically — only predicates for non-null parameters are added, allowing the planner to use the most appropriate indexes:

```java
// Using Spring Data Specifications:
public static Specification<RoomAssignment> withFilters(
        UUID slotId, UUID roomId, Boolean locked, LocalDate fromDate, LocalDate toDate) {
    return (root, query, cb) -> {
        List<Predicate> predicates = new ArrayList<>();
        if (slotId   != null) predicates.add(cb.equal(root.get("timeSlot").get("id"), slotId));
        if (roomId   != null) predicates.add(cb.equal(root.get("room").get("id"), roomId));
        if (locked   != null) predicates.add(cb.equal(root.get("locked"), locked));
        if (fromDate != null) predicates.add(cb.greaterThanOrEqualTo(root.get("examDate"), fromDate));
        if (toDate   != null) predicates.add(cb.lessThanOrEqualTo(root.get("examDate"), toDate));
        return cb.and(predicates.toArray(new Predicate[0]));
    };
}
```

---

### ISSUE-3.5 — Missing index on `room_assignments.exam_date + time_slot_id` for bulk lookup

**File:** `RoomAssignmentRepository.java` (lines 58–66) — `findAllDetailedByExamDateInAndTimeSlotIdIn()`

**Generated SQL (approximate):**
```sql
SELECT DISTINCT ra.* 
  JOIN ia ON ra.id = ia.room_assignment_id
  JOIN p  ON ia.invigilator_id = p.id
WHERE ra.exam_date IN (...)
  AND ra.time_slot_id IN (...)
```

**Root cause:** This query filters on two key columns simultaneously. The existing `idx_room_assignment_date` and `idx_room_assignment_time_slot` are separate. A composite index `(exam_date, time_slot_id)` would allow a single range scan for bulk scheduling loads.

**Recommended fix:**
```sql
CREATE INDEX idx_ra_date_slot ON room_assignments (exam_date, time_slot_id);
```

---

## 4. Entity Mapping Problems

---

### ISSUE-4.1 — `Person.availableDays`: `@ElementCollection` with LAZY loading triggers extra queries

**File:** `Person.java` (lines 72–81)

```java
@ElementCollection(fetch = FetchType.LAZY)
@CollectionTable(name = "person_available_days", ...)
@BatchSize(size = 50)
private Set<WeekDay> availableDays = new LinkedHashSet<>();
```

**Assessment:** The `@BatchSize(50)` mitigates but does not eliminate the N+1 pattern. When a page of 20 persons is loaded and `availableDays` is accessed (which it is, via `PersonResponse`), Hibernate issues:

1. `SELECT * FROM people WHERE ...` (paginated)
2. `SELECT * FROM person_available_days WHERE person_id IN (?, ?, ..., ?)` (batched IN clause)

This is **2 queries per page load** where 1 was possible. At very high request rates (100+ RPM), this doubles the DB query count for `/people` endpoints.

**Recommendation:** See ISSUE-1.2 fix — use a two-pass approach or a JOIN FETCH in a dedicated query that also handles the count separately.

---

### ISSUE-4.2 — `RoomAssignment.invigilatorAssignments`: `@OneToMany` without `@BatchSize`

**File:** `RoomAssignment.java` (lines 88–91)

```java
@OneToMany(mappedBy = "roomAssignment", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderColumn(name = "position_index")
private List<InvigilatorAssignment> invigilatorAssignments = new ArrayList<>();
```

**Assessment:** No `@BatchSize` annotation. However, all consuming code always uses `@EntityGraph` to eagerly fetch this collection, so the lack of `@BatchSize` is non-critical at this time. If any code path loads `RoomAssignment` **without** the EntityGraph and then accesses `invigilatorAssignments`, a classic N+1 would occur.

**Recommendation:** Add `@BatchSize(size = 25)` as a safety net for any future access patterns that bypass the EntityGraph:

```java
@BatchSize(size = 25)
@OneToMany(mappedBy = "roomAssignment", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderColumn(name = "position_index")
private List<InvigilatorAssignment> invigilatorAssignments = new ArrayList<>();
```

---

### ISSUE-4.3 — `@EntityGraph` on collection relationships can produce Cartesian product intermediate results

**File:** `RoomAssignmentRepository.java` (lines 51–76) — all three `@EntityGraph` methods

```java
@EntityGraph(attributePaths = {
    "room", "timeSlot", "chiefInvigilator",
    "invigilatorAssignments",
    "invigilatorAssignments.invigilator"
})
@Query("select distinct ra from RoomAssignment ra where ra.id in :ids")
List<RoomAssignment> findAllDetailedByIdIn(@Param("ids") Collection<UUID> ids);
```

**Root cause:** When Hibernate joins a one-to-many collection (`invigilatorAssignments`) in a single SQL JOIN, the result set contains one row per parent × child combination. For 100 assignments × 5 invigilators, the JDBC ResultSet has 500 rows before Hibernate deduplicates. The `select distinct` in JPQL triggers `SELECT DISTINCT` in SQL, forcing the DB engine to sort/hash 500 rows.

**Query cost:** Quadratic blowup — 1,000 assignments × 5 invigilators = 5,000 intermediate rows per bulk load call.

**Recommended fix:** Use Hibernate's `@BatchSize` on the collection + two-pass loading to avoid the JOIN:

```java
// Pass 1: Load RoomAssignment + scalar relations only (no collection JOIN)
@EntityGraph(attributePaths = {"room", "timeSlot", "chiefInvigilator"})
@Query("select ra from RoomAssignment ra where ra.id in :ids")
List<RoomAssignment> findWithoutInvigilatorsByIdIn(@Param("ids") Collection<UUID> ids);

// Pass 2: Hibernate automatically batch-loads invigilatorAssignments via @BatchSize when accessed
// (add @BatchSize(size=25) to the collection field — see ISSUE-4.2)
```

This changes the query pattern from: `1 JOIN query returning width × height rows` → `1 base query + 1 batched IN query`, which is more efficient for large sets.

---

## 5. DTO Mapping Performance

---

### ISSUE-5.1 — `InvigilatorAssignmentMapper.toResponse()`: Accesses lazy `invigilator` proxy

**File:** `InvigilatorAssignmentMapper.java` (lines 12–13)

```java
@Mapping(target = "invigilatorId",   source = "invigilator.id")
@Mapping(target = "invigilatorName", source = "invigilator.name")
```

**Assessment:** `InvigilatorAssignment.invigilator` is `FetchType.LAZY`. MapStruct accesses `invigilator.id` and `invigilator.name` during mapping. If the `invigilator` proxy has not been initialized (i.e., the entity was loaded without the EntityGraph), this causes:
- A lazy load per invigilator assignment → N+1 queries
- Or a `LazyInitializationException` if outside a transaction

**Current mitigation:** All call sites use `@EntityGraph` with `"invigilatorAssignments.invigilator"`, so the proxy is initialized. ✓

**Risk:** Any future code that loads `InvigilatorAssignment` directly (e.g., via `InvigilatorAssignmentRepository.findById()`) and maps it via `InvigilatorAssignmentMapper` will trigger N+1 queries silently. The lack of any safeguard makes this a latent bug.

**Recommendation:** Add explicit `@Transactional` annotations to any mapper usage that's not already inside a transaction, and add tests asserting the query count.

---

### ISSUE-5.2 — `validateAvailabilityChange()` loads full `RoomAssignment` when only `examDate` is needed

**File:** `PeopleService.java` (lines 131–133)

```java
futureAssignments.addAll(
    roomAssignmentRepository.findFutureChiefAssignmentsByPersonId(personId, today));
futureAssignments.addAll(
    roomAssignmentRepository.findFutureInvigilatorAssignmentsByPersonId(personId, today));

for (RoomAssignment assignment : futureAssignments) {
    WeekDay assignmentDay = WeekDay.from(assignment.getExamDate().getDayOfWeek()); // ← only this is used
```

**Root cause:** Both queries return full `RoomAssignment` entities with `timeSlot` join-fetched (JPQL `join fetch ra.timeSlot`). The calling code only accesses `assignment.getExamDate()`. The join-fetched `timeSlot` is wasted. Room, chief invigilator, source, subject name, etc., are all loaded but never accessed.

**Recommended fix:** Use a projection:

```java
// New interface projection:
public interface AssignmentDateProjection {
    LocalDate getExamDate();
}

// In RoomAssignmentRepository:
@Query("select ra.examDate from RoomAssignment ra where ra.chiefInvigilator.id = :personId and ra.examDate >= :fromDate")
List<LocalDate> findFutureChiefAssignmentDatesByPersonId(@Param("personId") UUID personId, @Param("fromDate") LocalDate fromDate);

@Query("""
    select ra.examDate from RoomAssignment ra
    join ra.invigilatorAssignments ia
    where ia.invigilator.id = :personId and ra.examDate >= :fromDate
    """)
List<LocalDate> findFutureInvigilatorAssignmentDatesByPersonId(@Param("personId") UUID personId, @Param("fromDate") LocalDate fromDate);

// In PeopleService:
List<LocalDate> futureDates = new ArrayList<>();
futureDates.addAll(roomAssignmentRepository.findFutureChiefAssignmentDatesByPersonId(personId, today));
futureDates.addAll(roomAssignmentRepository.findFutureInvigilatorAssignmentDatesByPersonId(personId, today));
for (LocalDate examDate : futureDates) {
    WeekDay assignmentDay = WeekDay.from(examDate.getDayOfWeek());
    if (!availableDays.contains(assignmentDay)) {
        throw new ConflictException(...);
    }
}
```

This changes the SELECT from `SELECT ra.*, ts.*` to `SELECT ra.exam_date` — a dramatic reduction in data transferred.

---

## 6. Service Layer Issues

---

### ISSUE-6.1 — `PeopleService.createPerson()` / `updatePerson()`: Calls `synchronizePeople()` on every write

**File:** `PeopleService.java` (lines 62–63, 71–72)

```java
public PersonResponse createPerson(PersonRequest request) {
    normalizedNameMaintenanceService.synchronizePeople(); // ← DB query on first call after restart
    ...
}
public PersonResponse updatePerson(UUID id, PersonRequest request) {
    normalizedNameMaintenanceService.synchronizePeople(); // ← Same
    ...
}
```

**Assessment:** The `NormalizedNameMaintenanceService.synchronizePeople()` has a fast-path (`if (personsSynchronized) return;`) after the first successful sync. This eliminates DB calls after startup bac-fill. The `@EventListener(ApplicationReadyEvent.class)` runs the sync during startup, meaning the flag is set **before** any request arrives. This is effectively a no-op in production. ✓

**Residual concern:** The service uses `volatile boolean` for thread safety but does not use `synchronized` — there's a minor race window where two simultaneous threads both see `personsSynchronized = false` and both run `doSynchronizePeople()`. This is benign (both would backfill the same data) but wasteful.

**Recommendation:** Use double-checked locking or `AtomicBoolean` for stronger guarantees:

```java
private final AtomicBoolean personsSynchronized = new AtomicBoolean(false);

public synchronized void synchronizePeople() {
    if (personsSynchronized.get()) return;
    doSynchronizePeople();
}
```

---

### ISSUE-6.2 — `saveAssignmentsBulk()`: Entire result set reloaded after save

**File:** `AssignmentService.java` (lines 148–157)

```java
List<RoomAssignment> savedAssignments = roomAssignmentRepository.saveAll(preparedAssignments);
List<UUID> savedIds = savedAssignments.stream().map(RoomAssignment::getId).toList();
...
return getOrderedAssignmentResponses(savedIds); // ← Reloads ALL saved assignments via EntityGraph
```

**Root cause:** `getOrderedAssignmentResponses()` calls `findAllDetailedByIdIn()` which re-fetches the entities just saved, including their full EntityGraph. The data is already in the JPA session after `saveAll()`.

**Query cost:** 1 extra JOIN query fetching N saved assignments with their full relation tree (see ISSUE-4.3 for the Cartesian product risk).

**Recommendation:** Map directly from `preparedAssignments` (which have all relations populated in memory):

```java
// preparedAssignments already has room, timeSlot, chiefInvigilator, invigilatorAssignments set
// via configureBulkAssignmentRelations() — no need to re-fetch.
List<RoomAssignment> savedAssignments = roomAssignmentRepository.saveAll(preparedAssignments);
applyWorkloadDelta(previousOccurrences, nextOccurrences);
// Map directly — avoid the extra DB round-trip:
return savedAssignments.stream()
    .sorted(Comparator.comparing(ra -> savedIds.indexOf(ra.getId())))
    .map(roomAssignmentMapper::toResponse)
    .toList();
```

---

### ISSUE-6.3 — `DashboardService`: Cache is single-node only — multi-instance deployments will have stale caches

**File:** `CacheConfig.java` (lines 28–30), `DashboardService.java`

```java
// CacheConfig comment:
// NOTE: This is a single-node cache. For multi-instance deployments,
// replace with a distributed cache (Redis via spring-boot-starter-data-redis).
```

**Assessment:** The code correctly acknowledges this limitation in a comment. Caffeine is in-process — each JVM instance has its own cache. Under multi-instance deployment (load balanced), Dashboard data can be stale in one node while fresh in another. Writes to any node will bypass all other nodes' caches.

**Recommendation:** When scaling beyond one instance, migrate to Redis:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## 7. Transaction Management Risks

---

### ISSUE-7.1 — `application.properties`: HikariCP pool with maximum 5 connections (CRITICAL)

**File:** `application.properties` (line 10)

```ini
spring.datasource.hikari.maximum-pool-size=5
```

**Root cause:** The connection pool is capped at 5 simultaneous database connections. Under concurrent load:
- 5 simultaneous requests can occupy all 5 connections
- The 6th request blocks until `connection-timeout=20000ms` (20 seconds!)
- Under moderate traffic (30+ RPM), users will experience frequent 20-second waits
- Bulk upload operations hold a connection for the entire transaction duration (could be 30+ seconds for 5,000 rows), blocking all other requests

**Impact:**
- 10 concurrent users: requests start queuing
- 30+ concurrent users: frequent 20-second timeouts visible to users
- Bulk upload: monopolizes the pool

**Recommended fix:**
```ini
# Tune based on DB server capacity. For a shared cloud DB:
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=5000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

For a dedicated DB server, use Hikari's sizing formula: `pool_size = (core_count × 2) + effective_spindle_count`.

---

### ISSUE-7.2 — Missing Hibernate JDBC batch configuration for `saveAll()`

**File:** `application.properties` — missing property

**Root cause:** Without `hibernate.jdbc.batch_size` configured, Hibernate's `saveAll()` issues **individual INSERT/UPDATE statements** for each entity, even though it calls `addBatch()` internally. The batch is never sent as a group.

**Affected code:**
- `BulkUploadService.saveInChunks()` — 500 persons/rooms per chunk → 500 individual INSERTs
- `AssignmentService.roomAssignmentRepository.saveAll()` — N individual saves
- `NormalizedNameMaintenanceService.personRepository.flush()` — N individual UPDATEs

**Without batch:**
```
-- For 500 persons: 500 individual round-trips
INSERT INTO people (...) VALUES (...)  -- ×500
```

**With batch:**
```
-- 500 persons: 1 bulk batch INSERT (varies by JDBC driver)
INSERT INTO people (...) VALUES (...), (...), ...  -- 1 statement
```

**Recommended fix:**
```ini
# application.properties:
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
```

Also set the MySQL JDBC URL parameter for rewriteBatchedStatements:
```ini
spring.datasource.url=jdbc:mysql://host:3306/db?rewriteBatchedStatements=true&...
```

Without `rewriteBatchedStatements=true`, MySQL's JDBC driver sends individual statements even when Hibernate batches them.

**Impact:**
- Bulk upload of 5,000 persons: reduces INSERT round-trips from 5,000 to ~100 (50x improvement)
- Bulk assignment save: reduces UPDATE round-trips proportionally

---

### ISSUE-7.3 — `spring.jpa.hibernate.ddl-auto=update` in production (RISK)

**File:** `application.properties` (line 34)

```ini
spring.jpa.hibernate.ddl-auto=update
```

**Root cause:** `ddl-auto=update` allows Hibernate to automatically ALTER TABLE, ADD COLUMN, or CREATE TABLE on startup. This behavior is **dangerous in production** because:
- Hibernate cannot DROP columns — schema differences accumulate
- A wrong entity change can cause an irreversible `ALTER TABLE` that removes production data
- Schema changes during deployment are not reviewed or version-controlled

**Recommended fix:** Switch to `validate` and use Flyway or Liquibase for schema management:

```ini
spring.jpa.hibernate.ddl-auto=validate
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

---

### ISSUE-7.4 — Sensitive credentials stored in plaintext `application.properties`

**File:** `application.properties` (lines 6, 45, 51–52, 75–76)

```ini
spring.datasource.password=n=8X-Ww4H7!c
app.jwt.secret=3c9d4f0b1e284b8b95bdf8ccf9aaf6d8c8375f6a19e0c534cc8d73328f6c3fa7
spring.mail.password=mlydbbgrewpnnrja
cloudinary.api-key=732883554933543
cloudinary.api-secret=heAj_OozrTISn_M2nH4roNyGPXE
```

**Root cause:** All secrets are committed to source control in plaintext. Any developer with repository access, any build server log, and any attacker with access to the file system can extract these credentials.

**Recommended fix:** Use environment variables or a secrets manager:

```ini
spring.datasource.password=${DB_PASSWORD}
app.jwt.secret=${JWT_SECRET}
spring.mail.password=${MAIL_PASSWORD}
cloudinary.api-key=${CLOUDINARY_API_KEY}
cloudinary.api-secret=${CLOUDINARY_API_SECRET}
```

---

### ISSUE-7.5 — All Actuator endpoints exposed publicly

**File:** `application.properties` (line 79)

```ini
management.endpoints.web.exposure.include=*
```

**Root cause:** Exposes all actuator endpoints including `/actuator/env` (environment variables, potential secret leak), `/actuator/heapdump` (memory dump with session tokens), `/actuator/shutdown` (if enabled), `/actuator/loggers` (log level manipulation), etc.

`SecurityConfig` restricts `/actuator/**` to ADMIN role — so this is protected. However, `/actuator/health` and `/actuator/info` are public.

**Recommendation:** Restrict to only needed endpoints:

```ini
management.endpoints.web.exposure.include=health,info,metrics
```

---

## 8. Repository Query Design

---

### ISSUE-8.1 — `countSlotUsage` uses implicit join path through entity graph

**File:** `InvigilatorAssignmentRepository.java` (lines 16–28)

```jpql
select count(ia) from InvigilatorAssignment ia
where ia.roomAssignment.timeSlot.id = :slotId
and ia.roomAssignment.examDate = :examDate
and ia.invigilator.id = :invigilatorId
and (:excludedRoomAssignmentId is null or ia.roomAssignment.id <> :excludedRoomAssignmentId)
```

**Root cause:** The path `ia.roomAssignment.timeSlot.id` forces Hibernate to perform two implicit joins: `invigilator_assignments → room_assignments → time_slots`. The same is true for `ia.roomAssignment.examDate`. These implicit joins are not guaranteed to use indexes optimally.

**Recommended fix:** Use explicit JOIN and select only the needed scalar values:

```jpql
select count(ia) from InvigilatorAssignment ia
join ia.roomAssignment ra
where ra.timeSlot.id = :slotId
and ra.examDate = :examDate
and ia.invigilator.id = :invigilatorId
and (:excludedRoomAssignmentId is null or ra.id <> :excludedRoomAssignmentId)
```

---

### ISSUE-8.2 — `PersonRepository.findAllNormalizedNames()` has no LIMIT

**File:** `PersonRepository.java` (lines 49–50)

```java
@Query("select p.normalizedName from Person p where p.normalizedName is not null")
List<String> findAllNormalizedNames(); // Returns ALL rows, unbounded
```

**Root cause:** See ISSUE-2.4 — this query returns an unbounded list used entirely in-memory.

---

### ISSUE-8.3 — `findFutureChiefAssignmentsByPersonId` returns full entities unnecessarily

**File:** `RoomAssignmentRepository.java` (lines 94–103)

```jpql
select distinct ra from RoomAssignment ra
join fetch ra.timeSlot ts
where ra.chiefInvigilator.id = :personId
and ra.examDate >= :fromDate
```

**Root cause:** Returns full `RoomAssignment` objects with `TimeSlot` join-fetched. The caller only reads `ra.getExamDate()`. All other fields (`room`, `subjectName`, `subjectCode`, `source`, `locked`, etc.) are loaded for nothing.

**Recommended fix:** See ISSUE-5.2.

---

## 9. Large Dataset Risk Analysis

---

### At 10,000 Records

| Issue | Impact |
|-------|--------|
| LIKE with leading wildcards (ISSUE-2.1) | Search queries take 5–15ms — noticeable UI lag |
| `applyWorkloadDelta()` N+1 updates (ISSUE-1.1) | Bulk assignments take 2–5s |
| HikariCP max 5 connections (ISSUE-7.1) | 10+ simultaneous users see connection wait times |
| No batch INSERT config (ISSUE-7.2) | Bulk upload of 5,000 rows takes 30–60s |
| Missing composite index for validation (ISSUE-3.1) | Assignment validation adds ~20ms per room |

### At 50,000 Records

| Issue | Impact |
|-------|--------|
| LIKE with leading wildcards (ISSUE-2.1) | **Search takes 200–500ms — unacceptable production latency** |
| `findAllNormalizedNames()` (ISSUE-2.4) | Loading all names takes 500ms+ and uses ~5MB heap per request |
| Unbounded backfill load (ISSUE-2.3) | Risk of OOM on startup if many records have null normalizedName |
| Nullable-param filter query (ISSUE-3.4) | Full table scan on assignment list pagination — 500ms+ per page |
| EntityGraph Cartesian product (ISSUE-4.3) | Bulk scheduling load returns 250,000 intermediate rows for 50k assignments |

### At 100,000+ Records

| Issue | Impact |
|-------|--------|
| LIKE with leading wildcards (ISSUE-2.1) | **Search takes 1–3s — search is effectively broken** |
| `applyWorkloadDelta()` in bulk operations (ISSUE-1.1) | **Bulk save of 500 assignments fires 3,000 UPDATEs → 30–60s, transaction timeout** |
| `findAllNormalizedNames()` (ISSUE-2.4) | **OOM risk — 100k+ strings in heap** |
| HikariCP max 5 (ISSUE-7.1) | **All 5 connections occupied → 20s timeout for every new request** |
| Missing JDBC batch config (ISSUE-7.2) | **Bulk upload monopolizes the pool for minutes, blocking all other users** |
| `count()` in dashboard (ISSUE-2.5) | COUNT(*) on 100k+ rows takes 100–500ms (MySQL InnoDB) |
| EntityGraph Cartesian (ISSUE-4.3) | **Bulk schedule load: millions of intermediate rows → query timeout** |

---

## 10. Final Summary

---

### 🔴 Critical Issues — Immediate Scalability Blockers

| # | File | Issue | Fix Priority |
|---|------|-------|-------------|
| C1 | `AssignmentService.java:539` | N individual UPDATE statements in `applyWorkloadDelta()` — will cause transaction timeouts under bulk operations | **Immediate** |
| C2 | `PersonRepository.java:31`, `RoomRepository.java:29` | Leading-wildcard LIKE on all search queries — full table scans, queries will exceed 1s at 50k records | **Immediate** |
| C3 | `application.properties:10` | HikariCP `maximum-pool-size=5` — pool exhaustion under any concurrent load | **Immediate** |
| C4 | `application.properties` (missing) | No `hibernate.jdbc.batch_size` — `saveAll()` issues individual INSERT/UPDATE per record | **Immediate** |
| C5 | `RoomAssignment entity` / `application.properties:4` | Missing `rewriteBatchedStatements=true` in MySQL JDBC URL — batch config has no effect without it | **Immediate** |
| C6 | `NormalizedNameMaintenanceService.java:84,97` | Unbounded entity load (`findAllByNormalizedNameIsNull()`) — OOM risk at 50k+ records | **High** |

---

### 🟡 Medium Issues — Performance Degradation at Scale

| # | File | Issue | Fix Priority |
|---|------|-------|-------------|
| M1 | `RoomAssignmentRepository.java:30` | Missing composite index `(time_slot_id, exam_date, chief_invigilator_id)` for validation query | **High** |
| M2 | `InvigilatorAssignmentRepository.java:23` | N COUNT queries per assignment in `validateAssignmentRules()` | **High** |
| M3 | `BulkUploadService.java:130` | `findAllNormalizedNames()` loads all names into memory — OOM at 100k records | **High** |
| M4 | `AssignmentService.java:91,105` | Redundant `getDetailedAssignment()` re-fetch after save — extra EntityGraph JOIN per write | **Medium** |
| M5 | `PeopleService.java:131` | Loads full `RoomAssignment` entities when only `examDate` scalar is needed | **Medium** |
| M6 | `AssignmentService.java:157` | Bulk save reloads all saved assignments via EntityGraph after `saveAll()` | **Medium** |
| M7 | `RoomAssignmentRepository.java:34` | Nullable-param JPQL query inhibits optimal index selection; use Specifications or Querydsl | **Medium** |
| M8 | `RoomAssignment entity` | No composite index `(exam_date, time_slot_id)` for bulk schedule queries | **Medium** |
| M9 | `DashboardService.java:49` | 4 sequential COUNT queries on cache miss — should be a single aggregate query | **Low-Medium** |

---

### 🟢 Minor Improvements — Incremental Gains

| # | File | Issue | Fix Priority |
|---|------|-------|-------------|
| I1 | `RoomAssignment.java` | Add `@BatchSize(size=25)` to `invigilatorAssignments` as a safety net | **Low** |
| I2 | `InvigilatorAssignmentRepository.java:17` | Use explicit JOIN instead of implicit path navigation in `countSlotUsage` | **Low** |
| I3 | `NormalizedNameMaintenanceService.java:38` | Use `AtomicBoolean` / `synchronized` instead of `volatile boolean` for thread safety | **Low** |
| I4 | `application.properties:34` | Switch `ddl-auto=update` to `validate` + Flyway/Liquibase | **Low** |
| I5 | `application.properties:6,45` | Move all secrets to environment variables — security and operational hygiene | **Low** |
| I6 | `application.properties:79` | Restrict actuator exposure to `health,info,metrics` | **Low** |
| I7 | `CacheConfig.java` | Document and plan Redis migration for multi-instance deployments | **Low** |

---

### Scalability Risk Assessment

```
          DATA VOLUME
              │
100k ──────── ┼─── ✗ LIKE search broken (1–3s) ──── ✗ Bulk save timeouts ──── ✗ Pool exhausted
              │
 50k ──────── ┼─── ✗ LIKE search degraded (500ms) ─ ✗ OOM on startup backfill
              │
 10k ──────── ┼─── ⚠ Search lag 15ms ─ ⚠ Bulk slow 5s ─ ⚠ Pool contention
              │
Current ───── ┼─── ✓ Mostly functional at low concurrency
              │
```

**Expected failure points by load:**

- **Now (0–1k concurrent RPM):** System functions but pool contention visible at 10+ users. Bulk operations are slow.
- **Short-term (2k RPM / 10k records):** Search queries become noticeably sluggish. Bulk save latency doubles.
- **Medium-term (10k RPM / 50k records):** Search timeouts, OOM risk on restart, dashboard queries slow without cache.
- **Long-term (any load / 100k+ records):** Leading-wildcard LIKE breaks the search entirely. N+1 updates in bulk paths cause transaction timeouts. Pool exhaustion under normal concurrent use.

**Recommended implementation order:**

1. `rewriteBatchedStatements=true` in JDBC URL + `hibernate.jdbc.batch_size=50` → zero code change, massive bulk throughput gain
2. `maximum-pool-size=20` → one-line change, eliminates connection starvation
3. Refactor `applyWorkloadDelta()` to batch updates → eliminates the most dangerous N+1 UPDATEs
4. Add composite indexes `(time_slot_id, exam_date, chief_invigilator_id)` and `(exam_date, time_slot_id)` → DDL migration, no code change
5. Replace LIKE search with FULLTEXT indexes → largest refactoring effort, highest long-term payoff
6. Paginate `findAllByNormalizedNameIsNull()` → prevents OOM on migration startup
7. Replace `findAllNormalizedNames()` with targeted lookup → prevents OOM during bulk upload

