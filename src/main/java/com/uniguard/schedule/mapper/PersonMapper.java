package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.person.PersonRequest;
import com.uniguard.schedule.dto.person.PersonResponse;
import com.uniguard.schedule.entity.Person;

@Mapper(config = CentralMapperConfig.class)
public interface PersonMapper {

	PersonResponse toResponse(Person person);

	Person toEntity(PersonRequest request);

	void updateEntity(PersonRequest request, @MappingTarget Person person);
}