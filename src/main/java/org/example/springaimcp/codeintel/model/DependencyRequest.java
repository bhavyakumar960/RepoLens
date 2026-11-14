package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Request for dependency traversal operations.
 */
@Data
@Builder
public class DependencyRequest {
    
    private String symbolId;
    
    @Builder.Default
    private DependencyDirection direction = DependencyDirection.BOTH;
    
    @Builder.Default
    private Integer depth = 1;
    
    @Builder.Default
    private List<RelationshipType> relationshipTypes = List.of(
        RelationshipType.CALLS,
        RelationshipType.USES
    );
    
    @Builder.Default
    private Integer limit = 30;
    
    public enum DependencyDirection {
        INCOMING,   // Who depends on this symbol
        OUTGOING,   // What this symbol depends on
        BOTH        // Both directions
    }
    
    public enum RelationshipType {
        CALLS,
        USES,
        IMPLEMENTS,
        EXTENDS,
        RETURNS,
        ACCEPTS,
        EXPOSES,
        HANDLED_BY,
        TESTS_METHOD,
        READS_FROM,
        WRITES_TO,
        DEPENDS_ON
    }
}
