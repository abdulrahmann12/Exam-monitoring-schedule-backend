package com.uniguard.schedule.service;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.dto.common.PageResponse;
import com.uniguard.schedule.dto.room.RoomRequest;
import com.uniguard.schedule.dto.room.RoomResponse;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.enums.RoomType;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.mapper.RoomMapper;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.util.NameNormalizationUtil;

import java.text.MessageFormat;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {

	private final RoomRepository roomRepository;
	private final RoomAssignmentRepository roomAssignmentRepository;
	private final RoomMapper roomMapper;
	private final NormalizedNameMaintenanceService normalizedNameMaintenanceService;
	private final DemoModeService demoModeService;

	public PageResponse<RoomResponse> getRooms(RoomType type, String name, Integer minCapacity, Integer maxCapacity, Pageable pageable) {
		// Convert name to a prefix pattern on normalizedName to allow B-tree index usage.
		// Trailing-only wildcard 'term%' on an indexed column avoids full table scans.
		String namePattern = (name != null && !name.isBlank())
			? com.uniguard.schedule.util.NameNormalizationUtil.normalizeForComparison(name) + "%"
			: null;
		Page<RoomResponse> page = roomRepository.search(type, namePattern, minCapacity, maxCapacity, pageable)
			.map(roomMapper::toResponse);
		return PageResponse.from(page);
	}

	@Transactional
	public RoomResponse createRoom(@Valid RoomRequest request) {
		normalizedNameMaintenanceService.synchronizeRooms();
		validateUniqueName(request.name(), null);
		Room room = roomMapper.toEntity(request);
		return roomMapper.toResponse(roomRepository.save(room));
	}

	@Transactional
	public RoomResponse updateRoom(UUID id, @Valid RoomRequest request) {
		normalizedNameMaintenanceService.synchronizeRooms();
		Room room = getRoomEntity(id);
		validateUniqueName(request.name(), id);
		roomMapper.updateEntity(request, room);
		return roomMapper.toResponse(roomRepository.save(room));
	}

	@Transactional
	public void deleteRoom(UUID id) {
		demoModeService.rejectIfDemoUser("delete-room");
		Room room = getRoomEntity(id);
		if (roomAssignmentRepository.existsByRoomId(id)) {
			throw new ConflictException(MessageFormat.format(Messages.ROOM_DELETE_IN_USE, room.getName()));
		}

		roomRepository.delete(room);
	}

	private Room getRoomEntity(UUID id) {
		return roomRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(MessageFormat.format(Messages.ROOM_NOT_FOUND, id)));
	}

	private void validateUniqueName(String roomName, UUID existingId) {
		String normalizedName = NameNormalizationUtil.normalizeForComparison(roomName);
		roomRepository.findByNormalizedName(normalizedName)
			.filter(existing -> existingId == null || !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(MessageFormat.format(Messages.ROOM_NAME_EXISTS, roomName));
			});
	}
}