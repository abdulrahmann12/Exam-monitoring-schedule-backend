package com.uniguard.schedule.config;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.uniguard.schedule.exception.ValidationException;

import java.text.MessageFormat;
import java.util.Set;

@Component
public class PageRequestFactory {

	private static final int MAX_PAGE_SIZE = 1000;

	public Pageable create(int page, int size, String sortBy, Sort.Direction direction, Set<String> allowedSorts) {
		if (page < 0) {
			throw new ValidationException(Messages.PAGINATION_INVALID_PAGE);
		}

		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ValidationException(MessageFormat.format(Messages.PAGINATION_INVALID_SIZE, MAX_PAGE_SIZE));
		}

		if (!allowedSorts.contains(sortBy)) {
			throw new ValidationException(MessageFormat.format(Messages.PAGINATION_INVALID_SORT, sortBy, String.join(", ", allowedSorts)));
		}

		// Always end with the id: many rows share a date or a name, and without a tie-breaker the database may
		// order them differently for each page, so a row shows up on two pages and another on none.
		Sort sort = Sort.by(direction, sortBy);
		if (!"id".equals(sortBy)) {
			sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
		}
		return PageRequest.of(page, size, sort);
	}
}