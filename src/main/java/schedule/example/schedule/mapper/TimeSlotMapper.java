package schedule.example.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import schedule.example.schedule.dto.timeslot.TimeSlotRequest;
import schedule.example.schedule.dto.timeslot.TimeSlotResponse;
import schedule.example.schedule.entity.TimeSlot;

@Mapper(config = CentralMapperConfig.class)
public interface TimeSlotMapper {

	TimeSlotResponse toResponse(TimeSlot timeSlot);

	TimeSlot toEntity(TimeSlotRequest request);

	void updateEntity(TimeSlotRequest request, @MappingTarget TimeSlot timeSlot);
}