package schedule.example.schedule.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import schedule.example.schedule.dto.room.RoomRequest;
import schedule.example.schedule.dto.room.RoomResponse;
import schedule.example.schedule.entity.Room;

@Mapper(config = CentralMapperConfig.class)
public interface RoomMapper {

	RoomResponse toResponse(Room room);

	Room toEntity(RoomRequest request);

	void updateEntity(RoomRequest request, @MappingTarget Room room);
}