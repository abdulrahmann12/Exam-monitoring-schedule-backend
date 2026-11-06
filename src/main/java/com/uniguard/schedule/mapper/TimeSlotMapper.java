package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.timeslot.TimeSlotRequest;
import com.uniguard.schedule.dto.timeslot.TimeSlotResponse;
import com.uniguard.schedule.entity.TimeSlot;

@Mapper(config = CentralMapperConfig.class)
public interface TimeSlotMapper {

	@Mapping(target = "scheduleGroupId", source = "scheduleGroup.id")
	TimeSlotResponse toResponse(TimeSlot timeSlot);

	@Mapping(target = "scheduleGroup", ignore = true)
	TimeSlot toEntity(TimeSlotRequest request);

	@Mapping(target = "scheduleGroup", ignore = true)
	void updateEntity(TimeSlotRequest request, @MappingTarget TimeSlot timeSlot);
}