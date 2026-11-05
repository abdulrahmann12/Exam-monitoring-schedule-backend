package schedule.example.schedule.entity.enums;

/**
 * Roles of user accounts.
 *
 * <ul>
 *   <li>{@link #ADMIN} — everything, including managing users and system settings.</li>
 *   <li>{@link #SCHEDULER} — builds and edits schedules, people, rooms and time slots;
 *       cannot manage users or change settings.</li>
 *   <li>{@link #VIEWER} — read-only: can view and export schedules.</li>
 *   <li>{@link #DEMO_ADMIN} — the shared public demo account (read-only on the server).
 *       System-managed: it can never be assigned through the user management API.</li>
 * </ul>
 */
public enum AdminRole {
    ADMIN,
    SCHEDULER,
    VIEWER,
    DEMO_ADMIN;

    /** Roles an administrator may give to a user. */
    public boolean isAssignable() {
        return this != DEMO_ADMIN;
    }
}
