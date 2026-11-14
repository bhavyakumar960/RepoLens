package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Request for retrieving exact source code context for a known symbol.
 */
@Data
@Builder
public class CodeContextRequest {
    
    private String symbolId;
    
    @Builder.Default
    private List<ContextInclude> include = List.of(
        ContextInclude.IMPLEMENTATION,
        ContextInclude.CLASS_CONTEXT
    );
    
    @Builder.Default
    private Integer maxLines = 0; // 0 = use default from properties
    
    @Builder.Default
    private Integer contextLines = 5; // Lines before/after
    
    public enum ContextInclude {
        IMPLEMENTATION,      // Method/class implementation
        CLASS_CONTEXT,       // Surrounding class definition
        IMPORTS,             // Import statements
        ANNOTATIONS,         // Annotations
        JAVADOC,            // JavaDoc comments
        RELATED_METHODS     // Other methods in same class
    }
}
