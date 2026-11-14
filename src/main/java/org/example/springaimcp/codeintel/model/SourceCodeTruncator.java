package org.example.springaimcp.codeintel.model;

import java.util.Arrays;

/**
 * Shared source-code truncation logic used by every LLM-facing formatter
 * ({@code CompactEvidenceFormatter}, {@code CodeSearchResponse}, ...).
 * <p>
 * Centralizing this avoids duplicated (and potentially drifting) truncation
 * implementations across formatters, and gives us one place to tune how
 * source-line budgets scale with result-set size.
 */
public final class SourceCodeTruncator {

    private SourceCodeTruncator() {
    }

    /**
     * Truncate source code to at most {@code maxLines} lines, appending a marker
     * indicating how many lines were omitted. A non-positive {@code maxLines}
     * disables truncation.
     */
    public static String truncate(String sourceCode, int maxLines) {
        if (sourceCode == null || maxLines <= 0) {
            return sourceCode;
        }

        String[] lines = sourceCode.split("\n", -1);
        if (lines.length <= maxLines) {
            return sourceCode;
        }

        String truncated = String.join("\n", Arrays.copyOfRange(lines, 0, maxLines));
        return truncated + String.format("\n... (%d more line(s) omitted)", lines.length - maxLines);
    }

    /**
     * Scales the per-symbol source-line cap down as the result count grows, so a
     * response with many results doesn't multiply a large fixed per-symbol cap
     * (e.g. 8 results x 60 lines each). Single-digit result sets keep the full
     * {@code baseMaxLines} budget; larger sets shrink proportionally, bounded by
     * {@code minLines}.
     */
    public static int scaleForResultCount(int baseMaxLines, int resultCount, int minLines) {
        if (baseMaxLines <= 0 || resultCount <= 3) {
            return baseMaxLines;
        }
        int scaled = Math.max(minLines, baseMaxLines * 3 / resultCount);
        return Math.min(baseMaxLines, scaled);
    }
}
