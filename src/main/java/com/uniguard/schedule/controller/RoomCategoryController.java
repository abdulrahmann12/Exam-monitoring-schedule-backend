package com.uniguard.schedule.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.uniguard.schedule.dto.room.RoomCategoryDtos.RoomCategoryRequest;
import com.uniguard.schedule.dto.room.RoomCategoryDtos.RoomCategoryResponse;
import com.uniguard.schedule.service.RoomCategoryService;

/** Room types of the current university. Everyone reads; administrators and schedulers change. */
@RestController
@RequestMapping("/api/room-categories")
public class RoomCategoryController {

	private final RoomCategoryService service;

	public RoomCategoryController(RoomCategoryService service) {
		this.service = service;
	}

	@GetMapping
	public List<RoomCategoryResponse> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoomCategoryResponse create(@Valid @RequestBody RoomCategoryRequest request) {
		return service.create(request);
	}

	@PutMapping("/{id}")
	public RoomCategoryResponse update(@PathVariable UUID id, @Valid @RequestBody RoomCategoryRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}
}
