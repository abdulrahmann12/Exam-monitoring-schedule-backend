package schedule.example.schedule.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Placeholder service for schedule generation and validation.
 *
 * <p><strong>TODO:</strong> Implement auto-generation and pre-validation logic.
 * Methods deliberately throw {@link UnsupportedOperationException} rather than silently
 * returning — silent no-ops would allow callers to proceed as if validation/generation
 * succeeded, causing subtle correctness bugs.
 */
@Service
public class ScheduleService {

	public void validateDateSlot(UUID timeSlotId) {
		throw new UnsupportedOperationException(
			"Schedule slot validation is not yet implemented. TimeSlotId: " + timeSlotId);
	}

	public void generateDateSlot(UUID timeSlotId) {
		throw new UnsupportedOperationException(
			"Schedule generation is not yet implemented. TimeSlotId: " + timeSlotId);
	}
}