package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

/**
 * Request for architecture context.
 */
@Data
@Builder
public class ArchitectureRequest {
    
    private String query;
    private String repository;
    private String module;
    
    @Builder.Default
    private Integer depth = 3;
    
    @Builder.Default
    private Boolean includeApis = true;
    
    @Builder.Default
    private Boolean includeServices = true;
    
    @Builder.Default
    private Boolean includeRepositories = true;
}
