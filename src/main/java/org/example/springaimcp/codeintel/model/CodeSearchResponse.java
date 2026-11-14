package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response model for code search operations.
 * Optimized for low-token LLM interaction.
 */
@Data
@Builder
public class CodeSearchResponse {

    /**
     * Default cap on source code lines echoed per symbol in {@link #toCompactSummary()}.
     * Keeps the LLM-facing text compact regardless of how much source was fetched
     * from disk (see CodeIntelProperties#maxLines vs maxSummarySourceLines).
     */
    private static final int DEFAULT_MAX_SUMMARY_SOURCE_LINES = 60;

    /**
     * Floor applied when scaling the per-symbol source-line cap down for large
     * result sets (see {@link SourceCodeTruncator#scaleForResultCount}).
     */
    private static final int MIN_SUMMARY_SOURCE_LINES = 15;

    private String query;
    private List<CodeSymbol> results;
    private String dependencySummary;
    private Integer totalResults;

    /**
     * Optional retrieval provenance/debug info, populated only when the originating
     * {@code CodeSearchRequest#debug} flag (or {@code rag.retrieval-debug-enabled}) is set.
     */
    private RetrievalDebugInfo debugInfo;
    
    /**
     * Get a compact summary of the search results suitable for LLM consumption.
     */
    public String toCompactSummary() {
        return toCompactSummary(DEFAULT_MAX_SUMMARY_SOURCE_LINES);
    }

    /**
     * Get a compact summary of the search results suitable for LLM consumption,
     * truncating any embedded source code to at most {@code maxSourceLines} lines.
     */
    public String toCompactSummary(int maxSourceLines) {
        if (results == null || results.isEmpty()) {
            return "No results found for query: " + query;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d result(s) for: %s\n\n", totalResults, query));

        // Shrink the per-symbol source-line cap as the result count grows, so a
        // large result set doesn't multiply a large fixed per-symbol cap.
        int scaledMaxSourceLines = SourceCodeTruncator.scaleForResultCount(
                maxSourceLines, results.size(), MIN_SUMMARY_SOURCE_LINES);

        for (CodeSymbol symbol : results) {
            sb.append(formatSymbol(symbol, scaledMaxSourceLines)).append("\n\n");
        }
        
        if (dependencySummary != null && !dependencySummary.isBlank()) {
            sb.append("Dependencies:\n").append(dependencySummary);
        }

        if (debugInfo != null) {
            sb.append("\n\nDebug: ").append(debugInfo.toSummary());
        }

        return sb.toString();
    }
    
    private String formatSymbol(CodeSymbol symbol, int maxSourceLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("• %s: %s", symbol.getSymbolType(), symbol.getName()));
        
        if (symbol.getRelevanceScore() != null) {
            sb.append(String.format(" [%.2f]", symbol.getRelevanceScore()));
        }
        
        sb.append(String.format("\n  Location: %s:%s", 
            symbol.getFilePath(), 
            formatLineRange(symbol.getStartLine(), symbol.getEndLine())));
        
        if (symbol.getRepository() != null) {
            sb.append(String.format("\n  Repo: %s", symbol.getRepository()));
        }
        
        if (symbol.getModule() != null) {
            sb.append(String.format(" | Module: %s", symbol.getModule()));
        }
        
        if (symbol.getSummary() != null) {
            sb.append(String.format("\n  Summary: %s", symbol.getSummary()));
        }
        
        if (symbol.getRelationships() != null && !symbol.getRelationships().isEmpty()) {
            sb.append(String.format("\n  Relationships: %s", 
                String.join(", ", symbol.getRelationships())));
        }
        
        if (symbol.getSourceCode() != null) {
            sb.append("\n  Source:\n```\n");
            sb.append(truncateSource(symbol.getSourceCode(), maxSourceLines));
            sb.append("\n```");
        }
        
        return sb.toString();
    }

    /**
     * Truncate source code to at most {@code maxLines} lines, appending a marker
     * indicating how many lines were omitted. A non-positive {@code maxLines}
     * disables truncation.
     */
    private String truncateSource(String sourceCode, int maxLines) {
        return SourceCodeTruncator.truncate(sourceCode, maxLines);
    }
    
    private String formatLineRange(Integer start, Integer end) {
        if (start == null) return "?";
        if (end == null || start.equals(end)) return String.valueOf(start);
        return String.format("%d-%d", start, end);
    }
}
