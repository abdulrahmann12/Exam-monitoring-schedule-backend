package com.uniguard.schedule.audit;

import com.uniguard.schedule.dto.assignment.RoomAssignmentResponse;
import com.uniguard.schedule.dto.bulk.BulkUploadResultDTO;
import com.uniguard.schedule.dto.bulk.ScheduleBulkUploadResultDTO;
import com.uniguard.schedule.dto.person.PersonResponse;
import com.uniguard.schedule.dto.room.RoomResponse;
import com.uniguard.schedule.dto.schedulegroup.ScheduleGroupResponse;
import com.uniguard.schedule.dto.schedulegroup.ScheduleNotifyResponse;
import com.uniguard.schedule.dto.assignment.DeleteAllAssignmentsResponse;
import com.uniguard.schedule.dto.timeslot.TimeSlotResponse;
import com.uniguard.schedule.dto.unavailability.UnavailabilityResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Readable labels for every request that changes data. Requests not listed here are still recorded
 * (category OTHER, "POST /api/..."), so a new endpoint can never slip through unlogged.
 * Sign-in and user management are recorded explicitly by their services instead.
 */
public final class AuditRoutes {

	/** How to describe one route. {@code idVariable} names the path variable holding the target id. */
	public record Route(String action, AuditCategory category, String targetType, String idVariable, Describer describer) {
	}

	@FunctionalInterface
	public interface Describer {
		/**
		 * @param body       the response body (may be null, e.g. for deletes)
		 * @param targetName the target's name read before the change (may be null)
		 */
		String describe(Object body, String targetName);
	}

