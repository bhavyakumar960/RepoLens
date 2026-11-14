package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

/**
 * Request model for exact symbol lookup operations.
 */
@Data
@Builder
public class SymbolLookupRequest {
    
    private String name;
    private String repository;
    private String module;
    private CodeSymbol.SymbolType symbolType;
    
    @Builder.Default
    private Boolean includeSource = false;
    
    @Builder.Default
    private Boolean includeRelationships = true;
}
