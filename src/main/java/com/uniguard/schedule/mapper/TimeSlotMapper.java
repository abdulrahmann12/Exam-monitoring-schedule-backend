package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.timeslot.TimeSlotRequest;
import com.uniguard.schedule.dto.timeslot.TimeSlotResponse;
import com.uniguard.schedule.entity.TimeSlot;

@Mapper(config = CentralMapperConfig.class)
public interface TimeSlotMapper {

	TimeSlotResponse toResponse(TimeSlot timeSlot);

	TimeSlot toEntity(TimeSlotRequest request);

	void updateEntity(TimeSlotRequest request, @MappingTarget TimeSlot timeSlot);
}