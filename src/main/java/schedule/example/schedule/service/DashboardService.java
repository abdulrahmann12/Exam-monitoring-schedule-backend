package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import schedule.example.schedule.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import schedule.example.schedule.config.ApplicationDefaults;
import schedule.example.schedule.config.CacheConfig;
import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse;
import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse.TopAssignedPerson;
import schedule.example.schedule.entity.Person;
import schedule.example.schedule.repository.PersonRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final PersonRepository personRepository;
    private final PeopleService peopleService;
    private final ScheduleGroupSupport scheduleGroupSupport;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns aggregated dashboard statistics scoped to one schedule group.
     *
     * <p>Staff and room totals stay global. Assignment volume and "top assigned"
     * use only rooms in the requested (or default) schedule group.
     */
    @Cacheable(value = CacheConfig.CACHE_DASHBOARD,
            key = "T(schedule.example.schedule.tenant.TenantContext).require() + ':' + #scheduleGroupId")
    public DashboardSummaryResponse getSummary(UUID scheduleGroupId) {
        // requireGroup is tenant-filtered: another university's group id answers 404.
        UUID groupId = scheduleGroupSupport.requireGroup(scheduleGroupId).getId();
        UUID universityId = TenantContext.require();
        // Plain SQL bypasses Hibernate's university filter, so every count filters by university here.
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
            SELECT
                (SELECT COUNT(*) FROM people WHERE university_id = ? AND role = 'CHIEF_INVIGILATOR' AND active = TRUE) AS chiefs,
                (SELECT COUNT(*) FROM people WHERE university_id = ? AND role = 'INVIGILATOR'       AND active = TRUE) AS invigilators,
                (SELECT COUNT(*) FROM rooms  WHERE university_id = ? AND active = TRUE)                               AS rooms,
                (SELECT COUNT(*) FROM room_assignments WHERE university_id = ? AND schedule_group_id = ?)             AS assignments
            """, universityId, universityId, universityId, universityId, groupId);

        long chiefs           = ((Number) counts.get("chiefs")).longValue();
        long invigilators     = ((Number) counts.get("invigilators")).longValue();
        long rooms            = ((Number) counts.get("rooms")).longValue();
        long totalAssignments = ((Number) counts.get("assignments")).longValue();

        Map<UUID, Integer> workload = peopleService.countAssignmentsByScheduleGroup(groupId);
        List<UUID> topIds = workload.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> String.valueOf(entry.getKey())))
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();
        Map<UUID, Person> peopleById = personRepository.findAllById(topIds).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<TopAssignedPerson> top = topIds.stream()
                .map(peopleById::get)
                .filter(Objects::nonNull)
                .filter(Person::isActive)
                .map(person -> new TopAssignedPerson(
                        person.getId(),
                        person.getName(),
                        person.getRole().name(),
                        workload.getOrDefault(person.getId(), 0)))
                .toList();

        return new DashboardSummaryResponse(chiefs, invigilators, rooms, totalAssignments, top);
    }
}
