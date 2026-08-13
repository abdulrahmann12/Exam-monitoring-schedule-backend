package schedule.example.schedule.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.dto.dashboard.DashboardSummaryResponse;
import schedule.example.schedule.service.DashboardService;

import java.util.UUID;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** GET /api/dashboard/summary — metrics used by the Dashboard page */
    @GetMapping("/summary")
    public DashboardSummaryResponse getSummary(@RequestParam(required = false) UUID scheduleGroupId) {
        return dashboardService.getSummary(scheduleGroupId);
    }
}
