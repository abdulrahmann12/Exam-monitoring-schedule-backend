package com.uniguard.schedule.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.uniguard.schedule.config.Messages;
import com.uniguard.schedule.entity.InvigilatorAssignment;
import com.uniguard.schedule.entity.Person;
import com.uniguard.schedule.entity.PersonUnavailability;
import com.uniguard.schedule.entity.RoomAssignment;
import com.uniguard.schedule.entity.TimeSlot;
import com.uniguard.schedule.entity.enums.HallGender;
import com.uniguard.schedule.entity.enums.WeekDay;
import com.uniguard.schedule.repository.PersonRepository;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The single source of truth for scheduling conflict rules, used by manual assignment, bulk save,
 * Excel schedule upload, time-slot edits and the auto-assigner.
 *
 * <p>Rules (all evaluated against a {@link SchedulingContext} that spans every schedule group):
 * <ol>
 *   <li><b>Room</b> — a room cannot host two assignments at overlapping times on the same date.</li>
 *   <li><b>Availability</b> — staff must work on the exam's weekday and not be on leave that date.</li>
 *   <li><b>Parallel rooms</b> — a chief may cover at most {@link Person#getMaxParallelRooms()}
 *       rooms at overlapping times; an invigilator exactly one.</li>
 *   <li><b>Daily limit</b> — optional cap on separate duty sessions per person per day.</li>
 *   <li><b>Hall gender</b> — gender-separated halls only accept staff of that gender.</li>
 *   <li><b>Own subject</b> — nobody invigilates an exam whose subject code they teach.</li>
 *   <li><b>Rest</b> — optional minimum break between two sessions of one person on a day.</li>
 *   <li><b>Period limit</b> — optional cap on sessions per person in one exam period.</li>
 * </ol>
 *
 * <p>"Overlapping" always compares real start/end times, never time-slot ids, so slots from
 * different schedule groups (or overlapping slots in the same group) are handled correctly.
 */
@Component
@RequiredArgsConstructor
public class StaffConcurrencyRules {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final PersonRepository personRepository;

    // ── Primitive checks ────────────────────────────────────────────────────

    /**
     * Two slots overlap when each starts before the other ends:
     * {@code A.start < B.end && A.end > B.start}.
     */
    public static boolean overlaps(TimeSlot left, TimeSlot right) {
        if (left == null || right == null || left.getStartTime() == null || left.getEndTime() == null
                || right.getStartTime() == null || right.getEndTime() == null) {
            return false;
        }
        return left.getStartTime().isBefore(right.getEndTime())
                && left.getEndTime().isAfter(right.getStartTime());
    }

    /** Weekly pattern only: {@code true} when the person works on the exam date's weekday. */
    public static boolean isAvailable(Person person, LocalDate examDate) {
        return person.getAvailableDays().contains(WeekDay.from(examDate.getDayOfWeek()));
    }

    /** Weekly pattern <em>and</em> date-specific leave. */
    public static boolean isAvailable(Person person, LocalDate examDate, SchedulingContext context) {
        return isAvailable(person, examDate) && context.leaveOn(person.getId(), examDate).isEmpty();
    }

    private static boolean sameSitting(RoomAssignment current, RoomAssignment other) {
        return other != current
                && current.getExamDate().equals(other.getExamDate())
                && overlaps(current.getTimeSlot(), other.getTimeSlot());
    }

    private static boolean isSameRow(RoomAssignment current, RoomAssignment other) {
        return other == current
                || (current.getId() != null && current.getId().equals(other.getId()));
    }

    /** Counts other rooms, on the same date at an overlapping time, where {@code chiefId} is chief. */
    public static int countOverlappingChiefRooms(List<RoomAssignment> universe, RoomAssignment current, UUID chiefId) {
        int count = 0;
        for (RoomAssignment other : universe) {
            if (isSameRow(current, other) || !sameSitting(current, other)) {
                continue;
            }
            if (other.getChiefInvigilator() != null && chiefId.equals(other.getChiefInvigilator().getId())) {
                count++;
            }
        }
        return count;
    }

    /** Counts other rooms, on the same date at an overlapping time, where {@code invigilatorId} invigilates. */
    public static int countOverlappingInvigilatorRooms(List<RoomAssignment> universe, RoomAssignment current, UUID invigilatorId) {
        int count = 0;
        for (RoomAssignment other : universe) {
            if (isSameRow(current, other) || !sameSitting(current, other) || other.getInvigilatorAssignments() == null) {
                continue;
            }
            if (holdsInvigilatorSeat(other, invigilatorId)) {
                count++;
            }
        }
        return count;
    }

    /** Another assignment that books the same room on the same date at an overlapping time, if any. */
    public static Optional<RoomAssignment> findRoomOverlap(List<RoomAssignment> universe, RoomAssignment current) {
        if (current.getRoom() == null) {
            return Optional.empty();
        }
        for (RoomAssignment other : universe) {
            if (isSameRow(current, other) || !sameSitting(current, other) || other.getRoom() == null) {
                continue;
            }
            if (sameEntity(current.getRoom().getId(), other.getRoom().getId(), current.getRoom(), other.getRoom())) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    /**
     * Number of separate duty sessions {@code personId} already has on the current assignment's date,
     * ignoring sessions that overlap the current one (those are the same sitting).
     */
    public static int countOtherSessionsOnDate(List<RoomAssignment> universe, RoomAssignment current, UUID personId) {
        Set<String> sessions = new HashSet<>();
        for (RoomAssignment other : universe) {
            if (isSameRow(current, other) || !current.getExamDate().equals(other.getExamDate())) {
                continue;
            }
            if (overlaps(current.getTimeSlot(), other.getTimeSlot())) {
                continue;
            }
            boolean involved = (other.getChiefInvigilator() != null && personId.equals(other.getChiefInvigilator().getId()))
                    || holdsInvigilatorSeat(other, personId);
            if (involved && other.getTimeSlot() != null) {
                sessions.add(other.getTimeSlot().getStartTime() + "-" + other.getTimeSlot().getEndTime());
            }
        }
        return sessions.size();
    }

    private static boolean holdsInvigilatorSeat(RoomAssignment assignment, UUID personId) {
        if (assignment.getInvigilatorAssignments() == null) {
            return false;
        }
        for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
            if (ia.getInvigilator() != null && personId.equals(ia.getInvigilator().getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameEntity(UUID leftId, UUID rightId, Object left, Object right) {
        return left == right || (leftId != null && leftId.equals(rightId));
    }

    // ── Composite checks ────────────────────────────────────────────────────

    /**
     * Can {@code person} take a (chief or invigilator) seat in {@code current} without breaking any rule?
     * Used by the auto-assigner to filter candidates.
     */
    public static boolean canTakeSeat(Person person, boolean asChief, RoomAssignment current, SchedulingContext context) {
        return personViolation(person, asChief, current, context).isEmpty();
    }

    /**
     * All rule violations for {@code current}, as user-facing messages. Empty list = valid.
     * {@code current} must already be part of the context universe or be excluded from it by identity.
     */
    public static List<String> findViolations(RoomAssignment current, SchedulingContext context) {
        Set<String> violations = new LinkedHashSet<>();

        findRoomOverlap(context.universe(), current).ifPresent(other -> violations.add(MessageFormat.format(
                Messages.ASSIGNMENT_ROOM_OVERLAP,
                current.getRoom().getName(),
                current.getExamDate(),
                describeSlot(other.getTimeSlot()))));

        if (current.getChiefInvigilator() != null) {
            personViolation(current.getChiefInvigilator(), true, current, context).ifPresent(violations::add);
        }
        if (current.getInvigilatorAssignments() != null) {
            for (InvigilatorAssignment ia : current.getInvigilatorAssignments()) {
                if (ia.getInvigilator() != null) {
                    personViolation(ia.getInvigilator(), false, current, context).ifPresent(violations::add);
                }
            }
        }
        return new ArrayList<>(violations);
    }

    private static Optional<String> personViolation(Person person, boolean asChief, RoomAssignment current, SchedulingContext context) {
        LocalDate date = current.getExamDate();

        if (!isAvailable(person, date)) {
            String pattern = asChief ? Messages.ASSIGNMENT_CHIEF_UNAVAILABLE : Messages.ASSIGNMENT_INVIGILATOR_UNAVAILABLE;
            return Optional.of(MessageFormat.format(pattern, person.getName(), WeekDay.from(date.getDayOfWeek()).getValue(), date));
        }

        Optional<PersonUnavailability> leave = context.leaveOn(person.getId(), date);
        if (leave.isPresent()) {
            String pattern = asChief ? Messages.ASSIGNMENT_CHIEF_ON_LEAVE : Messages.ASSIGNMENT_INVIGILATOR_ON_LEAVE;
            String reason = leave.get().getReason() == null || leave.get().getReason().isBlank()
                    ? "" : " (" + leave.get().getReason() + ")";
            return Optional.of(MessageFormat.format(pattern, person.getName(), date, reason));
        }

        HallGender hall = current.getRoom() == null ? null : current.getRoom().getHallGender();
        if (hall != null && hall != HallGender.MIXED && !hall.name().equals(person.getGender() == null ? null : person.getGender().name())) {
            return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_GENDER_MISMATCH,
                    person.getName(), current.getRoom().getName(), hall.name().toLowerCase(java.util.Locale.ROOT)));
        }

        if (person.teaches(current.getSubjectCode())) {
            return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_OWN_SUBJECT, person.getName(), current.getSubjectCode()));
        }

        if (asChief) {
            int parallel = countOverlappingChiefRooms(context.universe(), current, person.getId());
            if (parallel >= person.getMaxParallelRooms()) {
                return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_CHIEF_LIMIT, person.getName()));
            }
        } else if (countOverlappingInvigilatorRooms(context.universe(), current, person.getId()) >= 1) {
            return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_INVIGILATOR_DOUBLE_BOOKED, person.getName()));
        }

        int limit = context.maxDutiesPerDay();
        if (limit > 0 && countOtherSessionsOnDate(context.universe(), current, person.getId()) + 1 > limit) {
            return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_DAILY_LIMIT, person.getName(), limit, date));
        }

        int minRest = context.settings().minRestMinutes();
        if (minRest > 0) {
            Optional<Long> shortest = shortestGapMinutes(context.universe(), current, person.getId());
            if (shortest.isPresent() && shortest.get() < minRest) {
                return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_REST_TOO_SHORT, person.getName(), minRest, date));
            }
        }

        int periodLimit = context.settings().maxDutiesPerPeriod();
        if (periodLimit > 0 && current.getScheduleGroup() != null
                && countOtherSessionsInPeriod(context, current, person.getId()) + 1 > periodLimit) {
            return Optional.of(MessageFormat.format(Messages.ASSIGNMENT_PERIOD_LIMIT, person.getName(), periodLimit));
        }
        return Optional.empty();
    }

    /**
     * Shortest break (minutes) between {@code current} and any other, non-overlapping session of the
     * person on the same date. Empty when the person has no other session that day.
     */
    static Optional<Long> shortestGapMinutes(List<RoomAssignment> universe, RoomAssignment current, UUID personId) {
        TimeSlot slot = current.getTimeSlot();
        if (slot == null || slot.getStartTime() == null || slot.getEndTime() == null) {
            return Optional.empty();
        }
        Long shortest = null;
        for (RoomAssignment other : universe) {
            if (isSameRow(current, other) || !current.getExamDate().equals(other.getExamDate())
                    || other.getTimeSlot() == null || overlaps(slot, other.getTimeSlot())) {
                continue;
            }
            boolean involved = (other.getChiefInvigilator() != null && personId.equals(other.getChiefInvigilator().getId()))
                    || holdsInvigilatorSeat(other, personId);
            if (!involved) {
                continue;
            }
            TimeSlot o = other.getTimeSlot();
            long gap = !o.getEndTime().isAfter(slot.getStartTime())
                    ? java.time.Duration.between(o.getEndTime(), slot.getStartTime()).toMinutes()
                    : java.time.Duration.between(slot.getEndTime(), o.getStartTime()).toMinutes();
            shortest = shortest == null ? gap : Math.min(shortest, gap);
        }
        return Optional.ofNullable(shortest);
    }

    /**
     * Separate sessions the person already has in the current assignment's exam period (schedule
     * group), across all dates — persisted ones plus unsaved ones in the universe — excluding the
     * current sitting itself.
     */
    static int countOtherSessionsInPeriod(SchedulingContext context, RoomAssignment current, UUID personId) {
        UUID groupId = current.getScheduleGroup().getId();
        String currentKey = sittingKey(current.getExamDate(), current.getTimeSlot());
        Set<String> sessions = new HashSet<>();
        for (var window : context.persistedGroupWindows(groupId)) {
            if (personId.equals(window.getPersonId())) {
                sessions.add(window.getExamDate() + "@" + window.getStartTime() + "-" + window.getEndTime());
            }
        }
        for (RoomAssignment other : context.universe()) {
            if (isSameRow(current, other) || other.getScheduleGroup() == null || !groupId.equals(other.getScheduleGroup().getId())) {
                continue;
            }
            boolean involved = (other.getChiefInvigilator() != null && personId.equals(other.getChiefInvigilator().getId()))
                    || holdsInvigilatorSeat(other, personId);
            if (involved) {
                sessions.add(sittingKey(other.getExamDate(), other.getTimeSlot()));
            }
        }
        sessions.remove(currentKey);
        return sessions.size();
    }

    private static String sittingKey(LocalDate date, TimeSlot slot) {
        return date + "@" + (slot == null ? "?" : slot.getStartTime() + "-" + slot.getEndTime());
    }

    public static String describeSlot(TimeSlot slot) {
        if (slot == null || slot.getStartTime() == null || slot.getEndTime() == null) {
            return "?";
        }
        return slot.getStartTime().format(TIME) + "–" + slot.getEndTime().format(TIME);
    }

    // ── Workload counters ───────────────────────────────────────────────────

    public static Map<UUID, Integer> countAssignmentOccurrences(RoomAssignment assignment) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (assignment.getChiefInvigilator() != null) {
            counts.merge(assignment.getChiefInvigilator().getId(), 1, Integer::sum);
        }
        for (InvigilatorAssignment ia : assignment.getInvigilatorAssignments()) {
            if (ia.getInvigilator() != null) {
                counts.merge(ia.getInvigilator().getId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    public static Map<UUID, Integer> countAssignmentOccurrences(Collection<RoomAssignment> assignments) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (RoomAssignment assignment : assignments) {
            countAssignmentOccurrences(assignment)
                    .forEach((personId, value) -> counts.merge(personId, value, Integer::sum));
        }
        return counts;
    }

    /**
     * Updates the denormalised {@code totalAssignments} counter for all affected persons in one batch.
     * The counter is display-only; fairness uses {@link WorkloadTracker}. It is also recomputed from
     * real data at startup ({@code WorkloadReconciler}) so any historical drift self-heals.
     */
    public void applyWorkloadDelta(Map<UUID, Integer> before, Map<UUID, Integer> after) {
        Map<UUID, Integer> netDeltas = new HashMap<>();
        Set<UUID> allIds = new HashSet<>(before.keySet());
        allIds.addAll(after.keySet());

        for (UUID personId : allIds) {
            int delta = after.getOrDefault(personId, 0) - before.getOrDefault(personId, 0);
            if (delta != 0) {
                netDeltas.put(personId, delta);
            }
        }

        if (netDeltas.isEmpty()) {
            return;
        }

        List<Person> people = personRepository.findAllById(netDeltas.keySet());
        for (Person person : people) {
            Integer delta = netDeltas.get(person.getId());
            if (delta != null) {
                person.setTotalAssignments(Math.max(0, person.getTotalAssignments() + delta));
            }
        }
        personRepository.saveAll(people);
    }
}
