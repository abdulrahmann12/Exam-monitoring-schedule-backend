package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.config.PageRequestFactory;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.timeslot.TimeSlotRequest;
import schedule.example.schedule.dto.timeslot.TimeSlotResponse;
import schedule.example.schedule.service.TimeSlotService;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/slots")
public class TimeSlotController {

    private static final Set<String> ALLOWED_SORTS = Set.of("id", "label", "startTime", "endTime", "sortOrder");

    private final TimeSlotService timeSlotService;
    private final PageRequestFactory pageRequestFactory;

    public TimeSlotController(TimeSlotService timeSlotService, PageRequestFactory pageRequestFactory) {
        this.timeSlotService = timeSlotService;
        this.pageRequestFactory = pageRequestFactory;
    }

    @GetMapping
    public PageResponse<TimeSlotResponse> getTimeSlots(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "sortOrder") String sortBy,
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @RequestParam(required = false) UUID scheduleGroupId,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) Boolean activeOnly
    ) {
        Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
        return timeSlotService.getTimeSlots(scheduleGroupId, label, activeOnly, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TimeSlotResponse createTimeSlot(@Valid @RequestBody TimeSlotRequest request) {
        return timeSlotService.createTimeSlot(request);
    }

    @PutMapping("/{id}")
    public TimeSlotResponse updateTimeSlot(@PathVariable UUID id, @Valid @RequestBody TimeSlotRequest request) {
        return timeSlotService.updateTimeSlot(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeSlot(@PathVariable UUID id) {
        timeSlotService.deleteTimeSlot(id);
    }

    /** Soft-deactivate — use this instead of DELETE when the slot has existing assignments. */
    @PatchMapping("/{id}/deactivate")
    public TimeSlotResponse deactivateTimeSlot(@PathVariable UUID id) {
        return timeSlotService.deactivateTimeSlot(id);
    }
}