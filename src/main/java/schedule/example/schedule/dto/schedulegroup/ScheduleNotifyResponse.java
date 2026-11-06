package schedule.example.schedule.dto.schedulegroup;

import java.util.List;

/**
 * Result of "email the schedules": emails are queued and sent in the background.
 *
 * @param queued       emails put in the outbox (sent within moments, retried if the provider has trouble)
 * @param skipped      staff without an email address
 * @param unchanged    staff whose schedule was already emailed unchanged (not sent again)
 * @param batchId      to follow these emails in the delivery log
 */
public record ScheduleNotifyResponse(
	int queued,
	int skipped,
	int unchanged,
	List<String> skippedNames,
	java.util.UUID batchId
) {
}
