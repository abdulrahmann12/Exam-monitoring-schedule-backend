package com.uniguard.schedule.util;

import java.awt.Color;

import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.pdf.BaseFont;

/** A Unicode font for PDFs (Arabic names work): the system's Arial/DejaVu, else Helvetica. */
public final class PdfFonts {

	private PdfFonts() {
	}

	public static Font resolve(float size, int style, Color color) {
		String windir = System.getenv("WINDIR");
		String[] candidates = {
			windir != null ? windir + "/Fonts/arial.ttf" : null,
			"C:/Windows/Fonts/arial.ttf",
			"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
			"/System/Library/Fonts/Supplemental/Arial.ttf"
		};
		for (String path : candidates) {
			if (path == null) {
				continue;
			}
			try {
				BaseFont baseFont = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
				return new Font(baseFont, size, style, color);
			} catch (Exception ignored) {
				// Try the next installed Unicode font.
			}
		}
		return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
	}
}
