package com.uniguard.schedule.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes exact duplicate indexes on MySQL / MariaDB before Flyway migrates.
 *
 * <p>The old version let Hibernate ({@code ddl-auto=update}) manage the schema. On MariaDB it added the same
 * unique index again at every start ({@code email}, {@code email_2}, ...), until a table reached MySQL's limit of
 * 64 indexes and any migration adding an index to that table failed with "Too many keys specified".
 *
 * <p>Only indexes that are identical to another index on the same table (same columns in the same order, same
 * prefix lengths, same uniqueness and type) are dropped, so no constraint or query path changes. The named index
 * (e.g. {@code idx_person_email}) is kept, because migrations refer to it by name. Does nothing when there are no
 * duplicates, and never blocks startup: a drop that the database refuses is logged and skipped.
 */
@Component
public class DuplicateIndexCleanup implements Callback {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuplicateIndexCleanup.class);
	private static final Pattern NUMBERED_COPY = Pattern.compile(".*_\\d+$");

	/** One column of one index, as listed by information_schema.statistics. */
	record IndexColumn(String table, String index, boolean nonUnique, String type, int position, String column, Long subPart) {
	}

	/** An index to drop. */
	record Drop(String table, String index, String keptIndex) {
	}

	@Override
	public boolean supports(Event event, Context context) {
		return event == Event.BEFORE_MIGRATE;
	}

	@Override
	public boolean canHandleInTransaction(Event event, Context context) {
		return true;
	}

	@Override
	public String getCallbackName() {
		return "duplicate-index-cleanup";
	}

	@Override
	public void handle(Event event, Context context) {
		Connection connection = context.getConnection();
		try {
			String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
			if (!product.contains("mysql") && !product.contains("mariadb")) {
				return;
			}
			List<Drop> drops = planDrops(readIndexes(connection));
			for (Drop drop : drops) {
				try (var statement = connection.createStatement()) {
					statement.execute("ALTER TABLE `" + drop.table() + "` DROP INDEX `" + drop.index() + "`");
					LOGGER.info("Removed duplicate index {}.{} (same as {})", drop.table(), drop.index(), drop.keptIndex());
				} catch (SQLException e) {
					LOGGER.warn("Could not remove duplicate index {}.{}: {}", drop.table(), drop.index(), e.getMessage());
				}
			}
			if (!drops.isEmpty()) {
				LOGGER.info("Duplicate index cleanup finished: {} removed", drops.size());
			}
		} catch (SQLException e) {
			LOGGER.warn("Duplicate index cleanup skipped: {}", e.getMessage());
		}
	}

	private static List<IndexColumn> readIndexes(Connection connection) throws SQLException {
		List<IndexColumn> columns = new ArrayList<>();
		try (var statement = connection.createStatement();
			 var rows = statement.executeQuery("""
				 SELECT table_name, index_name, non_unique, index_type, seq_in_index, column_name, sub_part
				 FROM information_schema.statistics
				 WHERE table_schema = DATABASE() AND index_name <> 'PRIMARY'
				 ORDER BY table_name, index_name, seq_in_index""")) {
			while (rows.next()) {
				long subPart = rows.getLong(7);
				columns.add(new IndexColumn(rows.getString(1), rows.getString(2), rows.getInt(3) != 0, rows.getString(4),
					rows.getInt(5), rows.getString(6), rows.wasNull() ? null : subPart));
			}
		}
		return columns;
	}

	/** Groups identical indexes per table and returns every index except the preferred one of each group. */
	static List<Drop> planDrops(List<IndexColumn> columns) {
		Map<String, Map<String, List<IndexColumn>>> byTable = new LinkedHashMap<>();
		for (IndexColumn column : columns) {
			byTable.computeIfAbsent(column.table(), t -> new LinkedHashMap<>())
				.computeIfAbsent(column.index(), i -> new ArrayList<>())
				.add(column);
		}
		List<Drop> drops = new ArrayList<>();
		byTable.forEach((table, indexes) -> {
			Map<String, List<String>> bySignature = new LinkedHashMap<>();
			indexes.forEach((index, parts) -> {
				String signature = signature(parts);
				if (signature != null) {
					bySignature.computeIfAbsent(signature, s -> new ArrayList<>()).add(index);
				}
			});
			for (List<String> same : bySignature.values()) {
				if (same.size() < 2) {
					continue;
				}
				List<String> ordered = same.stream().sorted(KEEP_FIRST).toList();
				String kept = ordered.getFirst();
				ordered.stream().skip(1).forEach(index -> drops.add(new Drop(table, index, kept)));
			}
		});
		return drops;
	}

	/** Null for indexes we cannot compare safely (e.g. functional indexes without a column name). */
	private static String signature(List<IndexColumn> parts) {
		StringBuilder signature = new StringBuilder();
		IndexColumn first = parts.getFirst();
		signature.append(first.nonUnique() ? "N" : "U").append('|').append(first.type());
		List<IndexColumn> sorted = parts.stream().sorted(Comparator.comparingInt(IndexColumn::position)).toList();
		for (IndexColumn part : sorted) {
			if (part.column() == null) {
				return null;
			}
			signature.append('|').append(part.column().toLowerCase(Locale.ROOT));
			if (part.subPart() != null) {
				signature.append('(').append(part.subPart()).append(')');
			}
		}
		return signature.toString();
	}

	/** Our own names first (migrations refer to them), then names without a "_2" copy suffix, then the shortest. */
	private static final Comparator<String> KEEP_FIRST = Comparator
		.comparingInt((String name) -> isOwnName(name) ? 0 : 1)
		.thenComparingInt(name -> NUMBERED_COPY.matcher(name).matches() ? 1 : 0)
		.thenComparingInt(String::length)
		.thenComparing(Comparator.naturalOrder());

	private static boolean isOwnName(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.startsWith("idx_") || lower.startsWith("ux_") || lower.startsWith("uk_") || lower.startsWith("fk_");
	}
}
