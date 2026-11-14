package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

/**
 * Request for impact analysis.
 */
@Data
@Builder
public class ImpactRequest {
    
    private String symbolId;
    
    @Builder.Default
    private Integer depth = 3;
    
    @Builder.Default
    private Boolean includeTests = true;
    
    @Builder.Default
    private Boolean includeApis = true;
}
