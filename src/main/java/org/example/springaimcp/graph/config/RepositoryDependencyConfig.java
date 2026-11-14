package org.example.springaimcp.graph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for cross-repository dependencies.
 * Maps repository names to their Maven/library dependencies.
 * 
 * <p>Example configuration:
 * <pre>
 * code-intelligence:
 *   cross-repo-linking:
 *     enabled: true
 *     dependency-map:
 *       axis-orchestrator:
 *         - axis-utilities
 *         - axis-common
 *       axis-gateway:
 *         - axis-utilities
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "code-intelligence.cross-repo-linking")
@Data
public class RepositoryDependencyConfig {

    private static final List<String> DEFAULT_EXTERNAL_PACKAGE_PREFIXES = List.of(
            "java.",
            "javax.",
            "jakarta.",
            "org.springframework.",
            "org.slf4j.",
            "lombok."
    );

    /**
     * Simple (unqualified) type names that are known JDK/collection/generic types.
     * When type resolution fails to produce a fully-qualified name (e.g. missing
     * import resolution), these bare names would otherwise slip past the
     * package-prefix check above and generate permanent unresolvable stub nodes.
     */
    private static final Set<String> KNOWN_JDK_SIMPLE_NAMES = Set.of(
            "Object", "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "Number", "Void", "StringBuilder", "StringBuffer",
            "List", "Map", "Set", "Collection", "Iterable", "Iterator", "Optional", "OptionalInt",
            "OptionalLong", "OptionalDouble", "Stream", "IntStream", "LongStream", "DoubleStream",
            "Comparable", "Comparator", "Cloneable", "Serializable", "Runnable", "Callable", "Thread",
            "Exception", "RuntimeException", "Throwable", "Error",
            "var", "unknown"
    );
    
    /**
     * Enable/disable cross-repository call graph linking.
     */
    private boolean enabled = true;
    
    /**
     * Map of repository → list of dependency repositories.
     * When analyzing code in the key repository, method calls will be resolved
     * against methods in the dependency repositories.
     */
    private Map<String, List<String>> dependencyMap = new HashMap<>();

    /**
     * Package prefixes treated as external/library code and excluded from stub creation.
     */
    private List<String> externalPackagePrefixes = DEFAULT_EXTERNAL_PACKAGE_PREFIXES;
    
    /**
     * Get all repositories that the given repository depends on.
     */
    public List<String> getDependencies(String repository) {
        return dependencyMap.getOrDefault(repository, List.of());
    }
    
    /**
     * Check if a repository has any configured dependencies.
     */
    public boolean hasDependencies(String repository) {
        return dependencyMap.containsKey(repository) && 
               !dependencyMap.get(repository).isEmpty();
    }

    public boolean isExternalPackage(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return true;
        }
        String normalized = typeName.replace("[]", "");
        if (externalPackagePrefixes.stream().anyMatch(normalized::startsWith)) {
            return true;
        }
        // Bare (unqualified) type name: type resolution failed to attach a package,
        // so fall back to a known-name check to avoid creating permanent stub nodes
        // for common JDK/collection types like List, Map, Optional, var, etc.
        return !normalized.contains(".") && KNOWN_JDK_SIMPLE_NAMES.contains(normalized);
    }
}
