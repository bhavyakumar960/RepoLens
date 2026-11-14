package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Context information from symbol resolution.
 * Contains fully qualified name, repository/module filters, and related classes
 * to help with advanced cross-repository searches.
 */
@Data
@Builder
public class SymbolResolutionContext {
    
    /**
     * Original name provided for resolution (simple or FQN).
     */
    private String originalName;
    
    /**
     * Fully qualified name of the resolved symbol.
     */
    private String fullyQualifiedName;
    
    /**
     * Primary repository where the symbol was found.
     */
    private String repository;
    
    /**
     * Primary module where the symbol was found.
     */
    private String module;
    
    /**
     * Package name of the symbol.
     */
    private String packageName;
    
    /**
     * All repositories where this symbol exists (for cross-repo search).
     */
    @Builder.Default
    private List<String> repositories = new ArrayList<>();
    
    /**
     * All modules where this symbol exists.
     */
    @Builder.Default
    private List<String> modules = new ArrayList<>();
    
    /**
     * All packages containing this or related symbols.
     */
    @Builder.Default
    private List<String> packages = new ArrayList<>();
    
    /**
     * Related classes (from USES, EXTENDS, IMPLEMENTS relationships).
     * Useful for discovering other classes to search for.
     */
    @Builder.Default
    private List<String> relatedClasses = new ArrayList<>();
    
    /**
     * All candidate symbols found during resolution.
     */
    @Builder.Default
    private List<CodeSymbol> candidates = new ArrayList<>();
    
    /**
     * Whether the symbol was successfully resolved.
     */
    private boolean resolved;
    
    /**
     * Check if multiple repositories contain this symbol.
     */
    public boolean isCrossRepository() {
        return repositories != null && repositories.size() > 1;
    }
    
    /**
     * Check if multiple modules contain this symbol.
     */
    public boolean isCrossModule() {
        return modules != null && modules.size() > 1;
    }
    
    /**
     * Get primary repository/module filter for searches.
     */
    public String getPrimaryFilter() {
        if (repository != null && module != null) {
            return repository + ":" + module;
        } else if (repository != null) {
            return repository;
        }
        return null;
    }
}