	private static final Map<String, Route> ROUTES = Map.ofEntries(
		route("POST /api/people", "PERSON_CREATED", AuditCategory.PEOPLE, "PERSON", null, named("Added person")),
		route("PUT /api/people/{id}", "PERSON_UPDATED", AuditCategory.PEOPLE, "PERSON", "id", named("Edited person")),
		route("DELETE /api/people/{id}", "PERSON_DELETED", AuditCategory.PEOPLE, "PERSON", "id", named("Deleted person")),
		route("POST /api/people/{id}/unavailability", "LEAVE_ADDED", AuditCategory.PEOPLE, "PERSON", "id", (body, name) ->
			"Added leave for " + quote(name) + (body instanceof UnavailabilityResponse leave
				? " (" + leave.startDate() + " to " + leave.endDate() + ")" : "")),
		route("DELETE /api/people/{id}/unavailability/{unavailabilityId}", "LEAVE_REMOVED", AuditCategory.PEOPLE, "PERSON", "id",
			(body, name) -> "Removed leave of " + quote(name)),
		route("POST /api/bulk/persons/upload", "PEOPLE_IMPORTED", AuditCategory.PEOPLE, null, null, imported("people")),

		route("POST /api/rooms", "ROOM_CREATED", AuditCategory.ROOMS, "ROOM", null, named("Added room")),
		route("PUT /api/rooms/{id}", "ROOM_UPDATED", AuditCategory.ROOMS, "ROOM", "id", named("Edited room")),
		route("DELETE /api/rooms/{id}", "ROOM_DELETED", AuditCategory.ROOMS, "ROOM", "id", named("Deleted room")),
		route("POST /api/room-categories", "ROOM_TYPE_CREATED", AuditCategory.ROOMS, "ROOM_TYPE", null, named("Added room type")),
		route("PUT /api/room-categories/{id}", "ROOM_TYPE_UPDATED", AuditCategory.ROOMS, "ROOM_TYPE", "id", named("Edited room type")),
		route("DELETE /api/room-categories/{id}", "ROOM_TYPE_DELETED", AuditCategory.ROOMS, "ROOM_TYPE", "id", named("Deleted room type")),
		route("POST /api/bulk/rooms/upload", "ROOMS_IMPORTED", AuditCategory.ROOMS, null, null, imported("rooms")),

		route("POST /api/slots", "TIME_SLOT_CREATED", AuditCategory.TIME_SLOTS, "TIME_SLOT", null, named("Added time slot")),
		route("PUT /api/slots/{id}", "TIME_SLOT_UPDATED", AuditCategory.TIME_SLOTS, "TIME_SLOT", "id", named("Edited time slot")),
		route("DELETE /api/slots/{id}", "TIME_SLOT_DELETED", AuditCategory.TIME_SLOTS, "TIME_SLOT", "id", named("Deleted time slot")),
		route("PATCH /api/slots/{id}/deactivate", "TIME_SLOT_DEACTIVATED", AuditCategory.TIME_SLOTS, "TIME_SLOT", "id", named("Deactivated time slot")),
		route("PATCH /api/slots/{id}/activate", "TIME_SLOT_ACTIVATED", AuditCategory.TIME_SLOTS, "TIME_SLOT", "id", named("Activated time slot")),

		route("POST /api/schedule-groups", "EXAM_PERIOD_CREATED", AuditCategory.SCHEDULES, "SCHEDULE_GROUP", null, named("Created exam period")),
		route("PUT /api/schedule-groups/{id}", "EXAM_PERIOD_UPDATED", AuditCategory.SCHEDULES, "SCHEDULE_GROUP", "id", named("Edited exam period")),
		route("DELETE /api/schedule-groups/{id}", "EXAM_PERIOD_DELETED", AuditCategory.SCHEDULES, "SCHEDULE_GROUP", "id", named("Deleted exam period")),
		route("POST /api/schedule-groups/{id}/notify", "SCHEDULES_EMAILED", AuditCategory.SCHEDULES, "SCHEDULE_GROUP", "id", (body, name) ->
			"Emailed schedules for " + quote(name) + (body instanceof ScheduleNotifyResponse r
				? ": " + r.queued() + " queued, " + r.unchanged() + " unchanged, " + r.skipped() + " without email" : "")),
		route("POST /api/email-outbox/{id}/retry", "EMAIL_RETRIED", AuditCategory.SCHEDULES, "EMAIL", "id", (body, name) -> "Sent a failed email again"),
		route("POST /api/email-outbox/retry-failed", "EMAILS_RETRIED", AuditCategory.SCHEDULES, "EMAIL", null, (body, name) -> "Sent all failed emails again"),
		route("POST /api/room-allocation/save", "ROOMS_ALLOCATED", AuditCategory.SCHEDULES, null, null, (body, name) ->
			"Saved automatic room allocation" + (body instanceof com.uniguard.schedule.dto.allocation.RoomAllocationResults.Saved r
				? ": " + r.roomAssignmentsCreated() + " exam rooms" + (r.studentsUnplaced() > 0 ? ", " + r.studentsUnplaced() + " students without a seat" : "") : "")),
		route("POST /api/room-allocation/staff", "SCHEDULE_CREATED", AuditCategory.SCHEDULES, null, null, (body, name) ->
			"Created schedule (staff) for allocated rooms" + (body instanceof com.uniguard.schedule.dto.allocation.RoomAllocationResults.Staffed r
				? ": " + r.examRooms() + " exam rooms, chiefs " + r.chiefSlotsFilled() + "/" + r.chiefSlotsTotal()
					+ ", invigilators " + r.invigilatorSlotsFilled() + "/" + r.invigilatorSlotsTotal() : "")),
		route("POST /api/schedule-groups/{id}/bulk-upload", "SCHEDULE_IMPORTED", AuditCategory.SCHEDULES, "SCHEDULE_GROUP", "id", (body, name) ->
			"Imported schedule from Excel into " + quote(name) + (body instanceof ScheduleBulkUploadResultDTO r
				? ": " + r.roomAssignmentsCreated() + " exam rooms" : "")),

		route("POST /api/assignments", "EXAM_ROOM_CREATED", AuditCategory.SCHEDULES, "ASSIGNMENT", null, (body, name) ->
			"Added " + (body instanceof RoomAssignmentResponse a ? describe(a) : "exam room")),
		route("DELETE /api/assignments", "EXAM_PERIOD_CLEARED", AuditCategory.SCHEDULES, null, null, (body, name) ->
			body instanceof DeleteAllAssignmentsResponse r
				? "Deleted all " + r.deleted() + " exam room(s) of exam period " + quote(r.examPeriod())
				: "Deleted all exam rooms of an exam period"),
		route("PUT /api/assignments/{id}", "EXAM_ROOM_UPDATED", AuditCategory.SCHEDULES, "ASSIGNMENT", "id", (body, name) ->
			"Edited " + (body instanceof RoomAssignmentResponse a ? describe(a) : nonNull(name, "exam room"))),
		route("DELETE /api/assignments/{id}", "EXAM_ROOM_DELETED", AuditCategory.SCHEDULES, "ASSIGNMENT", "id", (body, name) ->
			"Deleted " + nonNull(name, "exam room")),
		route("POST /api/assignments/bulk", "SCHEDULE_SAVED", AuditCategory.SCHEDULES, null, null, (body, name) -> {
			if (body instanceof List<?> saved && !saved.isEmpty() && saved.get(0) instanceof RoomAssignmentResponse first) {
				return "Saved schedule: " + saved.size() + " exam room(s) on " + first.examDate()
					+ (first.slotLabel() != null ? " (" + first.slotLabel() + ")" : "");
			}
			return "Saved schedule";
		}),

		route("PUT /api/settings", "SETTINGS_UPDATED", AuditCategory.SETTINGS, null, null, (body, name) -> "Changed system settings")
	);

