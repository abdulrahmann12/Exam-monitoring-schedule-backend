package schedule.example.schedule.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.MessageResolver;
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

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TimeSlotService {

    private final TimeSlotRepository timeSlotRepository;
    private final RoomAssignmentRepository roomAssignmentRepository;
    private final TimeSlotMapper timeSlotMapper;
    private final MessageResolver messageResolver;

    public TimeSlotService(
            TimeSlotRepository timeSlotRepository,
            RoomAssignmentRepository roomAssignmentRepository,
            TimeSlotMapper timeSlotMapper,
            MessageResolver messageResolver
    ) {
        this.timeSlotRepository = timeSlotRepository;
        this.roomAssignmentRepository = roomAssignmentRepository;
        this.timeSlotMapper = timeSlotMapper;
        this.messageResolver = messageResolver;
    }

    public PageResponse<TimeSlotResponse> getTimeSlots(String label, Boolean activeOnly, Pageable pageable) {
        Page<TimeSlotResponse> page = timeSlotRepository.search(label, activeOnly, pageable)
                .map(timeSlotMapper::toResponse);
        return PageResponse.from(page);
    }

    @Transactional
    public TimeSlotResponse createTimeSlot(TimeSlotRequest request) {
        validateTimeRange(request);
        TimeSlot timeSlot = timeSlotMapper.toEntity(request);
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
    }

    @Transactional
    public TimeSlotResponse updateTimeSlot(UUID id, TimeSlotRequest request) {
        validateTimeRange(request);
        TimeSlot timeSlot = getTimeSlotEntity(id);
        timeSlotMapper.updateEntity(request, timeSlot);
        return timeSlotMapper.toResponse(timeSlotRepository.save(timeSlot));
    }

    @Transactional
    public void deleteTimeSlot(UUID id) {
        TimeSlot timeSlot = getTimeSlotEntity(id);
        if (roomAssignmentRepository.existsByTimeSlotId(id)) {
            throw new ConflictException(messageResolver.get("slot.delete.in-use",
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
                .orElseThrow(() -> new NotFoundException(messageResolver.get("slot.not-found", id)));
    }

    private void validateTimeRange(TimeSlotRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ValidationException(messageResolver.get("slot.invalid-range"));
        }
    }
}