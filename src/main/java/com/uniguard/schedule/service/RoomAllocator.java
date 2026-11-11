package com.uniguard.schedule.service;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Picks rooms for the exams of one sitting (one date and time). Pure logic: no database, no Spring.
 *
 * <p>Rules:
 * <ul>
 *   <li>A room hosts at most one exam (never two subjects in the same room at the same time).</li>
 *   <li>An exam may be split over several rooms.</li>
 *   <li>For each exam the set of free rooms is chosen that seats everyone with the <b>fewest rooms</b>,
 *       and among those the <b>fewest empty seats</b> (exact search, see {@link #bestSubset}).</li>
 *   <li>Exams are handled biggest first (they have the fewest good choices). An exam that cannot be
 *       seated completely is put aside until every other exam is seated, then gets whatever rooms are
 *       left; the students still without a seat are reported as {@code unplaced}.</li>
 * </ul>
 *
 * <p>The result is deterministic: equal sizes are ordered by key, equal rooms by name.
 */
public final class RoomAllocator {

	/** Hard limit on students per exam, so a typo like 100000 cannot make the search slow. */
	public static final int MAX_STUDENTS_PER_EXAM = 20_000;

	private RoomAllocator() {
	}

	/** One exam to seat. {@code key} identifies it to the caller (e.g. the file row). */
	public record Exam(String key, int students) {
		public Exam {
			Objects.requireNonNull(key, "key");
			if (students <= 0 || students > MAX_STUDENTS_PER_EXAM) {
				throw new IllegalArgumentException("students must be between 1 and " + MAX_STUDENTS_PER_EXAM);
			}
		}
	}

	/** One free room. {@code id} is opaque to the allocator. */
	public record RoomOption<I>(I id, String name, int capacity) {
		public RoomOption {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(name, "name");
		}
	}

	/** Students placed in one room. */
	public record Seating<I>(RoomOption<I> room, int students) {
	}

	/** Rooms chosen for one exam; {@code unplaced} &gt; 0 when the free rooms were not enough. */
	public record Placement<I>(Exam exam, List<Seating<I>> rooms, int unplaced) {
		public int seats() {
			return rooms.stream().mapToInt(seating -> seating.room().capacity()).sum();
		}
	}

	/**
	 * @param exams the exams of one sitting (keys must be unique)
	 * @param rooms the rooms free for the whole sitting (rooms with capacity 0 or less are ignored)
	 * @return one placement per exam, in the same order as {@code exams}
	 */
	public static <I> List<Placement<I>> allocate(List<Exam> exams, List<RoomOption<I>> rooms) {
		List<RoomOption<I>> free = new ArrayList<>(rooms.stream().filter(room -> room.capacity() > 0).toList());
		free.sort(Comparator.comparingInt((RoomOption<I> room) -> room.capacity()).reversed().thenComparing(RoomOption::name));

		List<Exam> order = new ArrayList<>(exams);
		order.sort(Comparator.comparingInt(Exam::students).reversed().thenComparing(Exam::key));

		List<Placement<I>> placements = new ArrayList<>(exams.size());
		exams.forEach(exam -> placements.add(null));

		List<Exam> tooBig = new ArrayList<>();
		for (Exam exam : order) {
			int freeSeats = free.stream().mapToInt(RoomOption::capacity).sum();
			if (exam.students() > freeSeats) {
				tooBig.add(exam); // seat the others first, then give this one what is left
				continue;
			}
			List<RoomOption<I>> chosen = bestSubset(free, exam.students());
			free.removeAll(chosen);
			placements.set(exams.indexOf(exam), place(exam, chosen));
		}
		for (Exam exam : tooBig) {
			List<RoomOption<I>> chosen = new ArrayList<>(free);
			free.clear();
			placements.set(exams.indexOf(exam), place(exam, chosen));
		}
		return placements;
	}

	/**
	 * The subset of {@code rooms} whose capacity covers {@code students} with the fewest rooms, then the
	 * smallest total capacity. Exact 0/1 knapsack over the total seat count; rooms earlier in the list
	 * win ties. The caller guarantees that all rooms together are enough.
	 */
	static <I> List<RoomOption<I>> bestSubset(List<RoomOption<I>> rooms, int students) {
		int maxCapacity = rooms.stream().mapToInt(RoomOption::capacity).max().orElse(0);
		int total = rooms.stream().mapToInt(RoomOption::capacity).sum();
		// A best subset never exceeds students + biggest room - 1 (else a room could be dropped).
		int limit = Math.min(total, students + maxCapacity - 1);

		int[] count = new int[limit + 1]; // fewest rooms reaching exactly this many seats
		java.util.Arrays.fill(count, Integer.MAX_VALUE);
		count[0] = 0;
		List<BitSet> took = new ArrayList<>(rooms.size()); // took.get(i).get(s): room i used to reach s
		for (RoomOption<I> room : rooms) {
			BitSet used = new BitSet(limit + 1);
			int capacity = room.capacity();
			for (int seats = limit; seats >= capacity; seats--) {
				int before = count[seats - capacity];
				if (before != Integer.MAX_VALUE && before + 1 < count[seats]) {
					count[seats] = before + 1;
					used.set(seats);
				}
			}
			took.add(used);
		}

		int best = -1;
		for (int seats = students; seats <= limit; seats++) {
			if (count[seats] != Integer.MAX_VALUE && (best < 0 || count[seats] < count[best])) {
				best = seats; // ascending scan: equal room counts keep the smaller seat total
			}
		}
		if (best < 0) {
			throw new IllegalStateException("rooms cannot seat " + students + " students");
		}

		List<RoomOption<I>> chosen = new ArrayList<>();
		int seats = best;
		for (int i = rooms.size() - 1; i >= 0 && seats > 0; i--) {
			if (took.get(i).get(seats)) {
				chosen.add(rooms.get(i));
				seats -= rooms.get(i).capacity();
			}
		}
		chosen.sort(Comparator.comparingInt((RoomOption<I> room) -> room.capacity()).reversed().thenComparing(RoomOption::name));
		return chosen;
	}

	/** Fills the rooms in order (biggest first); the last room gets the remainder. */
	private static <I> Placement<I> place(Exam exam, List<RoomOption<I>> chosen) {
		List<Seating<I>> seatings = new ArrayList<>(chosen.size());
		int left = exam.students();
		for (RoomOption<I> room : chosen) {
			int here = Math.min(left, room.capacity());
			if (here <= 0) {
				break;
			}
			seatings.add(new Seating<>(room, here));
			left -= here;
		}
		return new Placement<>(exam, List.copyOf(seatings), left);
	}
}
