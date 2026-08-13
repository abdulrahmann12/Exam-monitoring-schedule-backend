package schedule.example.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import schedule.example.schedule.dto.assignment.RoomAssignmentRequest;
import schedule.example.schedule.dto.assignment.RoomAssignmentResponse;
import schedule.example.schedule.entity.RoomAssignment;

@Mapper(config = CentralMapperConfig.class, uses = InvigilatorAssignmentMapper.class)
public interface RoomAssignmentMapper {

	@Mapping(target = "scheduleGroupId", source = "scheduleGroup.id")
	@Mapping(target = "roomId", source = "room.id")
	@Mapping(target = "roomName", source = "room.name")
	@Mapping(target = "timeSlotId", source = "timeSlot.id")
	@Mapping(target = "slotLabel", source = "timeSlot.label")
	@Mapping(target = "startTime", source = "timeSlot.startTime")
	@Mapping(target = "endTime", source = "timeSlot.endTime")
	@Mapping(target = "chiefInvigilatorId", source = "chiefInvigilator.id")
	@Mapping(target = "chiefInvigilatorName", source = "chiefInvigilator.name")
	@Mapping(target = "invigilators", source = "invigilatorAssignments")
	RoomAssignmentResponse toResponse(RoomAssignment assignment);

	@Mapping(target = "id", ignore = true)
	@Mapping(target = "scheduleGroup", ignore = true)
	@Mapping(target = "room", ignore = true)
	@Mapping(target = "timeSlot", ignore = true)
	@Mapping(target = "chiefInvigilator", ignore = true)
	@Mapping(target = "invigilatorAssignments", ignore = true)
	@Mapping(target = "generationVersion", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	RoomAssignment toEntity(RoomAssignmentRequest request);

	@Mapping(target = "scheduleGroup", ignore = true)
	@Mapping(target = "room", ignore = true)
	@Mapping(target = "timeSlot", ignore = true)
	@Mapping(target = "chiefInvigilator", ignore = true)
	@Mapping(target = "invigilatorAssignments", ignore = true)
	@Mapping(target = "generationVersion", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	void updateEntity(RoomAssignmentRequest request, @MappingTarget RoomAssignment assignment);
}