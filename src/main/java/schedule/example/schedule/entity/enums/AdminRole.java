package schedule.example.schedule.entity.enums;

/**
 * Roles for admin users.
 *
 * <ul>
 *   <li>{@link #ADMIN} — full production admin with unrestricted capabilities.</li>
 *   <li>{@link #DEMO_ADMIN} — restricted role for the demo account; may not perform
 *       destructive or configuration-altering operations.</li>
 * </ul>
 */
public enum AdminRole {
    ADMIN,
    DEMO_ADMIN
}

