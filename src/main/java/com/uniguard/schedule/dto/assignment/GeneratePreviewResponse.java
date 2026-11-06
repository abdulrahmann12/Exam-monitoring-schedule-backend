package com.uniguard.schedule.dto.assignment;

import java.util.List;

/** Proposed slot schedule (not persisted) plus every issue the rules engine found. */
public record GeneratePreviewResponse(
	List<PreviewAssignment> assignments,
	List<String> conflicts
) {
}
