package com.uniguard.schedule.service;

import org.junit.jupiter.api.Test;

import com.uniguard.schedule.util.RoomNames;

import static org.assertj.core.api.Assertions.assertThat;

class RoomNamesTest {

	@Test
	void recognisesLabs() {
		for (String name : new String[] {"Lab 2", "lab", "M209 (Lab)", "M210(Lab)", "B-Lab", "Chemistry Laboratory", "Computer LAB 3", "معمل 3"}) {
			assertThat(RoomNames.looksLikeLab(name)).as(name).isTrue();
		}
	}

	@Test
	void ignoresWordsThatOnlyContainLab() {
		for (String name : new String[] {"Label Hall", "Syllabus Room", "Hall A", "Collab Space", "M214", "", null}) {
			assertThat(RoomNames.looksLikeLab(name)).as(String.valueOf(name)).isFalse();
		}
	}
}
