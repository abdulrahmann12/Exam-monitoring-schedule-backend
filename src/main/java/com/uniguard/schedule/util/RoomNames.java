package com.uniguard.schedule.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Guesses from a room's name. Only a default: the administrator can change it per room. */
public final class RoomNames {

	/** "Lab 2", "M209 (Lab)", "M210(Lab)", "B-Lab", "Chemistry Laboratory", "معمل 3". Not "Label" or "Syllabus Hall". */
	private static final Pattern LAB = Pattern.compile("(^|[\\s(\\-])lab(?![a-z])|laboratory|معمل");

	/** "H101", "M214 (draft)", "S-12", "B2.05": 1–3 letters right before the number. Not "Lab 2" or "Hall 100". */
	private static final Pattern BUILDING = Pattern.compile("^([A-Za-z]{1,3})[-.]?\\d");

	private RoomNames() {
	}

	/** The building from a room name, upper case; null when the name does not show one. */
	public static String guessBuilding(String name) {
		if (name == null) {
			return null;
		}
		java.util.regex.Matcher matcher = BUILDING.matcher(name.trim());
		return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
	}

	/** Same rule as migration V18 (plus the Arabic word for lab). */
	public static boolean looksLikeLab(String name) {
		return name != null && LAB.matcher(name.toLowerCase(Locale.ROOT)).find();
	}
}
