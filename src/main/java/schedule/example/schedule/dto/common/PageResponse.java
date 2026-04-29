package schedule.example.schedule.dto.common;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
	List<T> content,
	long totalElements,
	int totalPages,
	int currentPage,
	int size,
	boolean first,
	boolean last
) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(
			page.getContent(),
			page.getTotalElements(),
			page.getTotalPages(),
			page.getNumber(),
			page.getSize(),
			page.isFirst(),
			page.isLast()
		);
	}
}