package schedule.example.schedule.util;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DuplicateResolverUtil {

	public ResolvedName resolveUniqueName(String originalName, Set<String> reservedNormalizedNames, int maxLength) {
		String baseName = NameNormalizationUtil.normalizeWhitespace(originalName);
		String normalizedBaseName = NameNormalizationUtil.normalizeForComparison(baseName);

		if (normalizedBaseName == null || normalizedBaseName.isBlank()) {
			throw new IllegalArgumentException("A non-blank name is required to resolve duplicates.");
		}

		if (!reservedNormalizedNames.contains(normalizedBaseName)) {
			reservedNormalizedNames.add(normalizedBaseName);
			return new ResolvedName(baseName, false);
		}

		for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
			String suffixText = " (" + suffix + ")";
			String candidateBaseName = truncateToLength(baseName, maxLength - suffixText.length());
			String candidateName = candidateBaseName + suffixText;
			String normalizedCandidateName = NameNormalizationUtil.normalizeForComparison(candidateName);

			if (!reservedNormalizedNames.contains(normalizedCandidateName)) {
				reservedNormalizedNames.add(normalizedCandidateName);
				return new ResolvedName(candidateName, true);
			}
		}

		throw new IllegalStateException("Could not resolve a unique suffix for name: " + baseName);
	}

	private String truncateToLength(String value, int maxLength) {
		if (value.length() <= maxLength) {
			return value;
		}

		return value.substring(0, maxLength).trim();
	}

	public record ResolvedName(String value, boolean duplicateResolved) {
	}
}