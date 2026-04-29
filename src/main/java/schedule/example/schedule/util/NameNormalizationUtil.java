package schedule.example.schedule.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class NameNormalizationUtil {

	private static final Pattern MULTIPLE_WHITESPACE = Pattern.compile("\\s+");

	private NameNormalizationUtil() {
	}

	public static String normalizeWhitespace(String value) {
		if (value == null) {
			return null;
		}

		return MULTIPLE_WHITESPACE.matcher(value.trim()).replaceAll(" ");
	}

	public static String normalizeForComparison(String value) {
		String normalized = normalizeWhitespace(value);
		if (normalized == null) {
			return null;
		}

		return normalized.toLowerCase(Locale.ROOT);
	}

	public static String normalizeEnumToken(String value) {
		String normalized = normalizeWhitespace(value);
		if (normalized == null || normalized.isBlank()) {
			return "";
		}

		return normalized
			.replace('-', '_')
			.replace(' ', '_')
			.toUpperCase(Locale.ROOT);
	}
}