package com.uniguard.schedule.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Guesses from a room's name. Only a default: the administrator can change it per room. */
public final class RoomNames {

	/** "Lab 2", "M209 (Lab)", "M210(Lab)", "B-Lab", "Chemistry Laboratory", "معمل 3". Not "Label" or "Syllabus Hall". */
	private static final Pattern LAB = Pattern.compile("(^|[\\s(\\-])lab(?![a-z])|laboratory|معمل");

	private RoomNames() {
	}

	/** Same rule as migration V18 (plus the Arabic word for lab). */
	public static boolean looksLikeLab(String name) {
		return name != null && LAB.matcher(name.toLowerCase(Locale.ROOT)).find();
	}
}
