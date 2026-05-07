package schedule.example.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import schedule.example.schedule.config.CacheConfig;
import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse;
import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse.TopAssignedPerson;
import schedule.example.schedule.repository.PersonRepository;

import java.util.List;
import java.util.Map;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final PersonRepository personRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns aggregated dashboard statistics.
     *
     * <p><strong>Performance fix — single aggregate query:</strong> The previous implementation
     * issued 4 sequential COUNT queries (chiefs, invigilators, active rooms, total assignments).
     * Each query required a separate network round-trip to the remote DB.
     *
     * <p>This version combines all four counts into one correlated subquery statement executed
     * in a single round-trip. The result is cached for 60 seconds (see {@link CacheConfig}), so
     * this query only runs at most once per minute regardless of dashboard request volume.
     *
     * <p>Result: 4 sequential DB round-trips → 1 round-trip (on cache miss).
     */
    @Cacheable(value = CacheConfig.CACHE_DASHBOARD, key = "'summary'")
    public DashboardSummaryResponse getSummary() {
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
            SELECT
                (SELECT COUNT(*) FROM people WHERE role = 'CHIEF_INVIGILATOR' AND active = TRUE) AS chiefs,
                (SELECT COUNT(*) FROM people WHERE role = 'INVIGILATOR'       AND active = TRUE) AS invigilators,
                (SELECT COUNT(*) FROM rooms   WHERE active = TRUE)                               AS rooms,
                (SELECT COUNT(*) FROM room_assignments)                                          AS assignments
            """);

        long chiefs           = ((Number) counts.get("chiefs")).longValue();
        long invigilators     = ((Number) counts.get("invigilators")).longValue();
        long rooms            = ((Number) counts.get("rooms")).longValue();
        long totalAssignments = ((Number) counts.get("assignments")).longValue();

        List<TopAssignedPerson> top = personRepository
                .findTop10ByActiveTrueOrderByTotalAssignmentsDesc(
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "totalAssignments")))
                .stream()
                .map(p -> new TopAssignedPerson(
                        p.getId(),
                        p.getName(),
                        p.getRole().name(),
                        p.getTotalAssignments()))
                .toList();

        return new DashboardSummaryResponse(chiefs, invigilators, rooms, totalAssignments, top);
    }
}
