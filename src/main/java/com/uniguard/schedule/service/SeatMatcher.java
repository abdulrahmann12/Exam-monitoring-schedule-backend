package com.uniguard.schedule.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maximum bipartite matching of seats to people, with per-person capacity (augmenting paths,
 * i.e. Kuhn's algorithm generalised to capacities).
 *
 * <p>Why: the old greedy filler took the best candidate room by room. One early pick could use up
 * the only person who fits a later seat (e.g. the only female invigilator going to a mixed room,
 * leaving the female hall empty). Matching reassigns earlier picks when that lets another seat be
 * filled, so the number of filled seats in a sitting is always the maximum possible.
 *
 * <p>Fairness: each seat's candidate list is expected in preference order (least workload first);
 * candidates are tried in that order, so low-load people are chosen whenever the maximum allows.
 * Seats are processed in the given order and a filled seat is never emptied again, so put
 * mandatory seats before optional ones.
 */
final class SeatMatcher {

	private SeatMatcher() {
	}

	/**
	 * @param candidates {@code candidates.get(seat)} = person indexes eligible for that seat, best first
	 * @param capacity   {@code capacity[person]} = how many seats that person may take (≥ 0)
	 * @return {@code result[seat]} = matched person index, or -1 when the seat stays empty
	 */
	static int[] match(List<List<Integer>> candidates, int[] capacity) {
		int[] empty = new int[candidates.size()];
		Arrays.fill(empty, -1);
		return match(candidates, capacity, empty);
	}

	/**
	 * Continues from an existing (valid) partial matching and only tries to fill the empty seats.
	 * Because it only augments, the result is still a maximum matching — used to spread load first
	 * (one seat per person) and add extra seats per person only where needed.
	 */
	static int[] match(List<List<Integer>> candidates, int[] capacity, int[] initial) {
		int seats = candidates.size();
		int[] seatToPerson = Arrays.copyOf(initial, seats);
		List<List<Integer>> personToSeats = new ArrayList<>(capacity.length);
		for (int p = 0; p < capacity.length; p++) {
			personToSeats.add(new ArrayList<>());
		}
		for (int seat = 0; seat < seats; seat++) {
			if (seatToPerson[seat] >= 0) {
				personToSeats.get(seatToPerson[seat]).add(seat);
			}
		}
		for (int seat = 0; seat < seats; seat++) {
			if (seatToPerson[seat] >= 0) {
				continue;
			}
			boolean[] visited = new boolean[capacity.length];
			augment(seat, candidates, capacity, seatToPerson, personToSeats, visited);
		}
		return seatToPerson;
	}

	private static boolean augment(
		int seat,
		List<List<Integer>> candidates,
		int[] capacity,
		int[] seatToPerson,
		List<List<Integer>> personToSeats,
		boolean[] visited
	) {
		// 1) A preferred candidate who still has room takes the seat directly (no reshuffling).
		for (int person : candidates.get(seat)) {
			if (!visited[person] && capacity[person] > 0 && personToSeats.get(person).size() < capacity[person]) {
				visited[person] = true;
				personToSeats.get(person).add(seat);
				seatToPerson[seat] = person;
				return true;
			}
		}
		// 2) Otherwise free someone up: move one of a full candidate's seats to another person.
		for (int person : candidates.get(seat)) {
			if (visited[person] || capacity[person] <= 0) {
				continue;
			}
			visited[person] = true;
			List<Integer> held = personToSeats.get(person);
			for (int i = 0; i < held.size(); i++) {
				int otherSeat = held.get(i);
				if (augment(otherSeat, candidates, capacity, seatToPerson, personToSeats, visited)) {
					held.set(i, seat);
					seatToPerson[seat] = person;
					return true;
				}
			}
		}
		return false;
	}
}
