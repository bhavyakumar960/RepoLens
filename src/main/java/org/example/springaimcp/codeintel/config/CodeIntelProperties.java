package org.example.springaimcp.codeintel.config;

import lombok.Data;

/**
 * Configuration properties for the code intelligence retrieval layer.
 * Bean creation and @ConfigurationProperties binding is handled in CodeIntelConfiguration.
 */
@Data
public class CodeIntelProperties {
    
    /**
     * Default number of top results to return from semantic search.
     */
    private Integer topK = 8;
    
    /**
     * Maximum dependency traversal depth.
     */
    private Integer maxDepth = 5;
    
    /**
     * Maximum number of results to return.
     */
    private Integer maxResults = 100;
    
    /**
     * Maximum lines of source code to retrieve per symbol.
     */
    private Integer maxLines = 500;

    /**
     * Maximum number of top-ranked symbols (per response) that get full source code
     * enrichment when source is requested. Lower-ranked symbols in the same response
     * are returned without source code to keep responses compact and token-efficient.
     * Set to 0 or negative to disable the cap (enrich all returned symbols).
     */
    private Integer maxSourceSymbols = 3;

    /**
     * Maximum lines of source code embedded per symbol in the compact text summary
     * ({@code CodeSearchResponse#toCompactSummary()}). Independent of maxLines, which
     * governs how much source is fetched from disk; this governs how much of that
     * source is echoed back in the LLM-facing summary text.
     */
    private Integer maxSummarySourceLines = 60;
    
    /**
     * Timeout in milliseconds for retrieval operations.
     */
    private Long timeoutMs = 30000L;
    
    /**
     * Enable caching for symbol lookups.
     */
    private Boolean enableCaching = true;
    
    /**
     * Cache time-to-live in seconds.
     */
    private Long cacheTtlSeconds = 300L;
}
