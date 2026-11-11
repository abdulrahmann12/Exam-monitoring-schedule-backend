package com.uniguard.schedule.service;

import java.text.MessageFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.uniguard.schedule.dto.room.RoomCategoryDtos.RoomCategoryRequest;
import com.uniguard.schedule.dto.room.RoomCategoryDtos.RoomCategoryResponse;
import com.uniguard.schedule.entity.Room;
import com.uniguard.schedule.entity.RoomCategory;
import com.uniguard.schedule.exception.ConflictException;
import com.uniguard.schedule.exception.NotFoundException;
import com.uniguard.schedule.repository.RoomCategoryRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.util.NameNormalizationUtil;
import com.uniguard.schedule.util.RoomNames;

/**
 * Room types of the current university (V18). Each university edits its own list; the defaults below are
 * created once. A type still used by rooms cannot be deleted.
 */
@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomCategoryService {

	private static final Logger LOGGER = LoggerFactory.getLogger(RoomCategoryService.class);

	public static final String CLASSROOM = "Classroom";
	public static final String LAB = "Lab";

	/** name → special. Created once per university; the admin can rename, add or delete afterwards. */
	private static final List<Map.Entry<String, Boolean>> DEFAULTS = List.of(
		Map.entry(CLASSROOM, false),
		Map.entry("Lecture Hall", false),
		Map.entry(LAB, true),
		Map.entry("Computer Lab", true),
		Map.entry("Chemistry Lab", true),
		Map.entry("Physics Lab", true),
		Map.entry("Drawing Studio", true));

	private final RoomCategoryRepository categories;
	private final RoomRepository rooms;

	public List<RoomCategoryResponse> list() {
		Map<UUID, Long> counts = rooms.findAll().stream()
			.filter(room -> room.getCategory() != null)
			.collect(Collectors.groupingBy(room -> room.getCategory().getId(), Collectors.counting()));
		return categories.findAllByOrderBySortOrderAscNameAsc().stream()
			.map(category -> toResponse(category, counts.getOrDefault(category.getId(), 0L)))
			.toList();
	}

	@Transactional
	public RoomCategoryResponse create(@Valid RoomCategoryRequest request) {
		requireUniqueName(request.name(), null);
		int nextOrder = categories.findAllByOrderBySortOrderAscNameAsc().stream().mapToInt(RoomCategory::getSortOrder).max().orElse(0) + 1;
		return toResponse(categories.save(new RoomCategory(request.name(), request.special(), nextOrder)), 0);
	}

	@Transactional
	public RoomCategoryResponse update(UUID id, @Valid RoomCategoryRequest request) {
		RoomCategory category = require(id);
		requireUniqueName(request.name(), id);
		category.setName(request.name());
		category.setSpecial(request.special());
		long used = rooms.countByCategoryId(id);
		return toResponse(categories.save(category), used);
	}

	@Transactional
	public void delete(UUID id) {
		RoomCategory category = require(id);
		long used = rooms.countByCategoryId(id);
		if (used > 0) {
			throw new ConflictException(MessageFormat.format(
				"\"{0}\" is the type of {1} room(s). Give those rooms another type first.", category.getName(), used));
		}
		categories.delete(category);
	}

	/** All types of the current university by normalized name (for Excel files). */
	public Map<String, RoomCategory> byNormalizedName() {
		return categories.findAllByOrderBySortOrderAscNameAsc().stream()
			.collect(Collectors.toMap(RoomCategory::getNormalizedName, Function.identity(), (a, b) -> a));
	}

	public List<String> names() {
		return categories.findAllByOrderBySortOrderAscNameAsc().stream().map(RoomCategory::getName).toList();
	}

	public Optional<RoomCategory> find(UUID id) {
		return id == null ? Optional.empty() : categories.findById(id);
	}

	public RoomCategory require(UUID id) {
		return categories.findById(id)
			.orElseThrow(() -> new NotFoundException(MessageFormat.format("Room type {0} was not found.", id)));
	}

	/** The type a new room gets when none is chosen: "Lab" for a name like "Lab 2", else "Classroom" (if they exist). */
	public RoomCategory guessFor(String roomName) {
		Map<String, RoomCategory> all = byNormalizedName();
		String wanted = RoomNames.looksLikeLab(roomName) ? LAB : CLASSROOM;
		return all.get(NameNormalizationUtil.normalizeForComparison(wanted));
	}

	/**
	 * Run inside the university: creates the default types once, then gives every room without a type
	 * its guessed type. Safe to run on every start.
	 */
	@Transactional
	public void ensureDefaults() {
		if (!categories.existsBy()) {
			int order = 1;
			for (Map.Entry<String, Boolean> entry : DEFAULTS) {
				categories.save(new RoomCategory(entry.getKey(), entry.getValue(), order++));
			}
			LOGGER.warn("[ROOM-TYPES] Default room types created.");
		}
		List<Room> untyped = rooms.findAll().stream().filter(room -> room.getCategory() == null).toList();
		int typed = 0;
		for (Room room : untyped) {
			RoomCategory guess = guessFor(room.getName());
			if (guess != null) {
				room.setCategory(guess);
				typed++;
			}
		}
		if (typed > 0) {
			rooms.saveAll(untyped);
			LOGGER.warn("[ROOM-TYPES] {} room(s) without a type got one from their name (Lab / Classroom).", typed);
		}
	}

	private void requireUniqueName(String name, UUID existingId) {
		categories.findByNormalizedName(NameNormalizationUtil.normalizeForComparison(name))
			.filter(existing -> !existing.getId().equals(existingId))
			.ifPresent(existing -> {
				throw new ConflictException(MessageFormat.format("A room type named \"{0}\" already exists.", existing.getName()));
			});
	}

	private static RoomCategoryResponse toResponse(RoomCategory category, long rooms) {
		return new RoomCategoryResponse(category.getId(), category.getName(), category.isSpecial(), category.getSortOrder(), rooms);
	}

	/** Types in display order. */
	public static final Comparator<RoomCategory> ORDER = Comparator.comparingInt(RoomCategory::getSortOrder).thenComparing(RoomCategory::getName);
}
