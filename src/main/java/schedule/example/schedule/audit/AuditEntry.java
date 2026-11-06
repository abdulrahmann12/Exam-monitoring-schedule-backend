package schedule.example.schedule.audit;

/**
 * What to record. The actor (signed-in user) and IP address are filled in automatically;
 * {@code actorEmail} is only for events without a signed-in user (e.g. a failed sign-in).
 */
public record AuditEntry(
	String action,
	AuditCategory category,
	String targetType,
	String targetId,
	String summary,
	Outcome outcome,
	String actorEmail
) {

	public enum Outcome {
		SUCCESS,
		FAILURE,
		DENIED
	}

	public static AuditEntry success(String action, AuditCategory category, String targetType, Object targetId, String summary) {
		return new AuditEntry(action, category, targetType, targetId == null ? null : targetId.toString(), summary, Outcome.SUCCESS, null);
	}

	public AuditEntry withOutcome(Outcome newOutcome) {
		return new AuditEntry(action, category, targetType, targetId, summary, newOutcome, actorEmail);
	}

	/** For events where nobody is signed in: names the account concerned. */
	public AuditEntry forAccount(String email) {
		return new AuditEntry(action, category, targetType, targetId, summary, outcome, email);
	}
}
