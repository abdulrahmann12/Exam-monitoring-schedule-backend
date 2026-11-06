package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupRequest;
import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupResponse;
import com.uniguard.schedule.entity.ScheduleGroup;

@Mapper(config = CentralMapperConfig.class)
public interface ScheduleGroupMapper {

	@Mapping(target = "id", ignore = true)
	@Mapping(target = "active", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	ScheduleGroup toEntity(ScheduleGroupRequest request);

	@Mapping(target = "id", ignore = true)
	@Mapping(target = "active", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	void updateEntity(ScheduleGroupRequest request, @MappingTarget ScheduleGroup group);

	default ScheduleGroupResponse toResponse(ScheduleGroup group, long timeSlotCount, long assignmentCount) {
		return new ScheduleGroupResponse(
			group.getId(),
			group.getName(),
			group.getDescription(),
			group.isActive(),
			timeSlotCount,
			assignmentCount,
			group.getCreatedAt(),
			group.getUpdatedAt()
		);
	}
}
