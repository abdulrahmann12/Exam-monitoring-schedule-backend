package com.uniguard.schedule.security;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "At most N events per key within a time window", in memory. Good enough for a single server;
 * a multi-server deployment would move this to a shared store.
 */
public class SlidingWindowRateLimiter {

	private final int limit;
	private final long windowMillis;
	private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

	public SlidingWindowRateLimiter(int limit, Duration window) {
		this.limit = limit;
		this.windowMillis = window.toMillis();
	}

	/** Records an event for the key; false (and nothing recorded) when the key is over its limit. */
	public boolean tryAcquire(String key) {
		long now = System.currentTimeMillis();
		if (events.size() > 10_000) {
			prune(now);
		}
		Deque<Long> times = events.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
				times.pollFirst();
			}
			if (times.size() >= limit) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}

	private void prune(long now) {
		events.entrySet().removeIf(entry -> {
			synchronized (entry.getValue()) {
				Long last = entry.getValue().peekLast();
				return last == null || now - last > windowMillis;
			}
		});
	}
}
