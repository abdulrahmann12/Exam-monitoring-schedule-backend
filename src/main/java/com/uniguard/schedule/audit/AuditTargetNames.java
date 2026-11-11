package com.uniguard.schedule.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uniguard.schedule.repository.PersonRepository;
import com.uniguard.schedule.repository.RoomAssignmentRepository;
import com.uniguard.schedule.repository.RoomRepository;
import com.uniguard.schedule.repository.ScheduleGroupRepository;
import com.uniguard.schedule.repository.TimeSlotRepository;

import java.util.UUID;

/**
 * Looks up a readable name for the thing a request is about to change — read <em>before</em> the
 * change, so deleted items still show up as "Deleted room 'Hall A'" instead of a bare id.
 */
@Component
public class AuditTargetNames {

	private final PersonRepository people;
	private final RoomRepository rooms;
	private final TimeSlotRepository slots;
	private final ScheduleGroupRepository groups;
	private final RoomAssignmentRepository assignments;
	private final com.uniguard.schedule.repository.RoomCategoryRepository roomTypes;
	private final TransactionTemplate readOnly;

	public AuditTargetNames(
		PersonRepository people,
		RoomRepository rooms,
		TimeSlotRepository slots,
		ScheduleGroupRepository groups,
		RoomAssignmentRepository assignments,
		com.uniguard.schedule.repository.RoomCategoryRepository roomTypes,
		PlatformTransactionManager transactionManager
	) {
		this.roomTypes = roomTypes;
		this.people = people;
		this.rooms = rooms;
		this.slots = slots;
		this.groups = groups;
		this.assignments = assignments;
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
	}

	/** @return the name, or null when unknown (never throws: the log must not break a request). */
	public String find(String targetType, String id) {
		UUID uuid;
		try {
			uuid = UUID.fromString(id);
		} catch (IllegalArgumentException | NullPointerException ex) {
			return null;
		}
		try {
			return readOnly.execute(status -> switch (targetType) {
				case "PERSON" -> people.findById(uuid).map(p -> p.getName()).orElse(null);
				case "ROOM" -> rooms.findById(uuid).map(r -> r.getName()).orElse(null);
				case "ROOM_TYPE" -> roomTypes.findById(uuid).map(t -> t.getName()).orElse(null);
				case "TIME_SLOT" -> slots.findById(uuid).map(s -> s.getLabel()).orElse(null);
				case "SCHEDULE_GROUP" -> groups.findById(uuid).map(g -> g.getName()).orElse(null);
				case "ASSIGNMENT" -> assignments.findById(uuid).map(a -> AuditRoutes.describeAssignment(
					a.getSubjectName(), a.getRoom() == null ? null : a.getRoom().getName(), a.getExamDate(),
					a.getTimeSlot() == null ? null : a.getTimeSlot().getLabel())).orElse(null);
				default -> null;
			});
		} catch (RuntimeException ex) {
			return null;
		}
	}
}
