package schedule.example.schedule.entity.enums;

/**
 * Tracks whether a room assignment was produced by the scheduling engine,
 * set manually by an admin, or is a mix of both.
 */
public enum AssignmentSource {
    GENERATED,
    MANUAL,
    MIXED
}
