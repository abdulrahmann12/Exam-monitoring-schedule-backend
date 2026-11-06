package com.uniguard.schedule.config;

import com.uniguard.schedule.config.DuplicateIndexCleanup.Drop;
import com.uniguard.schedule.config.DuplicateIndexCleanup.IndexColumn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateIndexCleanupTest {

	private static IndexColumn col(String table, String index, boolean nonUnique, int position, String column) {
		return new IndexColumn(table, index, nonUnique, "BTREE", position, column, null);
	}

	@Test
	void dropsNumberedCopiesAndKeepsTheNamedIndex() {
		List<IndexColumn> columns = new ArrayList<>();
		columns.add(col("admin_users", "email", false, 1, "email"));
		for (int i = 2; i <= 63; i++) {
			columns.add(col("admin_users", "email_" + i, false, 1, "email"));
		}
		columns.add(col("admin_users", "idx_admin_user_email", false, 1, "email"));

		List<Drop> drops = DuplicateIndexCleanup.planDrops(columns);

		assertThat(drops).hasSize(63);
		assertThat(drops).extracting(Drop::index).doesNotContain("idx_admin_user_email");
		assertThat(drops).extracting(Drop::keptIndex).containsOnly("idx_admin_user_email");
	}

	@Test
	void keepsIndexesThatDifferInUniquenessColumnsOrOrder() {
		List<IndexColumn> columns = List.of(
			col("people", "idx_person_email", false, 1, "email"),
			col("people", "idx_email_plain", true, 1, "email"),              // not unique
			col("people", "idx_a_b", true, 1, "a"), col("people", "idx_a_b", true, 2, "b"),
			col("people", "idx_b_a", true, 1, "b"), col("people", "idx_b_a", true, 2, "a"), // other order
			col("people", "idx_a", true, 1, "a"),                              // prefix of idx_a_b
			col("rooms", "idx_email_plain", true, 1, "email"),                 // other table
			new IndexColumn("people", "idx_email_prefix", true, "BTREE", 1, "email", 10L), // prefix length
			new IndexColumn("people", "ft_email", true, "FULLTEXT", 1, "email", null));    // other type

		assertThat(DuplicateIndexCleanup.planDrops(columns)).isEmpty();
	}

	@Test
	void multiColumnDuplicatesAreDetectedWhateverTheRowOrder() {
		List<IndexColumn> columns = List.of(
			col("room_assignments", "UKabc", false, 2, "slot"), col("room_assignments", "UKabc", false, 1, "date"),
			col("room_assignments", "uk_ra_date_slot", false, 1, "date"), col("room_assignments", "uk_ra_date_slot", false, 2, "slot"));

		assertThat(DuplicateIndexCleanup.planDrops(columns))
			.containsExactly(new Drop("room_assignments", "UKabc", "uk_ra_date_slot"));
	}

	@Test
	void functionalIndexesWithoutAColumnNameAreNeverTouched() {
		List<IndexColumn> columns = List.of(
			col("people", "idx_f1", true, 1, null),
			col("people", "idx_f2", true, 1, null));

		assertThat(DuplicateIndexCleanup.planDrops(columns)).isEmpty();
	}
}
