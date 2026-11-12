package com.uniguard.schedule.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageRequestFactoryTest {

	private final PageRequestFactory factory = new PageRequestFactory();

	@Test
	void everyPageEndsWithTheIdSoRowsNeverRepeatOrGoMissing() {
		Pageable byDate = factory.create(1, 100, "examDate", Sort.Direction.ASC, Set.of("examDate", "id"));
		assertThat(byDate.getSort().toList()).containsExactly(Sort.Order.asc("examDate"), Sort.Order.asc("id"));

		Pageable byId = factory.create(0, 100, "id", Sort.Direction.DESC, Set.of("examDate", "id"));
		assertThat(byId.getSort().toList()).containsExactly(Sort.Order.desc("id"));
	}
}
