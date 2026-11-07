package com.uniguard.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.dto.timeslot.TimeSlotRequest;
import com.uniguard.schedule.dto.timeslot.TimeSlotResponse;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.exception.ValidationException;
import com.uniguard.schedule.mapper.TimeSlotMapper;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;

import java.text.MessageFormat;
import java.util.UUID;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.uniguard.schedule.entity.RoomAssignment;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimeSlotService {

    private final TimeSlotRepository timeSlotRepository;
    private final RoomAssignmentRepository roomAssignmentRepository;
    private final TimeSlotMapper timeSlotMapper;
    private final DemoModeService demoModeService;
    private final ScheduleWriteLock scheduleWriteLock;
    private final SchedulingContextLoader contextLoader;

    /** Time slots are shared by every exam period of the university, like people and rooms. */
    public PageResponse<TimeSlotResponse> getTimeSlots(String label, Boolean activeOnly, Pageable pageable) {
        Page<TimeSlotResponse> page = timeSlotRepository.search(label, activeOnly, pageable)
                .map(timeSlotMapper::toResponse);
        return PageResponse.from(page);
    }

    @Transactional
    public TimeSlotResponse createTimeSlot(@Valid TimeSlotRequest request) {
        validateTimeRange(request);
        TimeSlot timeSlot = timeSlotMapper.toEntity(request);
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
    }

    @Transactional
    public TimeSlotResponse updateTimeSlot(UUID id, @Valid TimeSlotRequest request) {
        validateTimeRange(request);
        TimeSlot timeSlot = getTimeSlotEntity(id);
        boolean timesChanged = !request.startTime().equals(timeSlot.getStartTime())
                || !request.endTime().equals(timeSlot.getEndTime());
        if (timesChanged) {
            scheduleWriteLock.acquire();
        }
        timeSlotMapper.updateEntity(request, timeSlot);
        if (timesChanged) {
            revalidateAssignmentsUsing(timeSlot);
        }
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
    }

    @Transactional
    public void deleteTimeSlot(UUID id) {
        demoModeService.rejectIfDemoUser("delete-time-slot");
        TimeSlot timeSlot = getTimeSlotEntity(id);
        if (roomAssignmentRepository.existsByTimeSlotId(id)) {
            throw new ConflictException(MessageFormat.format(Messages.SLOT_DELETE_IN_USE,
                    timeSlot.getLabel() != null ? timeSlot.getLabel() : id.toString(),
                    timeSlot.getStartTime()));
        }
        timeSlotRepository.delete(timeSlot);
    }

    /** Soft-deactivate a slot instead of deleting it when it has assignments. */
    @Transactional
    public TimeSlotResponse deactivateTimeSlot(UUID id) {
        demoModeService.rejectIfDemoUser("deactivate-time-slot");
        TimeSlot timeSlot = getTimeSlotEntity(id);
        timeSlot.setActive(false);
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
    }

    /**
     * Moving a slot's start/end can make its assignments overlap others (same room, same staff).
     * Re-runs the full rule set on every assignment that uses the slot, with the new times applied.
     */
    private void revalidateAssignmentsUsing(TimeSlot timeSlot) {
        List<RoomAssignment> affected = roomAssignmentRepository.findAllDetailedByTimeSlotId(timeSlot.getId());
        if (affected.isEmpty()) {
            return;
        }
        Set<LocalDate> dates = affected.stream().map(RoomAssignment::getExamDate).collect(Collectors.toSet());
        SchedulingContext context = contextLoader.load(dates);
        context.addAll(affected);
        for (RoomAssignment assignment : affected) {
            List<String> violations = StaffConcurrencyRules.findViolations(assignment, context);
            if (!violations.isEmpty()) {
                throw new ConflictException(MessageFormat.format(Messages.SLOT_TIME_CHANGE_CONFLICT,
                        assignment.getRoom().getName() + " on " + assignment.getExamDate() + " — " + violations.getFirst()));
            }
        }
    }

    private TimeSlot getTimeSlotEntity(UUID id) {
        return timeSlotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.SLOT_NOT_FOUND, id)));
    }

    private void validateTimeRange(TimeSlotRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ValidationException(Messages.SLOT_INVALID_RANGE);
        }
    }
}