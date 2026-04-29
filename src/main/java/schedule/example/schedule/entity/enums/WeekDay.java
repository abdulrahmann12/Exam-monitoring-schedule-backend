package schedule.example.schedule.entity.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.DayOfWeek;
import java.util.Arrays;

public enum WeekDay {
	SUN("Sun", DayOfWeek.SUNDAY),
	MON("Mon", DayOfWeek.MONDAY),
	TUE("Tue", DayOfWeek.TUESDAY),
	WED("Wed", DayOfWeek.WEDNESDAY),
	THU("Thu", DayOfWeek.THURSDAY),
	FRI("Fri", DayOfWeek.FRIDAY),
	SAT("Sat", DayOfWeek.SATURDAY);

	private final String value;
	private final DayOfWeek dayOfWeek;

	WeekDay(String value, DayOfWeek dayOfWeek) {
		this.value = value;
		this.dayOfWeek = dayOfWeek;
	}

	@JsonValue
	public String getValue() {
		return value;
	}

	public DayOfWeek getDayOfWeek() {
		return dayOfWeek;
	}

	public static WeekDay from(DayOfWeek dayOfWeek) {
		return Arrays.stream(values())
			.filter(candidate -> candidate.dayOfWeek == dayOfWeek)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unsupported day: " + dayOfWeek));
	}

	@JsonCreator
	public static WeekDay fromValue(String value) {
		return Arrays.stream(values())
			.filter(candidate -> candidate.value.equalsIgnoreCase(value) || candidate.name().equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unsupported weekday: " + value));
	}
}