package schedule.example.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import schedule.example.schedule.dto.person.PersonRequest;
import schedule.example.schedule.dto.person.PersonResponse;
import schedule.example.schedule.entity.Person;

@Mapper(config = CentralMapperConfig.class)
public interface PersonMapper {

	PersonResponse toResponse(Person person);

	Person toEntity(PersonRequest request);

	void updateEntity(PersonRequest request, @MappingTarget Person person);
}