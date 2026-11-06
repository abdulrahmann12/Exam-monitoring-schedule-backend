package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.uniguard.schedule.dto.assignment.InvigilatorAssignmentResponse;
import com.uniguard.schedule.entity.InvigilatorAssignment;

@Mapper(config = CentralMapperConfig.class)
public interface InvigilatorAssignmentMapper {

        @Mapping(target = "invigilatorId", source = "invigilator.id")
        @Mapping(target = "invigilatorName", source = "invigilator.name")
        @Mapping(target = "positionIndex", source = "positionIndex")
        @Mapping(target = "required", source = "required")
        @Mapping(target = "createdAt", source = "createdAt")
        InvigilatorAssignmentResponse toResponse(InvigilatorAssignment assignment);
}