	/** Endpoints that change nothing, or are logged explicitly elsewhere. */
	private static final List<String> NOT_RECORDED_HERE = List.of(
		"/api/auth/",
		"/api/users",
		"/api/owner/",
		"/api/assignments/generate-preview",
		"/api/room-allocation/preview"
	);

	private AuditRoutes() {
	}

	public static Route find(String method, String pattern) {
		return ROUTES.get(method + " " + pattern);
	}

	public static boolean recordedHere(String path) {
		return NOT_RECORDED_HERE.stream().noneMatch(path::startsWith);
	}

	public static String describeAssignment(String subject, String room, LocalDate date, String slot) {
		StringBuilder text = new StringBuilder("exam ");
		text.append(subject == null || subject.isBlank() ? "(no subject)" : quote(subject));
		if (room != null) {
			text.append(" in ").append(room);
		}
		if (date != null) {
			text.append(" on ").append(date);
		}
		if (slot != null) {
			text.append(" (").append(slot).append(')');
		}
		return text.toString();
	}

	private static String describe(RoomAssignmentResponse a) {
		return describeAssignment(a.subjectName(), a.roomName(), a.examDate(), a.slotLabel());
	}

	private static Map.Entry<String, Route> route(String key, String action, AuditCategory category, String targetType, String idVariable, Describer describer) {
		return Map.entry(key, new Route(action, category, targetType, idVariable, describer));
	}

	/** "Deleted room 'Hall A'": name from the response if any, otherwise the name read before the change. */
	private static Describer named(String verb) {
		return (body, name) -> {
			String fromBody = nameOf(body);
			return verb + " " + quote(fromBody != null ? fromBody : name);
		};
	}

	private static Describer imported(String what) {
		return (body, name) -> body instanceof BulkUploadResultDTO r
			? "Imported " + what + " from Excel: " + r.successCount() + " saved, " + r.failedCount() + " row(s) rejected"
			: "Imported " + what + " from Excel";
	}

	private static String nameOf(Object body) {
		if (body instanceof PersonResponse p) {
			return p.name();
		}
		if (body instanceof RoomResponse r) {
			return r.name();
		}
		if (body instanceof com.uniguard.schedule.dto.room.RoomCategoryDtos.RoomCategoryResponse t) {
			return t.name();
		}
		if (body instanceof TimeSlotResponse t) {
			return t.label();
		}
		if (body instanceof ScheduleGroupResponse g) {
			return g.name();
		}
		return null;
	}

	private static String quote(String name) {
		return name == null || name.isBlank() ? "(unknown)" : "'" + name + "'";
	}

	private static String nonNull(String value, String fallback) {
		return value == null ? fallback : value;
	}
}
