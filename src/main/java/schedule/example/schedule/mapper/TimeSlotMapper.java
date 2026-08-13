package schedule.example.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import schedule.example.schedule.dto.timeslot.TimeSlotRequest;
import schedule.example.schedule.dto.timeslot.TimeSlotResponse;
import schedule.example.schedule.entity.TimeSlot;

@Mapper(config = CentralMapperConfig.class)
public interface TimeSlotMapper {

	@Mapping(target = "scheduleGroupId", source = "scheduleGroup.id")
	TimeSlotResponse toResponse(TimeSlot timeSlot);

	@Mapping(target = "scheduleGroup", ignore = true)
	TimeSlot toEntity(TimeSlotRequest request);

	@Mapping(target = "scheduleGroup", ignore = true)
	void updateEntity(TimeSlotRequest request, @MappingTarget TimeSlot timeSlot);
}