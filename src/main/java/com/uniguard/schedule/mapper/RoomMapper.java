package com.uniguard.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import com.uniguard.schedule.dto.room.RoomRequest;
import com.uniguard.schedule.dto.room.RoomResponse;
import com.uniguard.schedule.entity.Room;

@Mapper(config = CentralMapperConfig.class)
public interface RoomMapper {

	RoomResponse toResponse(Room room);

	Room toEntity(RoomRequest request);

	void updateEntity(RoomRequest request, @MappingTarget Room room);
}