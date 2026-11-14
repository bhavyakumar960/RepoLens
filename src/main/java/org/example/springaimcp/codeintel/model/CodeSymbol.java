package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a code symbol (class, method, interface, etc.) with its metadata.
 * This is the core domain model for code intelligence retrieval.
 */
@Data
@Builder
public class CodeSymbol {
    
    private String symbolId;
    private SymbolType symbolType;
    private String name;
    private String fullyQualifiedName;
    
    // Location
    private String repository;
    private String module;
    private String packageName;
    private String className;
    private String methodName;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private String branch;
    private String commit;
    
    // Signature (for methods)
    private String signature;
    private String returnType;
    private List<String> parameterTypes;
    
    // Relevance
    private Double relevanceScore;
    
    // Summary (concise description)
    private String summary;
    
    // Relationships
    private List<String> relationships;
    
    // Source code (optional, only when explicitly requested)
    private String sourceCode;
    
    public enum SymbolType {
        CLASS,
        INTERFACE,
        METHOD,
        FIELD,
        ENUM,
        ANNOTATION,
        API_ENDPOINT,
        TEST_METHOD
    }
}
