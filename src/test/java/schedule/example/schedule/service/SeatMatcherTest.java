package schedule.example.schedule.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeatMatcherTest {

	/**
	 * Seat 0 = mixed room (anyone), seat 1 = female hall (only person 0).
	 * Greedy would give seat 0 to its first choice (person 0) and leave seat 1 empty.
	 * Matching moves person 1 to seat 0 so both seats are filled.
	 */
	@Test
	void reassignsEarlierPicksToFillMoreSeats() {
		List<List<Integer>> candidates = List.of(
			List.of(0, 1), // mixed room: prefers person 0 (lower load)
			List.of(0)     // female hall: only person 0 qualifies
		);
		int[] result = SeatMatcher.match(candidates, new int[]{1, 1});
		assertThat(result).containsExactly(1, 0);
	}

	@Test
	void respectsCapacityAboveOne() {
		// One chief may cover two rooms; three rooms, one chief → one stays empty.
		int[] result = SeatMatcher.match(List.of(List.of(0), List.of(0), List.of(0)), new int[]{2});
		assertThat(result).containsExactly(0, 0, -1);
	}

	@Test
	void prefersCandidatesInTheGivenOrderWhenNothingForcesOtherwise() {
		int[] result = SeatMatcher.match(List.of(List.of(2, 0, 1), List.of(2, 0, 1)), new int[]{1, 1, 1});
		assertThat(result).containsExactly(2, 0);
	}

	@Test
	void spreadingFirstThenSecondSeatsStillReachesTheMaximum() {
		// Chief 0 may take 2 rooms, chief 1 only fits room 0. Three rooms.
		List<List<Integer>> candidates = List.of(List.of(0, 1), List.of(0), List.of(0));
		int[] spread = SeatMatcher.match(candidates, new int[]{1, 1});
		assertThat(spread).containsExactly(1, 0, -1);
		int[] full = SeatMatcher.match(candidates, new int[]{2, 1}, spread);
		assertThat(full).containsExactly(1, 0, 0);
	}

	@Test
	void earlierSeatsAreNeverEmptiedForLaterOnes() {
		// Required seat first, optional second; only one person — the required seat keeps them.
		int[] result = SeatMatcher.match(List.of(List.of(0), List.of(0)), new int[]{1});
		assertThat(result).containsExactly(0, -1);
	}
}
