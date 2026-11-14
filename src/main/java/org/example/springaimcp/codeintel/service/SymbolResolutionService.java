package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.codeintel.model.SymbolResolutionContext;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for advanced symbol resolution using imports and fully qualified names.
 * 
 * This service provides fallback mechanisms for cross-repository searches:
 * 1. Resolves simple names to fully qualified names using import information
 * 2. Extracts repository and module filters from found symbols
 * 3. Performs cross-repository searches using discovered FQNs
 * 
 * Use case: When a symbol is not found in the current repository,
 * this service helps discover it in other repositories by:
 * - Using the full package path
 * - Extracting related classes from import statements
 * - Providing better search context for subsequent queries
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SymbolResolutionService {
    
    private final JavaClassRepository classRepository;
    private final CodeIntelProperties properties;
    
    /**
     * Resolve a simple class name to its fully qualified name and additional context.
     * This method searches across all repositories to find the class and extract
     * repository/module information for better filtering.
     * 
     * @param simpleName the simple class name (e.g., "UserService")
     * @return resolution context with FQN, repository, module, and related classes
     */
    public SymbolResolutionContext resolveSymbol(String simpleName) {
        log.debug("Resolving symbol: {}", simpleName);
        
        // Search for the class across all repositories
        List<JavaClass> candidates = classRepository.searchByName(simpleName, properties.getMaxResults());
        
        if (candidates.isEmpty()) {
            log.debug("No candidates found for symbol: {}", simpleName);
            return SymbolResolutionContext.builder()
                .originalName(simpleName)
                .resolved(false)
                .build();
        }
        
        // Use the first candidate as primary resolution
        JavaClass primary = candidates.get(0);
        log.debug("Resolved '{}' to '{}' in repository '{}', module '{}'", 
            simpleName, primary.getFullyQualifiedName(), primary.getRepository(), primary.getModule());
        
        // Extract unique repositories and modules from all candidates
        Set<String> repositories = candidates.stream()
            .map(JavaClass::getRepository)
            .filter(r -> r != null && !r.isBlank())
            .collect(Collectors.toSet());
        
        Set<String> modules = candidates.stream()
            .map(JavaClass::getModule)
            .filter(m -> m != null && !m.isBlank())
            .collect(Collectors.toSet());
        
        Set<String> packages = candidates.stream()
            .map(JavaClass::getPackageName)
            .filter(p -> p != null && !p.isBlank())
            .collect(Collectors.toSet());
        
        // Extract related classes from USES relationships
        Set<String> relatedClasses = extractRelatedClasses(primary);
        
        return SymbolResolutionContext.builder()
            .originalName(simpleName)
            .fullyQualifiedName(primary.getFullyQualifiedName())
            .repository(primary.getRepository())
            .module(primary.getModule())
            .packageName(primary.getPackageName())
            .repositories(new ArrayList<>(repositories))
            .modules(new ArrayList<>(modules))
            .packages(new ArrayList<>(packages))
            .relatedClasses(new ArrayList<>(relatedClasses))
            .candidates(candidates.stream().map(this::javaClassToCodeSymbol).toList())
            .resolved(true)
            .build();
    }
    
    /**
     * Resolve a fully qualified name to find all instances across repositories.
     * 
     * @param fqn the fully qualified name (e.g., "com.example.UserService")
     * @return resolution context with repository and module filters
     */
    public SymbolResolutionContext resolveByFullyQualifiedName(String fqn) {
        log.debug("Resolving by FQN: {}", fqn);
        
        List<JavaClass> candidates = classRepository.findByFullyQualifiedName(fqn, properties.getMaxResults());
        
        if (candidates.isEmpty()) {
            log.debug("No candidates found for FQN: {}", fqn);
            
            // Try extracting simple name from FQN and search again
            String simpleName = extractSimpleName(fqn);
            if (!simpleName.equals(fqn)) {
                log.debug("Extracted simple name '{}' from FQN, retrying...", simpleName);
                return resolveSymbol(simpleName);
            }
            
            return SymbolResolutionContext.builder()
                .originalName(fqn)
                .fullyQualifiedName(fqn)
                .resolved(false)
                .build();
        }
        
        JavaClass primary = candidates.get(0);
        
        Set<String> repositories = candidates.stream()
            .map(JavaClass::getRepository)
            .filter(r -> r != null && !r.isBlank())
            .collect(Collectors.toSet());
        
        Set<String> modules = candidates.stream()
            .map(JavaClass::getModule)
            .filter(m -> m != null && !m.isBlank())
            .collect(Collectors.toSet());
        
        Set<String> packages = candidates.stream()
            .map(JavaClass::getPackageName)
            .filter(p -> p != null && !p.isBlank())
            .collect(Collectors.toSet());
        
        Set<String> relatedClasses = extractRelatedClasses(primary);
        
        return SymbolResolutionContext.builder()
            .originalName(fqn)
            .fullyQualifiedName(primary.getFullyQualifiedName())
            .repository(primary.getRepository())
            .module(primary.getModule())
            .packageName(primary.getPackageName())
            .repositories(new ArrayList<>(repositories))
            .modules(new ArrayList<>(modules))
            .packages(new ArrayList<>(packages))
            .relatedClasses(new ArrayList<>(relatedClasses))
            .candidates(candidates.stream().map(this::javaClassToCodeSymbol).toList())
            .resolved(true)
            .build();
    }
    
    /**
     * Search for related classes using the resolution context.
     * This helps discover other classes in the same repository/module/package.
     * 
     * @param context the resolution context from previous resolution
     * @return list of related code symbols
     */
    public List<CodeSymbol> findRelatedSymbols(SymbolResolutionContext context) {
        if (!context.isResolved()) {
            return List.of();
        }
        
        log.debug("Finding related symbols for: {}", context.getFullyQualifiedName());
        
        Set<CodeSymbol> related = new LinkedHashSet<>();
        
        // Search by package name (same package usually contains related classes)
        if (context.getPackageName() != null) {
            List<JavaClass> packageClasses = classRepository.searchByPartialName(
                context.getPackageName(), properties.getMaxResults());
            related.addAll(packageClasses.stream().map(this::javaClassToCodeSymbol).toList());
        }
        
        // Search for classes in the same repository and module
        if (context.getRepository() != null && context.getModule() != null) {
            List<JavaClass> moduleClasses = classRepository.findByRepositoryAndModule(
                context.getRepository(), context.getModule());
            related.addAll(moduleClasses.stream()
                .limit(properties.getMaxResults())
                .map(this::javaClassToCodeSymbol).toList());
        }
        
        log.debug("Found {} related symbol(s)", related.size());
        return new ArrayList<>(related);
    }
    
    /**
     * Extract related classes from USES relationships.
     * These are classes imported/used by the given class.
     */
    private Set<String> extractRelatedClasses(JavaClass javaClass) {
        Set<String> related = new LinkedHashSet<>();
        
        if (javaClass.getUsedClasses() != null) {
            related.addAll(javaClass.getUsedClasses().stream()
                .map(JavaClass::getFullyQualifiedName)
                .filter(fqn -> fqn != null && !fqn.isBlank())
                .collect(Collectors.toSet()));
        }
        
        if (javaClass.getExtendedClasses() != null) {
            related.addAll(javaClass.getExtendedClasses().stream()
                .map(JavaClass::getFullyQualifiedName)
                .filter(fqn -> fqn != null && !fqn.isBlank())
                .collect(Collectors.toSet()));
        }
        
        if (javaClass.getImplementedInterfaces() != null) {
            related.addAll(javaClass.getImplementedInterfaces().stream()
                .map(JavaClass::getFullyQualifiedName)
                .filter(fqn -> fqn != null && !fqn.isBlank())
                .collect(Collectors.toSet()));
        }
        
        return related;
    }
    
    /**
     * Extract simple name from fully qualified name.
     */
    private String extractSimpleName(String fqn) {
        if (fqn == null || !fqn.contains(".")) {
            return fqn;
        }
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
    
    /**
     * Convert JavaClass to CodeSymbol.
     */
    private CodeSymbol javaClassToCodeSymbol(JavaClass javaClass) {
        return CodeSymbol.builder()
            .symbolId(javaClass.getClassId())
            .name(javaClass.getSimpleName())
            .symbolType(mapTypeToSymbolType(javaClass.getType()))
            .repository(javaClass.getRepository())
            .module(javaClass.getModule())
            .packageName(javaClass.getPackageName())
            .className(javaClass.getFullyQualifiedName())
            .filePath(javaClass.getFilePath())
            .build();
    }
    
    private CodeSymbol.SymbolType mapTypeToSymbolType(String type) {
        if (type == null) return CodeSymbol.SymbolType.CLASS;
        
        return switch (type.toUpperCase()) {
            case "INTERFACE" -> CodeSymbol.SymbolType.INTERFACE;
            case "ENUM" -> CodeSymbol.SymbolType.ENUM;
            case "CLASS", "ABSTRACT_CLASS" -> CodeSymbol.SymbolType.CLASS;
            default -> CodeSymbol.SymbolType.CLASS;
        };
    }
}
