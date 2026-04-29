package schedule.example.schedule.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import schedule.example.schedule.config.MessageResolver;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.room.RoomRequest;
import schedule.example.schedule.dto.room.RoomResponse;
import schedule.example.schedule.entity.Room;
import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.exception.ConflictException;
import schedule.example.schedule.exception.NotFoundException;
import schedule.example.schedule.mapper.RoomMapper;
import schedule.example.schedule.repository.RoomAssignmentRepository;
import schedule.example.schedule.repository.RoomRepository;
import schedule.example.schedule.util.NameNormalizationUtil;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class RoomService {

	private final RoomRepository roomRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final RoomMapper roomMapper;
	private final MessageResolver messageResolver;
	private final NormalizedNameMaintenanceService normalizedNameMaintenanceService;

	public RoomService(
		RoomRepository roomRepository,
		RoomAssignmentRepository roomAssignmentRepository,
		RoomMapper roomMapper,
		MessageResolver messageResolver,
		NormalizedNameMaintenanceService normalizedNameMaintenanceService
	) {
		this.roomRepository = roomRepository;
		this.roomAssignmentRepository = roomAssignmentRepository;
		this.roomMapper = roomMapper;
		this.messageResolver = messageResolver;
		this.normalizedNameMaintenanceService = normalizedNameMaintenanceService;
	}

	public PageResponse<RoomResponse> getRooms(RoomType type, String name, Integer minCapacity, Integer maxCapacity, Pageable pageable) {
		Page<RoomResponse> page = roomRepository.search(type, name, minCapacity, maxCapacity, pageable)
			.map(roomMapper::toResponse);
		return PageResponse.from(page);
	}

	@Transactional
	public RoomResponse createRoom(RoomRequest request) {
		normalizedNameMaintenanceService.synchronizeRooms();
		validateUniqueName(request.name(), null);
		Room room = roomMapper.toEntity(request);
		return roomMapper.toResponse(roomRepository.save(room));
	}

	@Transactional
	public RoomResponse updateRoom(UUID id, RoomRequest request) {
		normalizedNameMaintenanceService.synchronizeRooms();
		Room room = getRoomEntity(id);
		validateUniqueName(request.name(), id);
		roomMapper.updateEntity(request, room);
		return roomMapper.toResponse(roomRepository.save(room));
	}

	@Transactional
	public void deleteRoom(UUID id) {
		Room room = getRoomEntity(id);
		if (roomAssignmentRepository.existsByRoomId(id)) {
			throw new ConflictException(messageResolver.get("room.delete.in-use", room.getName()));
		}

		roomRepository.delete(room);
	}

	private Room getRoomEntity(UUID id) {
		return roomRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(messageResolver.get("room.not-found", id)));
	}

	private void validateUniqueName(String roomName, UUID existingId) {
		String normalizedName = NameNormalizationUtil.normalizeForComparison(roomName);
		roomRepository.findByNormalizedName(normalizedName)
			.filter(existing -> existingId == null || !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(messageResolver.get("room.name.exists", roomName));
			});
	}
}