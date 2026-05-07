package schedule.example.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import schedule.example.schedule.config.Messages;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.timeslot.TimeSlotRequest;
import schedule.example.schedule.dto.timeslot.TimeSlotResponse;
import schedule.example.schedule.entity.TimeSlot;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.exception.ValidationException;
import schedule.example.schedule.mapper.TimeSlotMapper;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.TimeSlotRepository;

import java.text.MessageFormat;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimeSlotService {

    private final TimeSlotRepository timeSlotRepository;
    private final RoomAssignmentRepository roomAssignmentRepository;
    private final TimeSlotMapper timeSlotMapper;

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
        timeSlotMapper.updateEntity(request, timeSlot);
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
    }

    @Transactional
    public void deleteTimeSlot(UUID id) {
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
        TimeSlot timeSlot = getTimeSlotEntity(id);
        timeSlot.setActive(false);
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
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