package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.room.RoomRequest;
import com.uniguard.schedule.dto.room.RoomResponse;
import com.uniguard.schedule.entity.Room;

@Mapper(config = CentralMapperConfig.class)
public interface RoomMapper {

	@Mapping(target = "roomCategoryId", expression = "java(room.getCategory() == null ? null : room.getCategory().getId())")
	@Mapping(target = "roomCategoryName", expression = "java(room.getCategory() == null ? null : room.getCategory().getName())")
	@Mapping(target = "roomCategorySpecial", expression = "java(room.isSpecial())")
	RoomResponse toResponse(Room room);

	/** The room type and {@code active} are optional in the request: RoomService decides them. */
	@Mapping(target = "category", ignore = true)
	@Mapping(target = "active", ignore = true)
	Room toEntity(RoomRequest request);

	@Mapping(target = "category", ignore = true)
	@Mapping(target = "active", ignore = true)
	void updateEntity(RoomRequest request, @MappingTarget Room room);
}