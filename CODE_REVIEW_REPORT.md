# Comprehensive Technical Code Review — `schedule-backend`

> **Stack:** Spring Boot 3.5.0 · Java 23 · JPA/Hibernate · MySQL · JJWT 0.12.6 · MapStruct · Apache POI
> **Domain:** University exam invigilator scheduling system
> **Review Date:** May 6, 2026

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Critical Issues](#2-critical-issues)
3. [Performance Bottlenecks](#3-performance-bottlenecks)
4. [Database Problems](#4-database-problems)
5. [Security Risks](#5-security-risks)
6. [Architecture Concerns](#6-architecture-concerns)
7. [Code Quality Issues](#7-code-quality-issues)
8. [Improvement Recommendations](#8-improvement-recommendations)
9. [Estimated Impact of Fixes](#9-estimated-impact-of-fixes)

---

## 1. Executive Summary

The project is a well-structured, single-module Spring Boot REST API implementing a classic layered
architecture (Controller → Service → Repository → Entity). The developer demonstrates solid understanding
of Spring patterns: constructor injection, `@Transactional(readOnly = true)` service defaults, paginated
ID-first queries, EntityGraph-based eager loading, MapStruct mappers, and appropriate use of JPA audit fields.

However, **several critical issues make this system unsuitable for production in its current form**,
primarily in the areas of secrets management, JWT security design, CORS misconfiguration, and actuator
exposure. There are also meaningful performance concerns around double JWT parsing per request, O(n²) bulk
validation, and unbounded in-memory data loading. The codebase has no automated tests, no schema migration
tooling, and no caching layer.

---

## 2. Critical Issues

---

### 🔴 CRITICAL-1 — Plaintext Credentials Committed to Source Code

- **File:** `src/main/resources/application.properties` (Lines 4–6, 27, 42, 49–50, 72–73)
- **Severity:** Critical | Performance Impact: 1/5 | Security Risk: 5/5 | Maintainability: 4/5

**Offending Code:**
```properties
spring.datasource.url=jdbc:mysql://db50370.public.databaseasp.net:3306/db50370?...
spring.datasource.username=db50370
spring.datasource.password=n=8X-Ww4H7!c                    # Line 6
app.bootstrap-admin.password=ChangeMe123!                   # Line 27
app.jwt.secret=3c9d4f0b1e284b8b95bdf8ccf9aaf6d8c8375f6a... # Line 42
spring.mail.password=mlydbbgrewpnnrja                       # Line 50
cloudinary.api-secret=heAj_OozrTISn_M2nH4roNyGPXE          # Line 73
```

**Root Cause:** All secrets are stored as plaintext in a version-controlled properties file.

**Explanation:** An attacker with repository access (via a leaked token, a misconfigured private repo,
or a supply-chain incident) can immediately gain full access to the production database, forge JWT tokens
indefinitely, send emails from the application's mail account, and access all Cloudinary stored assets.
The database connection string also reveals the public host and username.

**Recommendation:** Use Spring Profiles with `application-prod.properties` excluded from VCS, environment
variable injection via `${DB_PASSWORD}`, or a secrets manager (Vault, AWS Secrets Manager). Rotate all
exposed secrets immediately.

---

### 🔴 CRITICAL-2 — H2 Console Unauthenticated and Globally Whitelisted

- **File:** `src/main/java/.../config/SecurityConfig.java` (Line 64)
- **Severity:** Critical | Performance Impact: 1/5 | Security Risk: 5/5 | Maintainability: 2/5

**Offending Code:**
```java
.requestMatchers("/api/auth/login", "/h2-console/**").permitAll()
```

**Root Cause:** The H2 database console endpoint is unconditionally whitelisted regardless of active
profile or database backend.

**Explanation:** Although the runtime database is MySQL, the H2 driver is on the classpath (`pom.xml`
lines 66–68, scope `runtime`). The H2 console route is globally permit-all — no authentication required.
If H2 is ever misconfigured or the datasource URL changed accidentally, the embedded database becomes
publicly accessible.

**Recommendation:** Restrict to a `@Profile("dev")` configuration bean, or remove the exemption entirely.
H2 console should never appear in production security configurations.

---

### 🔴 CRITICAL-3 — CORS Wildcard With `allowCredentials(true)` — Protocol Violation & Security Risk

- **File:** `application.properties` (Line 22) & `SecurityConfig.java` (Lines 94–99)
- **Severity:** Critical | Security Risk: 5/5 | Maintainability: 2/5

**Offending Code:**
```java
// SecurityConfig.java
configuration.setAllowedOriginPatterns(corsProperties.resolvedAllowedOriginPatterns()); // resolves to ["*"]
configuration.setAllowCredentials(true); // Line 98
```
```properties
# application.properties
app.cors.allowed-origin-patterns[0]=*   # Line 22
```

**Root Cause:** CORS policy combines `allowedOriginPatterns=["*"]` with `allowCredentials(true)`.
`CorsProperties.resolvedAllowedOriginPatterns()` falls back to `["*"]` if nothing is configured.

**Explanation:** Per the CORS specification, `Access-Control-Allow-Credentials: true` cannot be paired
with a wildcard origin — browsers reject this combination. More critically, the fallback to `["*"]` means
any misconfiguration silently opens CORS to all origins with credentials. This enables cross-site request
forgery via credentialed CORS requests from any domain.

**Recommendation:** Set explicit allowed origins per environment. Never combine `allowedOrigins(["*"])`
with `allowCredentials(true)`.

---

### 🔴 CRITICAL-4 — Spring Actuator Fully Exposed Without Authentication

- **File:** `src/main/resources/application.properties` (Lines 76–83)
- **Severity:** Critical | Security Risk: 5/5 | Maintainability: 3/5

**Offending Code:**
```properties
management.endpoints.web.exposure.include=*
management.endpoint.health.show-details=always
```

**Root Cause:** All actuator endpoints are exposed with no security filter applied.

**Explanation:** The security config applies JWT authentication to `/api/**` but the default Actuator
base path is `/actuator/**`. Since there is no rule protecting `/actuator/**`, any unauthenticated user
can access:
- `/actuator/env` — reveals all environment variables (including secrets moved to env vars)
- `/actuator/beans` — full Spring context map
- `/actuator/mappings` — all API routes
- `/actuator/heapdump` — full JVM heap dump
- `/actuator/shutdown` — if enabled, kills the process

**Recommendation:** Either restrict Actuator behind authentication
(`.requestMatchers("/actuator/**").hasRole("ADMIN")`), use a separate management port, or expose only
`health` and `info`.

---

### 🔴 CRITICAL-5 — No JWT Revocation + 7-Day Token Lifetime

- **File:** `application.properties` (Line 43) & `JwtTokenProvider.java`
- **Severity:** Critical | Security Risk: 4/5 | Maintainability: 3/5

**Offending Code:**
```properties
app.jwt.expiration-minutes=10080  # 7 days = 10,080 minutes
```

**Root Cause:** Stateless JWT with no blacklist and 7-day expiry. No logout endpoint exists.

**Explanation:** Once a token is issued, it is valid for 7 full days regardless of whether the admin
has been deactivated, their password changed, or a compromise detected. An attacker who steals a token
has a 7-day window to operate undetected with no server-side revocation possible.

**Recommendation:** Reduce expiry to 15–30 minutes, implement a refresh token pattern, and add a token
revocation store (e.g., Redis-backed blacklist keyed on JWT ID `jti` claim).

---

## 3. Performance Bottlenecks

---

### 🟠 PERF-1 — JWT Parsed Twice Per Request + Signing Key Recomputed on Every Call

- **File:** `JwtAuthenticationFilter.java` (Lines 54–57) & `JwtTokenProvider.java` (Lines 53–63)
- **Severity:** High | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 3/5

**Offending Code:**
```java
// JwtAuthenticationFilter.java — two full HMAC parse operations per request
String subject = jwtTokenProvider.extractSubject(token);       // parseClaims() #1
UserDetails userDetails = adminUserDetailsService.loadUserByUsername(subject);
if (jwtTokenProvider.isValid(token, userDetails)) {            // parseClaims() #2
```
```java
// JwtTokenProvider.java — recomputed on every call
private SecretKey signingKey() {
    return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
}
```

**Root Cause:** No caching of parsed claims or the signing key object.

**Explanation:** Every authenticated request triggers two full JWT HMAC-SHA256 signature verification
operations. The `signingKey()` method re-encodes the secret bytes on each operation (called at least
twice per request). The signing key is deterministic and immutable and should be computed once at startup.

**Recommendation:** Cache the `SecretKey` as a `@PostConstruct`-initialized field. Redesign
`extractSubject()` and `isValid()` to share a single parse call.

---

### 🟠 PERF-2 — Database Query on Every Authenticated Request (No UserDetails Cache)

- **File:** `JwtAuthenticationFilter.java` (Line 55) & `AdminUserDetailsService.java` (Line 22)
- **Severity:** High | Performance Impact: 4/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
// Called on every single HTTP request with a valid Bearer token
UserDetails userDetails = adminUserDetailsService.loadUserByUsername(subject);
// → adminUserRepository.findByEmailIgnoreCase(username)  — DB round-trip every time
```

**Root Cause:** No caching configured on `UserDetailsService`.

**Explanation:** For every HTTP request with a valid Bearer token, the application makes a full database
round-trip to load the admin user record. Since the JWT has already been cryptographically verified,
this DB call adds latency for zero security benefit. Under moderate load (100 req/s), this generates
100 unnecessary DB queries per second competing for connection pool slots.

**Recommendation:** Apply Spring Cache (`@Cacheable`) on `loadUserByUsername`, or extract trust from
the cryptographically verified JWT claims directly without a DB lookup.

---

### 🟠 PERF-3 — O(n²) Complexity in `validateBulkAssignmentRules`

- **File:** `AssignmentService.java` (Lines 464–499)
- **Severity:** High | Performance Impact: 4/5 | Security Risk: 1/5 | Maintainability: 3/5

**Offending Code:**
```java
chiefCounts.forEach((key, counts) -> counts.forEach((personId, count) -> {
    RoomAssignment sample = assignments.stream()            // O(n) scan per entry!
        .filter(assignment -> assignment.getChiefInvigilator() != null
                && assignment.getChiefInvigilator().getId().equals(personId)
                && SlotOccurrenceKey.from(assignment).equals(key))
        .findFirst().orElse(null);
    // ... same pattern repeated for invigilator counts (lines 481–498)
}));
```

**Root Cause:** Linear stream scans performed inside a double-iteration loop.

**Explanation:** For each `(SlotOccurrenceKey, personId)` entry, the code performs a full linear scan
over all `assignments` to find an example record. With a large bulk operation (200 rooms × 50
invigilators), this becomes tens of thousands of comparisons. The pattern repeats for both chief and
invigilator validations.

**Recommendation:** Build a lookup `Map<SlotOccurrenceKey, Map<UUID, RoomAssignment>>` once upfront
so all individual lookups are O(1). The entire validation then becomes O(n).

---

### 🟠 PERF-4 — `Person.availableDays` Eagerly Loaded on Every Person Fetch (N+1 Problem)

- **File:** `Person.java` (Lines 60–64)
- **Severity:** High | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
@ElementCollection(fetch = FetchType.EAGER)   // ← triggers a secondary SQL per Person
@CollectionTable(name = "person_available_days", joinColumns = @JoinColumn(name = "person_id"))
@Column(name = "available_day", nullable = false, length = 16)
@Enumerated(EnumType.STRING)
private Set<WeekDay> availableDays = new LinkedHashSet<>();
```

**Root Cause:** `FetchType.EAGER` on `@ElementCollection`.

**Explanation:** Every time a `Person` entity is loaded — including via paginated search queries — a
secondary SQL query is issued to fetch `person_available_days`. For a page of 20 people, this generates
1 (people query) + 20 (available days queries) = 21 queries. This is a classic N+1 problem. The issue is
compounded in bulk operations where potentially hundreds of people are loaded.

**Recommendation:** Change to `FetchType.LAZY`. Join-fetch `availableDays` only in assignments where
availability validation is needed. Alternatively, store available days as a bitmask in a single column
to eliminate the join table entirely.

---

### 🟠 PERF-5 — `sameSlotRoom()` Used in O(n × m) Nested Stream Filter

- **File:** `AssignmentService.java` (Lines 136–138)
- **Severity:** Medium | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
List<RoomAssignment> toDelete = existingAssignments.stream()
    .filter(existing -> preparedAssignments.stream()     // O(m) per existing item
        .noneMatch(prepared -> sameSlotRoom(existing, prepared)))
    .toList();
```

**Root Cause:** Nested stream iteration without a hash lookup structure.

**Explanation:** Each `existing` assignment is compared against every `prepared` assignment, resulting
in O(existing × prepared) operations. A `Set<AssignmentCompositeKey>` built from `preparedAssignments`
would reduce all membership checks to O(1).

---

### 🟡 PERF-6 — `NormalizedNameMaintenanceService` Called on Every Write Operation

- **File:** `PeopleService.java` (Lines 62, 71), `RoomService.java` (Lines 53, 61)
- **Severity:** Medium | Performance Impact: 2/5 | Security Risk: 1/5 | Maintainability: 3/5

**Offending Code:**
```java
public PersonResponse createPerson(PersonRequest request) {
    normalizedNameMaintenanceService.synchronizePeople(); // DB query on every create
    ...
```

**Root Cause:** A data consistency repair routine runs synchronously on every write operation.

**Explanation:** `synchronizePeople()` queries `findAllByNormalizedNameIsNull()` before every person
upsert as a fallback for records predating the `normalizedName` column. Once the backfill is complete,
this is an unnecessary DB round-trip on every write path. It also creates a subtle concurrency issue:
two parallel creates both see no null-normalized records, proceed, and still race.

---

### 🟡 PERF-7 — Dashboard Issues 4 Sequential DB Count Queries With No Caching

- **File:** `DashboardService.java` (Lines 36–43)
- **Severity:** Medium | Performance Impact: 2/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
long chiefs = personRepository.countByRoleAndActiveTrue(PersonRole.CHIEF_INVIGILATOR);
long invigilators = personRepository.countByRoleAndActiveTrue(PersonRole.INVIGILATOR);
long rooms = roomRepository.countByActiveTrue();
long totalAssignments = roomAssignmentRepository.count(); // Unbounded full-table count
```

**Root Cause:** No caching; no query aggregation; sequential execution.

**Explanation:** Each dashboard load fires 4 separate DB queries synchronously. The `count()` on
`room_assignments` is an unbounded full-table-scan aggregate. None of these values change sub-second,
making them ideal cache candidates.

**Recommendation:** Apply `@Cacheable` with a short TTL (e.g., 60 seconds). Alternatively, execute
in parallel via `CompletableFuture`.

---

### 🟡 PERF-8 — `PersonRepository.search()` LIKE With Leading Wildcard Disables Indexes

- **File:** `PersonRepository.java` (Lines 29–38)
- **Severity:** Medium | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
and (:department is null or lower(p.department) like lower(concat('%', :department, '%')))
and (:name is null or lower(p.name) like lower(concat('%', :name, '%')))
```

**Root Cause:** Leading-wildcard LIKE patterns prevent B-tree index usage.

**Explanation:** The `idx_person_name` index on `people.name` is completely unused because
`LIKE '%foo%'` cannot leverage B-tree indexes. The `lower()` function wrapping further prevents
function-based index use. Under large datasets, every search degrades to a full table scan.

**Recommendation:** Use MySQL `FULLTEXT` indexes with `MATCH ... AGAINST` for full-text search.
For prefix-only matching, remove the leading `%`. Consider Elasticsearch for production-grade search.

---

## 4. Database Problems

---

### 🔴 DB-1 — `spring.jpa.hibernate.ddl-auto=update` in Production Configuration

- **File:** `src/main/resources/application.properties` (Line 31)
- **Severity:** Critical | Performance Impact: 2/5 | Security Risk: 3/5 | Maintainability: 4/5

**Offending Code:**
```properties
spring.jpa.hibernate.ddl-auto=update
```

**Root Cause:** No schema migration tooling; relying on Hibernate's auto-DDL.

**Explanation:** `update` mode is fundamentally unsafe for production:
- It cannot drop columns or constraints (causing silent data divergence)
- It cannot detect destructive renames
- It can deadlock under concurrent startup (rolling deployments)
- It provides no rollback capability
- Two instances starting simultaneously both attempt DDL mutations, causing race conditions

**Recommendation:** Switch to `validate` in production. Adopt **Flyway** or **Liquibase** for
versioned, repeatable schema migrations with proper rollback support.

---

### 🟠 DB-2 — Missing Composite Index for the `countSlotUsage` Query

- **File:** `InvigilatorAssignmentRepository.java` (Lines 16–28) & `InvigilatorAssignment.java`
- **Severity:** High | Performance Impact: 4/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
@Query("""
    select count(ia) from InvigilatorAssignment ia
    where ia.roomAssignment.timeSlot.id = :slotId
    and ia.roomAssignment.examDate = :examDate
    and ia.invigilator.id = :invigilatorId
    and (:excludedRoomAssignmentId is null or ia.roomAssignment.id <> :excludedRoomAssignmentId)
    """)
long countSlotUsage(...);
```

**Root Cause:** The frequent constraint-checking query spans two tables with no covering index.

**Explanation:** `countSlotUsage()` is called for every invigilator in `validateAssignmentRules()`.
The `invigilator_assignments` table only has `idx_invigilator_assignment_person` on `invigilator_id`.
The join condition on `room_assignment_id` and filters on `time_slot_id` / `exam_date` (which live in
`room_assignments`) are unindexed for this query pattern.

**Recommendation:** Add a composite index on `room_assignments(time_slot_id, exam_date)` and ensure
`invigilator_assignments(room_assignment_id)` has an index.

---

### 🟠 DB-3 — Missing Index on `invigilator_assignments.room_assignment_id`

- **File:** `InvigilatorAssignment.java` (Line 35)
- **Severity:** High | Performance Impact: 4/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "room_assignment_id", nullable = false)
private RoomAssignment roomAssignment;
// ↑ No @Index annotation on the FK column
```

**Root Cause:** No `@Index` declared on the foreign key column `room_assignment_id`.

**Explanation:** Every time `RoomAssignment.invigilatorAssignments` is loaded via EntityGraph, Hibernate
joins on `room_assignment_id`. Without an index on this column, every join is a full table scan. On a
table with thousands of invigilator assignments, this is severe for every assignment detail fetch.

**Recommendation:** Add `@Index(name = "idx_invigilator_assignment_room", columnList = "room_assignment_id")`
to `InvigilatorAssignment`'s `@Table` annotation.

---

### 🟠 DB-4 — `person_available_days` Collection Table Has No Index

- **File:** `Person.java` (Lines 60–64) — the generated `person_available_days` table
- **Severity:** High | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "person_available_days", joinColumns = @JoinColumn(name = "person_id"))
// ↑ No indexes defined on the collection table
```

**Root Cause:** Hibernate does not automatically create an index on the join column of `@CollectionTable`.

**Explanation:** Every fetch of `availableDays` requires a full scan of `person_available_days` on the
`person_id` column. In bulk operations loading hundreds of people (see PERF-4), this is compounded severely.

**Recommendation:**
```java
@CollectionTable(
    name = "person_available_days",
    joinColumns = @JoinColumn(name = "person_id"),
    indexes = @Index(name = "idx_person_available_days_person", columnList = "person_id")
)
```

---

### 🟡 DB-5 — HikariCP Pool Capped at 5 Connections

- **File:** `src/main/resources/application.properties` (Lines 10–15)
- **Severity:** Medium | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 2/5

**Offending Code:**
```properties
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
spring.datasource.hikari.idle-timeout=30000
spring.datasource.hikari.keepalive-time=30000  # same as idle-timeout — defeats the purpose
```

**Root Cause:** Very conservative pool sizing; `idle-timeout` equals `keepalive-time`.

**Explanation:** With 5 max connections, a burst of just 5–10 concurrent requests will exhaust the pool
and queue requests, causing `connection-timeout` (20s as configured). The `idle-timeout` and
`keepalive-time` being identically set (30000ms) means idle connections are returned before the keepalive
fires, defeating the keepalive purpose entirely.

**Recommendation:** Size using HikariCP formula: `cores × 2 + effective_spindle_count`. For a remote
cloud DB, 10–20 connections are typically appropriate. Set `idle-timeout` > `keepalive-time`.

---

### 🟡 DB-6 — `Person.totalAssignments` Is an Eventually Inconsistent Denormalized Counter

- **File:** `AssignmentService.java` (Lines 537–544), `PersonRepository.java` (Lines 41–43)
- **Severity:** Medium | Performance Impact: 2/5 | Security Risk: 1/5 | Maintainability: 4/5

**Offending Code:**
```java
private void applyWorkloadDelta(Map<UUID, Integer> before, Map<UUID, Integer> after) {
    Set<UUID> personIds = new HashSet<>(before.keySet());
    personIds.addAll(after.keySet());
    for (UUID personId : personIds) {
        int delta = after.getOrDefault(personId, 0) - before.getOrDefault(personId, 0);
        if (delta != 0) personRepository.adjustTotalAssignments(personId, delta);
    }
}
```

**Root Cause:** Denormalized counter maintained via application-level delta with no atomic guarantee.

**Explanation:** If a bulk operation partially commits or any step in `applyWorkloadDelta()` fails after
the assignment is saved, the counter will drift with no reconciliation path. There is no scheduled
reconciliation job or integrity check.

**Recommendation:** Accept the counter as an approximation and add a scheduled reconciliation job that
recounts from source truth, or replace it with a derived query `COUNT(*)` guarded by `@Cacheable`.

---

## 5. Security Risks

---

### 🟠 SEC-1 — JWT Signing Key Derived From a Potentially Weak, Hardcoded Secret

- **File:** `JwtTokenProvider.java` (Lines 61–63) & `application.properties` (Line 42)
- **Severity:** High | Security Risk: 4/5 | Performance Impact: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
private SecretKey signingKey() {
    return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
}
```
```properties
app.jwt.secret=3c9d4f0b1e284b8b95bdf8ccf9aaf6d8c8375f6a19e0c534cc8d73328f6c3fa7
```

**Root Cause:** HMAC-SHA256 key derived from a static, hardcoded string with no rotation mechanism.

**Explanation:** The key is 64 hex characters = 256 bits (the bare minimum for HMAC-SHA256). Since it
is hardcoded (CRITICAL-1), any key rotation requires a full redeployment plus immediate invalidation
of all outstanding tokens — an impossible operation without a revocation mechanism.

---

### 🟠 SEC-2 — No Rate Limiting on the Login Endpoint

- **File:** `AuthController.java` & `SecurityConfig.java` (Line 64)
- **Severity:** High | Security Risk: 4/5 | Performance Impact: 2/5 | Maintainability: 2/5

**Root Cause:** No request throttling, account lockout, or brute-force protection on `/api/auth/login`.

**Explanation:** The login endpoint is permit-all with no rate limiting. An attacker can attempt unlimited
username/password combinations programmatically. BCrypt (correctly used here) slows individual checks,
but an automated attack can still attempt thousands of passwords per minute given enough connections and time.

**Recommendation:** Implement rate limiting (Bucket4j + Redis), Spring Security's account locking, or
add API Gateway throttling at the network layer.

---

### 🟠 SEC-3 — `equalsIgnoreCase()` for JWT Subject Comparison

- **File:** `JwtTokenProvider.java` (Line 46)
- **Severity:** Medium | Security Risk: 3/5 | Performance Impact: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
return claims.getSubject().equalsIgnoreCase(userDetails.getUsername())
```

**Root Cause:** Case-insensitive comparison used in a security-critical equality check.

**Explanation:** While appropriate for email-based subjects (RFC 5321), `equalsIgnoreCase` is dangerous
if future authentication paths use case-sensitive identifiers (like UUIDs or usernames). Since
`AdminUserDetailsService` already normalizes emails to lowercase, the case-insensitive comparison is
redundant and obscures intent.

**Recommendation:** Use `equals()` after normalizing both sides to lowercase explicitly.

---

### 🟠 SEC-4 — Swagger UI Enabled Without Profile Restriction

- **File:** `src/main/resources/application.properties` (Lines 61–67)
- **Severity:** Medium | Security Risk: 3/5 | Maintainability: 2/5

**Offending Code:**
```properties
springdoc.swagger-ui.enabled=true
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.api-docs.path=/v3/api-docs
```

**Explanation:** Swagger UI is accessible in production without authentication, giving attackers a
complete API map: all endpoints, request/response shapes, parameter names, and expected error codes —
dramatically reducing reconnaissance cost.

**Recommendation:** Restrict Swagger to `@Profile("dev")` or protect the `/swagger-ui.html` and
`/v3/api-docs` routes with the security filter chain.

---

### 🟡 SEC-5 — `InvigilatorAssignment.invigilator` FK Is Nullable Without DB Enforcement

- **File:** `InvigilatorAssignment.java` (Lines 38–40)
- **Severity:** Medium | Security Risk: 2/5 | Maintainability: 3/5

**Offending Code:**
```java
@ManyToOne(fetch = FetchType.LAZY)    // no optional = false
@JoinColumn(name = "invigilator_id")  // no nullable = false
private Person invigilator;
```

**Explanation:** A slot marked `required = true` with `invigilator = null` is a business constraint
violation that is permitted at the database schema level. The service code handles nulls throughout
(e.g. `if (ia.getInvigilator() == null) continue`), meaning incomplete scheduling data can be
persisted without triggering any constraint.

---

## 6. Architecture Concerns

---

### 🟠 ARCH-1 — Business Logic Embedded in Entity Setters (Violates SRP)

- **File:** `Person.java` (Lines 98–113)
- **Severity:** High | Performance Impact: 1/5 | Security Risk: 2/5 | Maintainability: 4/5

**Offending Code:**
```java
public void setName(String name) {
    this.name = NameNormalizationUtil.normalizeWhitespace(name);
    this.normalizedName = NameNormalizationUtil.normalizeForComparison(this.name); // Side effect
}

public void setRole(PersonRole role) {
    this.role = role;
    this.maxParallelRooms = (role == PersonRole.CHIEF_INVIGILATOR) ? 2 : 1; // Business rule
}
```

**Root Cause:** Entities embedding business transformation and derivation logic in setters.

**Explanation:** When Hibernate reconstructs entities from the database, it calls setters, meaning
`setName()` recomputes `normalizedName` on every entity load — redundant and wasteful. More critically,
if any code calls `setNormalizedName()` separately (a public setter exists at line 104), `name` and
`normalizedName` can diverge. The `setMaxParallelRooms()` public setter at line 125 also allows direct
override of the derived value, silently breaking the invariant.

**Recommendation:** Use `@Access(AccessType.FIELD)` on entities. Move name normalization to a service
layer event. Remove public setters for derived fields (`normalizedName`, `maxParallelRooms`).

---

### 🟠 ARCH-2 — `validateAvailabilityChange()` Loads ALL Historical Assignments Into Memory

- **File:** `PeopleService.java` (Lines 119–134)
- **Severity:** High | Performance Impact: 3/5 | Security Risk: 1/5 | Maintainability: 3/5

**Offending Code:**
```java
List<RoomAssignment> assignments = new ArrayList<>();
assignments.addAll(roomAssignmentRepository.findAllChiefAssignmentsByPersonId(personId));
assignments.addAll(roomAssignmentRepository.findAllInvigilatorAssignmentsByPersonId(personId));
// ↑ No date filter — loads ALL historical assignments including past ones
```

**Root Cause:** No date filter applied; entire scheduling history loaded into the JVM heap.

**Explanation:** A person who has been used across multiple exam periods may have hundreds or thousands
of assignment records. All are loaded on every availability update, including past assignments that are
already immutable and cannot be violated. This is unnecessary work with unbounded heap growth.

**Recommendation:** Filter by `examDate >= LocalDate.now()` — only future assignments can be violated
by an availability change.

---

### 🟠 ARCH-3 — `ScheduleService` Is an Uncommitted Stub

- **File:** `ScheduleService.java` (entire file)
- **Severity:** Medium | Performance Impact: 1/5 | Security Risk: 1/5 | Maintainability: 3/5

**Offending Code:**
```java
@Service
public class ScheduleService {
    public void validateDateSlot(UUID timeSlotId) { }   // Empty — does nothing
    public void generateDateSlot(UUID timeSlotId) { }   // Empty — does nothing
}
```

**Root Cause:** Incomplete implementation committed to main branch.

**Explanation:** Two entirely empty methods with no implementation, no TODOs, no exceptions thrown.
If other services depend on these no-ops in the future, silent incorrect behavior will result.

**Recommendation:** Either remove the class, add `throw new UnsupportedOperationException(...)`, or
file a tracked story for the planned implementation.

---

### 🟡 ARCH-4 — Flat Admin User Model With No RBAC

- **File:** `AdminUser.java`, `AdminUserDetailsService.java`
- **Severity:** Medium | Security Risk: 3/5 | Maintainability: 4/5

**Explanation:** All authenticated users receive the hardcoded `"ADMIN"` role with no differentiation
between a read-only viewer, a scheduler, and a system administrator. Any legitimate admin can delete
all people, rooms, and assignments. There is no audit log linking who performed which action.

---

### 🟡 ARCH-5 — No Caching Layer Anywhere in the Application

- **File:** All services
- **Severity:** Medium | Performance Impact: 3/5 | Maintainability: 2/5

**Explanation:** Frequently read, rarely changed data (time slots, settings, room lists, dashboard
counts) are re-queried from the database on every request. Time slots are referenced on every assignment
validation but fetched fresh from DB each time. No `@EnableCaching`, no `@Cacheable`, no cache
configuration exists anywhere.

---

### 🟡 ARCH-6 — No Test Coverage

- **File:** `ScheduleApplicationTests.java` (the only test file — empty default)
- **Severity:** High | Performance Impact: 1/5 | Maintainability: 5/5

**Explanation:** The only test class is the Spring Boot auto-generated empty context load test. There
are no unit tests, no integration tests, and no slice tests for any of the following:
- Assignment validation rules (`validateAssignmentRules`, `validateBulkAssignmentRules`)
- Bulk operation logic (`saveAssignmentsBulk`, `persistWithRetry`)
- Name normalization and duplicate resolution
- JWT generation and validation
- Controller input validation

Without tests, none of the fixes recommended in this report can be made with confidence.

---

## 7. Code Quality Issues

---

### 🟡 CQ-1 — Duplicate Properties in `application.properties`

- **File:** `src/main/resources/application.properties` (Lines 33/58, 37/57)
- **Severity:** Medium | Maintainability: 3/5

**Offending Code:**
```properties
spring.main.banner-mode=off           # Line 37 — first occurrence
logging.level.org.hibernate.SQL=DEBUG # Line 33 — intended for debugging

# ... 20 lines later ...

spring.main.banner-mode=off           # Line 57 — duplicate (harmless but confusing)
logging.level.org.hibernate.SQL=ERROR # Line 58 — overrides line 33 (silently defeats debugging)
```

**Explanation:** A developer who adds `DEBUG` for SQL debugging at line 33 will receive no SQL output
because `ERROR` overrides it 25 lines later. This is a silent debugging trap.

---

### 🟡 CQ-2 — `ExcelParserUtil.RowValues` Is a Non-Static Inner Class

- **File:** `ExcelParserUtil.java` (Lines 90–105)
- **Severity:** Low | Performance Impact: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
public final class RowValues {  // Non-static — holds implicit reference to ExcelParserUtil instance
    private final Row row;
    private final DataFormatter formatter;
    private final FormulaEvaluator evaluator;
    ...
}
```

**Explanation:** `RowValues` holds an implicit reference to the enclosing `ExcelParserUtil` singleton.
Every `RowValues` instance (one per row, per parse operation) prevents GC of the outer instance's memory
until all row objects are collected. In large file processing, this is a subtle memory pressure issue.

**Recommendation:** Declare `RowValues` as `static`.

---

### 🟡 CQ-3 — `DuplicateResolverUtil.resolveUniqueName()` Mutates Input Parameter

- **File:** `DuplicateResolverUtil.java` (Lines 19, 30)
- **Severity:** Medium | Maintainability: 3/5

**Offending Code:**
```java
if (!reservedNormalizedNames.contains(normalizedBaseName)) {
    reservedNormalizedNames.add(normalizedBaseName); // ← Mutates the caller's Set!
    return new ResolvedName(baseName, false);
}
```

**Explanation:** The `reservedNormalizedNames` Set passed from `BulkUploadService` is mutated in-place.
In the retry loop (`persistWithRetry`, up to 3 retries), names resolved in a failed attempt pollute
the retry's reserved set, potentially causing valid retry names to be treated as duplicates.

**Recommendation:** Document the mutation contract explicitly, or isolate mutation to prevent
cross-attempt contamination by copying the set at the start of each retry attempt.

---

### 🟡 CQ-4 — `countAssignmentOccurrences(Collection)` Sorts Unnecessarily

- **File:** `AssignmentService.java` (Lines 522–529)
- **Severity:** Low | Performance Impact: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
assignments.stream()
    .sorted(Comparator.comparing(RoomAssignment::getExamDate)) // Pointless — sum is commutative
    .forEach(assignment -> countAssignmentOccurrences(assignment)
        .forEach((personId, value) -> counts.merge(personId, value, Integer::sum)));
```

**Explanation:** Summing counts is commutative — ordering has no effect on the result. The `.sorted()`
call adds unnecessary O(n log n) overhead for zero benefit. Should be removed.

---

### 🟡 CQ-5 — `validateRoomRow()` Calls `normalizeWhitespace()` Twice on the Same Value

- **File:** `BulkUploadService.java` (Line 242)
- **Severity:** Low | Performance Impact: 1/5 | Maintainability: 2/5

**Offending Code:**
```java
if (NameNormalizationUtil.normalizeWhitespace(source.type()) == null
    || NameNormalizationUtil.normalizeWhitespace(source.type()).isBlank()) {
// ↑ normalizeWhitespace called twice on the same input — should be stored in a local variable
```

---

### 🟡 CQ-6 — `AdminUser` Entity Has No Audit Fields

- **File:** `AdminUser.java`
- **Severity:** Low | Security Risk: 2/5 | Maintainability: 3/5

**Explanation:** Unlike every other entity, `AdminUser` has no `createdAt`, `updatedAt`, or
`@EntityListeners(AuditingEntityListener.class)`. There is no way to determine when the admin account
was created or last modified — information that is critical for security audits and compliance reviews.

---

## 8. Improvement Recommendations

Ordered by priority and estimated effort:

| Priority | Recommendation | Effort |
|----------|---------------|--------|
| 🔴 **P0** | Rotate ALL exposed credentials immediately (DB, JWT, Gmail, Cloudinary) | 1 day |
| 🔴 **P0** | Move all secrets to environment variables / Vault — never commit to VCS | 2 days |
| 🔴 **P0** | Remove H2 console from security whitelist OR profile-gate it to `dev` only | 1 hour |
| 🔴 **P0** | Lock down Actuator — authenticate all endpoints or restrict to management port | 2 hours |
| 🔴 **P0** | Fix CORS policy — explicit origins per environment, never wildcard + credentials | 1 hour |
| 🟠 **P1** | Migrate to Flyway/Liquibase — replace `ddl-auto=update` with `validate` | 3 days |
| 🟠 **P1** | Add JWT revocation — implement refresh tokens + short-lived access tokens (15 min) | 2 days |
| 🟠 **P1** | Cache `SecretKey` in `JwtTokenProvider` at `@PostConstruct` — compute once | 30 min |
| 🟠 **P1** | Cache `UserDetails` or embed role in JWT claims — eliminate per-request DB call | 4 hours |
| 🟠 **P1** | Add rate limiting on login endpoint (Bucket4j + Redis or API Gateway) | 1 day |
| 🟠 **P1** | Change `availableDays` to `FetchType.LAZY` + explicit join-fetch where needed | 2 hours |
| 🟠 **P1** | Add `idx_invigilator_assignment_room` index on `room_assignment_id` column | 30 min |
| 🟠 **P1** | Add composite index on `room_assignments(time_slot_id, exam_date)` | 30 min |
| 🟠 **P1** | Add index on `person_available_days(person_id)` via `@CollectionTable` | 30 min |
| 🟡 **P2** | Refactor `validateBulkAssignmentRules` from O(n²) to O(n) using index maps | 1 day |
| 🟡 **P2** | Add `@Cacheable` on dashboard stats and time slot lookups | 1 day |
| 🟡 **P2** | Filter `validateAvailabilityChange` to future dates only (`examDate >= today`) | 1 hour |
| 🟡 **P2** | Restrict Swagger UI to `@Profile("dev")` | 1 hour |
| 🟡 **P2** | Increase HikariCP pool size; fix `idle-timeout` > `keepalive-time` | 30 min |
| 🟡 **P2** | Remove or implement `ScheduleService` (currently empty no-op) | 2 hours |
| 🟡 **P2** | Write unit + integration tests for services and controllers | 1 week |
| 🟢 **P3** | Make `RowValues` in `ExcelParserUtil` a `static` nested class | 15 min |
| 🟢 **P3** | Fix duplicate properties in `application.properties` | 15 min |
| 🟢 **P3** | Remove unnecessary `.sorted()` in `countAssignmentOccurrences(Collection)` | 15 min |
| 🟢 **P3** | Add audit fields (`createdAt`, `updatedAt`) to `AdminUser` entity | 30 min |
| 🟢 **P3** | Use `@Access(AccessType.FIELD)` on entities to prevent setter side effects during Hibernate hydration | 4 hours |

---

## 9. Estimated Impact of Fixes

| Fix | Latency Reduction | Throughput Gain | Security Improvement |
|-----|-------------------|-----------------|----------------------|
| Cache `SecretKey` + single JWT parse (PERF-1) | ~2–5 ms/req | +5–10% | None |
| Cache `UserDetails` / trust JWT claims (PERF-2) | ~10–50 ms/req (saves DB RTT) | +15–30% | None |
| Add missing DB indexes (DB-2, DB-3, DB-4) | 10–500 ms on indexed queries | +20–50% on reads | None |
| Fix EAGER → LAZY on `availableDays` (PERF-4) | ~5–20 ms/req on person endpoints | +10–20% | None |
| Fix bulk validation O(n²) → O(n) (PERF-3) | Critical for large batches | Enables large bulk ops | None |
| Secrets rotation + env vars (CRITICAL-1) | None | None | Eliminates credential exposure |
| JWT expiry reduction + revocation (CRITICAL-5) | None | None | Attack window: 7 days → 15 min |
| Actuator lock-down (CRITICAL-4) | None | None | Eliminates env variable leak |
| Rate limiting on login (SEC-2) | None | None | Prevents brute-force attacks |
| Flyway migration (DB-1) | None | None | Safe zero-downtime deployments |
| Add test suite (ARCH-6) | None | None | Enables confident refactoring of all above |

---

## Summary Scorecard

| Dimension | Rating | Primary Concern |
|-----------|--------|-----------------|
| **Security** | ❌ Failing | Credential exposure, no revocation, open Actuator |
| **Correctness** | ⚠️ At Risk | Denormalized counter drift, null invigilator slots |
| **Performance** | ⚠️ At Risk | Double JWT parse, N+1 EAGER fetch, O(n²) bulk validation |
| **Database** | ⚠️ At Risk | Missing indexes, `ddl-auto=update`, tiny connection pool |
| **Architecture** | 🟡 Fair | Good layering; missing cache, RBAC, and future-date filtering |
| **Code Quality** | ✅ Good | Clean code, good use of records, proper factory patterns |
| **Production Readiness** | ❌ Failing | No tests, no migrations, secrets committed to source |

