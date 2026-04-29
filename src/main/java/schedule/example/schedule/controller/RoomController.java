package schedule.example.schedule.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import schedule.example.schedule.config.PageRequestFactory;
import schedule.example.schedule.dto.common.PageResponse;
import schedule.example.schedule.dto.room.RoomRequest;
import schedule.example.schedule.dto.room.RoomResponse;
import schedule.example.schedule.entity.enums.RoomType;
import schedule.example.schedule.service.RoomService;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

	private static final Set<String> ALLOWED_SORTS = Set.of("id", "name", "capacity", "type", "minInvigilators");

	private final RoomService roomService;
	private final PageRequestFactory pageRequestFactory;

	public RoomController(RoomService roomService, PageRequestFactory pageRequestFactory) {
		this.roomService = roomService;
		this.pageRequestFactory = pageRequestFactory;
	}

	@GetMapping
	public PageResponse<RoomResponse> getRooms(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size,
		@RequestParam(defaultValue = "name") String sortBy,
		@RequestParam(defaultValue = "ASC") Sort.Direction direction,
		@RequestParam(required = false) RoomType type,
		@RequestParam(required = false) String name,
		@RequestParam(required = false) Integer minCapacity,
		@RequestParam(required = false) Integer maxCapacity
	) {
		Pageable pageable = pageRequestFactory.create(page, size, sortBy, direction, ALLOWED_SORTS);
		return roomService.getRooms(type, name, minCapacity, maxCapacity, pageable);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoomResponse createRoom(@Valid @RequestBody RoomRequest request) {
		return roomService.createRoom(request);
	}

	@PutMapping("/{id}")
	public RoomResponse updateRoom(@PathVariable UUID id, @Valid @RequestBody RoomRequest request) {
		return roomService.updateRoom(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRoom(@PathVariable UUID id) {
		roomService.deleteRoom(id);
	}
}