package com.uniguard.schedule.util;

/**
 * CSV for Excel: UTF-8 with a byte-order mark (so Arabic names display correctly), CRLF lines, and
 * protection against spreadsheet formula injection (a cell starting with = + - @ is kept as text).
 */
public final class CsvExport {

	/** Put first in the file so Excel reads it as UTF-8. */
	public static final String BOM = "﻿";

	private CsvExport() {
	}

	public static String cell(String value) {
		if (value == null) {
			return "\"\"";
		}
		String text = value;
		if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
			text = "'" + text;
		}
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}

	public static String row(String... cells) {
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				line.append(',');
			}
			line.append(cell(cells[i]));
		}
		return line.append("\r\n").toString();
	}
}
