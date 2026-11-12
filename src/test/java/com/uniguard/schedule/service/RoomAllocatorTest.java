package com.uniguard.schedule.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomAllocatorTest {

	private static List<RoomAllocator.RoomOption<String>> rooms(int... capacities) {
		List<RoomAllocator.RoomOption<String>> rooms = new ArrayList<>();
		for (int i = 0; i < capacities.length; i++) {
			rooms.add(new RoomAllocator.RoomOption<>("R" + i, "Room " + (char) ('A' + i), capacities[i]));
		}
		return rooms;
	}

	private static List<Integer> capacities(RoomAllocator.Placement<String> placement) {
		return placement.rooms().stream().map(seating -> seating.room().capacity()).toList();
	}

	@Test
	void smallExamGetsTheSmallestRoomThatFits() {
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 30)), rooms(100, 60, 40, 25));
		assertThat(capacities(result.get(0))).containsExactly(40);
		assertThat(result.get(0).unplaced()).isZero();
	}

	@Test
	void bigExamIsSplitWithoutEmptySeatsWhenPossible() {
		// 150 students: 100+60 wastes 10, 100+50 wastes nothing.
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 150)), rooms(100, 60, 50, 40));
		assertThat(capacities(result.get(0))).containsExactly(100, 50);
		assertThat(result.get(0).rooms()).extracting(RoomAllocator.Seating::students).containsExactly(100, 50);
	}

	@Test
	void fewerRoomsBeatFewerEmptySeats() {
		// 90 students: one room of 100 (10 empty) beats 50+40 (0 empty) — one room, one team of invigilators.
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 90)), rooms(100, 50, 40));
		assertThat(capacities(result.get(0))).containsExactly(100);
	}

	@Test
	void findsTheExactCombinationGreedyWouldMiss() {
		// 120 students. Biggest-first greedy takes 70 then 70 (20 empty); the best is 70+50 (0 empty).
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 120)), rooms(70, 70, 50));
		assertThat(capacities(result.get(0))).containsExactly(70, 50);
	}

	@Test
	void aRoomIsNeverGivenToTwoExams() {
		var exams = List.of(new RoomAllocator.Exam("a", 60), new RoomAllocator.Exam("b", 60), new RoomAllocator.Exam("c", 60));
		var result = RoomAllocator.allocate(exams, rooms(60, 60, 60, 60));
		Set<String> used = new HashSet<>();
		for (var placement : result) {
			assertThat(placement.unplaced()).isZero();
			for (var seating : placement.rooms()) {
				assertThat(used.add(seating.room().id())).as("room used twice: " + seating.room().id()).isTrue();
			}
		}
	}

	@Test
	void resultsComeBackInTheOrderOfTheExams() {
		var exams = List.of(new RoomAllocator.Exam("small", 10), new RoomAllocator.Exam("big", 90));
		var result = RoomAllocator.allocate(exams, rooms(100, 20));
		assertThat(result.get(0).exam().key()).isEqualTo("small");
		assertThat(capacities(result.get(0))).containsExactly(20);
		assertThat(capacities(result.get(1))).containsExactly(100);
	}

	@Test
	void anExamThatCannotFitWaitsSoTheOthersAreSeatedFirst() {
		// 100 students but only 80 seats in all: seat the 30 first, then the 100 get what is left.
		var exams = List.of(new RoomAllocator.Exam("big", 100), new RoomAllocator.Exam("small", 30));
		var result = RoomAllocator.allocate(exams, rooms(50, 30));
		assertThat(capacities(result.get(1))).containsExactly(30);
		assertThat(result.get(1).unplaced()).isZero();
		assertThat(capacities(result.get(0))).containsExactly(50);
		assertThat(result.get(0).unplaced()).isEqualTo(50);
	}

	@Test
	void noFreeRoomsMeansEveryoneIsUnplaced() {
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 25)), List.<RoomAllocator.RoomOption<String>>of());
		assertThat(result.get(0).rooms()).isEmpty();
		assertThat(result.get(0).unplaced()).isEqualTo(25);
	}

	@Test
	void roomsWithoutSeatsAreIgnored() {
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 10)), rooms(0, 10));
		assertThat(capacities(result.get(0))).containsExactly(10);
	}

	@Test
	void sameInputGivesSameRooms() {
		var exams = List.of(new RoomAllocator.Exam("a", 45), new RoomAllocator.Exam("b", 45));
		var first = RoomAllocator.allocate(exams, rooms(50, 50, 50));
		var second = RoomAllocator.allocate(exams, rooms(50, 50, 50));
		assertThat(first).isEqualTo(second);
		assertThat(first.get(0).rooms().get(0).room().name()).isEqualTo("Room A"); // ties: by name, exam "a" first
	}

	@Test
	void rejectsImpossibleStudentNumbers() {
		assertThatThrownBy(() -> new RoomAllocator.Exam("x", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RoomAllocator.Exam("x", RoomAllocator.MAX_STUDENTS_PER_EXAM + 1)).isInstanceOf(IllegalArgumentException.class);
	}

	/** The search is exact: compare with trying every subset on many random cases. */
	@Test
	void bestSubsetMatchesBruteForce() {
		Random random = new Random(42);
		for (int round = 0; round < 500; round++) {
			int n = 1 + random.nextInt(10);
			int[] caps = new int[n];
			int total = 0;
			for (int i = 0; i < n; i++) {
				caps[i] = 5 + random.nextInt(120);
				total += caps[i];
			}
			int students = 1 + random.nextInt(total);
			var rooms = rooms(caps);
			rooms.sort(java.util.Comparator.comparingInt((RoomAllocator.RoomOption<String> r) -> r.capacity()).reversed());

			int bestCount = Integer.MAX_VALUE;
			int bestSeats = Integer.MAX_VALUE;
			for (int mask = 1; mask < (1 << n); mask++) {
				int count = Integer.bitCount(mask);
				int seats = 0;
				for (int i = 0; i < n; i++) {
					if ((mask & (1 << i)) != 0) {
						seats += caps[i];
					}
				}
				if (seats >= students && (count < bestCount || (count == bestCount && seats < bestSeats))) {
					bestCount = count;
					bestSeats = seats;
				}
			}

			var chosen = RoomAllocator.bestSubset(rooms, students);
			int seats = chosen.stream().mapToInt(RoomAllocator.RoomOption::capacity).sum();
			assertThat(chosen).as("round " + round).hasSize(bestCount);
			assertThat(seats).as("round " + round).isEqualTo(bestSeats);
			assertThat(new HashSet<>(chosen)).hasSize(chosen.size()); // no room twice
		}
	}

	@Test
	void aSplitExamStaysInOneBuildingWhenOneBuildingCanSeatIt() {
		var rooms = List.of(
			new RoomAllocator.RoomOption<>("A1", "A101", 70, "A"),
			new RoomAllocator.RoomOption<>("A2", "A102", 70, "A"),
			new RoomAllocator.RoomOption<>("B1", "B201", 50, "B"));
		// Across buildings 70 + 50 would be exact; one building (70 + 70) wins.
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 120)), rooms);
		assertThat(result.get(0).rooms()).extracting(seating -> seating.room().id()).containsExactly("A1", "A2");
	}

	@Test
	void oneBuildingNeverCostsOtherStudentsTheirSeat() {
		var rooms = List.of(
			new RoomAllocator.RoomOption<>("A1", "A101", 70, "A"),
			new RoomAllocator.RoomOption<>("A2", "A102", 70, "A"),
			new RoomAllocator.RoomOption<>("B1", "B201", 50, "B"),
			new RoomAllocator.RoomOption<>("B2", "B202", 60, "B"));
		// 250 seats for 130 + 120 students: no seat to spare. Building A for the 130 exam (70 + 70) would leave only
		// 110 seats for the 120 exam, so the exact rooms across buildings are kept and everyone is seated.
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 130), new RoomAllocator.Exam("y", 120)), rooms);
		assertThat(result.get(0).unplaced() + result.get(1).unplaced()).isZero();
		assertThat(result.get(0).seats()).isEqualTo(130);
	}

	@Test
	void whenNoBuildingIsBigEnoughTheExamSpansBuildings() {
		var rooms = List.of(new RoomAllocator.RoomOption<>("A1", "A101", 50, "A"), new RoomAllocator.RoomOption<>("B1", "B201", 50, "B"));
		var result = RoomAllocator.allocate(List.of(new RoomAllocator.Exam("x", 100)), rooms);
		assertThat(result.get(0).unplaced()).isZero();
		assertThat(result.get(0).rooms()).hasSize(2);
	}
}
