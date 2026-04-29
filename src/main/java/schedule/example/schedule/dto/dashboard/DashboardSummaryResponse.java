package schedule.example.schedule.dto.dashboard;

import java.util.List;

public record DashboardSummaryResponse(
        long totalChiefInvigilators,
        long totalInvigilators,
        long totalRooms,
        long totalAssignments,
        List<TopAssignedPerson> topAssignedStaff
) {
    public record TopAssignedPerson(
            java.util.UUID id,
            String name,
            String role,
            int totalAssignments
    ) {}
}
