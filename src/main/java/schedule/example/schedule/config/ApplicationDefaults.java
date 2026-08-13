package schedule.example.schedule.config;

import java.util.UUID;

public final class ApplicationDefaults {

	public static final UUID DEFAULT_SETTINGS_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** Seed group used to attach existing time slots and assignments. */
	public static final UUID DEFAULT_SCHEDULE_GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	public static final String DEFAULT_EXAM_PERIOD = "Current Semester";

	private ApplicationDefaults() {
	}
}