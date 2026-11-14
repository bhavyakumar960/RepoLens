package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

/**
 * Request model for code search operations.
 */
@Data
@Builder(toBuilder = true)
public class CodeSearchRequest {
    
    private String query;
    private String repository;
    private String module;
    private String branch;
    private String language;
    
    @Builder.Default
    private Integer topK = 8;
    
    @Builder.Default
    private Integer dependencyDepth = 2;
    
    @Builder.Default
    private Boolean includeTests = false;
    
    @Builder.Default
    private Boolean includeSource = false;
    
    // Adaptive retrieval flags
    @Builder.Default
    private Boolean useVectorSearch = true;
    
    @Builder.Default
    private Boolean useGraphSearch = true;
    
    @Builder.Default
    private Integer multiQueryCount = 2; // Dynamic: 1-3 based on intent

    /**
     * When true, the response's {@code debugInfo} is populated with routing/provenance
     * details (intent, strategy, resolved entities, timing). Off by default to keep
     * normal responses compact; can also be enabled globally via
     * {@code rag.retrieval-debug-enabled}.
     */
    @Builder.Default
    private Boolean debug = false;
}
