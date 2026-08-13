package schedule.example.schedule.dto.schedulegroup;

import java.util.List;

public record ScheduleNotifyResponse(
	int sent,
	int skipped,
	int failed,
	List<String> skippedNames,
	List<String> failedNames
) {
}
