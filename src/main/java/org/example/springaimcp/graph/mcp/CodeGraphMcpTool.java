package org.example.springaimcp.graph.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.service.CodeGraphService;
import org.example.springaimcp.graph.service.Neo4jIngestionService;
import org.example.springaimcp.rag.ingestion.DocumentIngestionService;
import org.example.springaimcp.rag.ingestion.HybridIngestionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for Neo4j code graph operations.
 * Provides LLM-accessible tools for:
 * - Ingesting code into Neo4j
 * - Querying code relationships
 * - Finding dependencies and call chains
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeGraphMcpTool {

    private final HybridIngestionService hybridIngestionService;
    private final CodeGraphService codeGraphService;
    private final DocumentIngestionService documentIngestionService;
    private final Neo4jIngestionService neo4jIngestionService;

    @Tool(description = """
            Validate that Neo4j and Qdrant vector store are synchronized. \
            Checks for files that exist in one store but not the other. \
            Use this to detect inconsistencies in the dual indexing system.""")
    public String validateSync(String repository, String module) {
        log.info("Validating Neo4j ↔ Qdrant synchronization: repo={}, module={}", repository, module);

        try {
            // Get files from both stores
            List<String> neo4jFiles = hybridIngestionService.listNeo4jFiles(repository, module);
            List<String> qdrantFiles = hybridIngestionService.listQdrantFiles(repository, module);

            // Find differences
            Set<String> neo4jSet = new java.util.HashSet<>(neo4jFiles);
            Set<String> qdrantSet = new java.util.HashSet<>(qdrantFiles);

            Set<String> onlyInNeo4j = new java.util.HashSet<>(neo4jSet);
            onlyInNeo4j.removeAll(qdrantSet);

            Set<String> onlyInQdrant = new java.util.HashSet<>(qdrantSet);
            onlyInQdrant.removeAll(neo4jSet);

            // Format report
            StringBuilder report = new StringBuilder();
            report.append("╔═══════════════════════════════════════════════════════════════\n");
            report.append(String.format("║ Sync Validation Report\n"));
            report.append(String.format("║ Repository: %s\n", repository != null ? repository : "all"));
            report.append(String.format("║ Module: %s\n", module != null ? module : "all"));
            report.append("╠═══════════════════════════════════════════════════════════════\n");
            report.append(String.format("║ Neo4j files:  %d\n", neo4jFiles.size()));
            report.append(String.format("║ Qdrant files: %d\n", qdrantFiles.size()));
            report.append("╠═══════════════════════════════════════════════════════════════\n");

            if (onlyInNeo4j.isEmpty() && onlyInQdrant.isEmpty()) {
                report.append("║ ✓ SYNCHRONIZED - All files match between Neo4j and Qdrant\n");
                report.append("╚═══════════════════════════════════════════════════════════════\n");
                return report.toString();
            }

            report.append("║ ⚠ INCONSISTENT - Sync issues detected\n");
            report.append("╚═══════════════════════════════════════════════════════════════\n\n");

            if (!onlyInNeo4j.isEmpty()) {
                report.append(String.format("⚠ Files in Neo4j but NOT in Qdrant (%d):\n", onlyInNeo4j.size()));
                onlyInNeo4j.stream()
                        .sorted()
                        .limit(20)
                        .forEach(file -> report.append(String.format("  • %s\n", file)));
                if (onlyInNeo4j.size() > 20) {
                    report.append(String.format("  ... and %d more\n", onlyInNeo4j.size() - 20));
                }
                report.append("\n");
            }

            if (!onlyInQdrant.isEmpty()) {
                report.append(String.format("⚠ Files in Qdrant but NOT in Neo4j (%d):\n", onlyInQdrant.size()));
                onlyInQdrant.stream()
                        .sorted()
                        .limit(20)
                        .forEach(file -> report.append(String.format("  • %s\n", file)));
                if (onlyInQdrant.size() > 20) {
                    report.append(String.format("  ... and %d more\n", onlyInQdrant.size() - 20));
                }
                report.append("\n");
            }

            report.append("💡 Recommendation: Run reconciliation to fix inconsistencies.\n");

            return report.toString();

        } catch (Exception e) {
            log.error("Failed to validate sync", e);
            return String.format("✗ Sync validation error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Ingest a Java file into both Neo4j (graph structure) and the vector store (semantic content). \
            This creates nodes for classes, methods, fields and relationships like CALLS, EXTENDS, IMPLEMENTS. \
            Use this to index Java code for deep structural analysis.""")
    public String ingestJavaFile(String filePath) {
        log.info("Ingesting Java file into Neo4j and vector store: {}", filePath);

        try {
            Path path = Path.of(filePath).toAbsolutePath();
            HybridIngestionService.HybridIngestionResult result = hybridIngestionService.ingestJavaFile(path);

            if (result.success()) {
                return String.format(
                        "✓ File ingested successfully: %s\n" +
                        "Neo4j: %s\n" +
                        "Vector: parsed=%d docs, chunks=%d, indexed=%d",
                        filePath,
                        result.neo4jStats(),
                        result.vectorStats().parsedDocumentCount(),
                        result.vectorStats().chunkCount(),
                        result.vectorStats().indexedChunkCount()
                );
            } else {
                return String.format("✗ Ingestion failed: %s\nError: %s",
                        filePath, result.errorMessage());
            }
        } catch (Exception e) {
            log.error("Failed to ingest Java file", e);
            return String.format("✗ Ingestion error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Ingest an entire repository directory recursively into Neo4j and vector store. \
            Processes all Java files under the given root path. \
            Skips target/ and build/ directories automatically.""")
    public String ingestRepository(String rootPath) {
        log.info("Ingesting repository into Neo4j and vector store: {}", rootPath);

        try {
            Path path = Path.of(rootPath).toAbsolutePath();
            HybridIngestionService.HybridBatchStats stats = hybridIngestionService.ingestRepository(path);

            return String.format(
                    "✓ Repository ingestion complete: %s\n" +
                    "Files: %d total, %d successful, %d failed\n" +
                    "Neo4j: %d classes, %d methods\n" +
                    "Vector: %d chunks",
                    rootPath,
                    stats.getTotalFiles(),
                    stats.getSuccessfulFiles(),
                    stats.getFailedFiles(),
                    stats.getTotalClasses(),
                    stats.getTotalMethods(),
                    stats.getTotalChunks()
            );
        } catch (Exception e) {
            log.error("Failed to ingest repository", e);
            return String.format("✗ Repository ingestion error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find all classes that implement a given interface. \
            Returns class names, types, and Spring annotations. \
            Use this to discover implementations of interfaces.""")
    public String findImplementations(String interfaceClassId) {
        log.info("Finding implementations of: {}", interfaceClassId);

        try {
            List<JavaClass> implementations = codeGraphService.findImplementations(interfaceClassId);

            if (implementations.isEmpty()) {
                return String.format("No implementations found for interface: %s", interfaceClassId);
            }

            return implementations.stream()
                    .map(cls -> String.format(
                            "• %s (%s)\n  Repository: %s\n  Module: %s\n  Package: %s\n  File: %s:%d-%d\n  Annotations: %s",
                            cls.getSimpleName(),
                            cls.getType(),
                            cls.getRepository(),
                            cls.getModule(),
                            cls.getPackageName(),
                            cls.getFilePath(),
                            cls.getStartLine(),
                            cls.getEndLine(),
                            cls.getSpringAnnotations().isEmpty() ? "none" : String.join(", ", cls.getSpringAnnotations())
                    ))
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find implementations", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find all methods that call a given method (reverse call chain). \
            Returns caller method signatures, classes, and locations. \
            Use this for impact analysis: What will break if I change this method?""")
    public String findCallersOfMethod(String methodId) {
        log.info("Finding callers of method: {}", methodId);

        try {
            List<JavaMethod> callers = codeGraphService.findCallersOfMethod(methodId);
            String note = crossRepositoryNote(methodId);

            if (callers.isEmpty()) {
                return note + String.format("No callers found for method: %s", methodId);
            }

            return note + String.format("Found %d caller(s):\n\n", callers.size()) +
                    callers.stream()
                            .map(this::formatMethod)
                            .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find callers", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find all methods called by a given method (forward call chain). \
            Returns called method signatures, classes, and locations. \
            Use this to understand dependencies: What does this method depend on?""")
    public String findCalledByMethod(String methodId) {
        log.info("Finding methods called by: {}", methodId);

        try {
            List<JavaMethod> called = codeGraphService.findCalledByMethod(methodId);
            String note = crossRepositoryNote(methodId);

            if (called.isEmpty()) {
                return note + String.format("Method does not call any other methods: %s", methodId);
            }

            return note + String.format("Found %d called method(s):\n\n", called.size()) +
                    called.stream()
                            .map(this::formatMethod)
                            .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find called methods", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    /**
     * Builds a warning note when the supplied methodId's repository was wrong and the
     * method was only resolvable by searching across all repositories. This tells the
     * caller (human or LLM) the correct repository to use for any follow-up tool
     * calls, instead of silently continuing to search the wrong repository.
     */
    private String crossRepositoryNote(String methodId) {
        return codeGraphService.resolveMethod(methodId)
                .filter(CodeGraphService.ResolvedMethod::crossRepository)
                .map(resolved -> String.format(
                        "⚠ Note: repository '%s' has no such method. It was found instead in repository " +
                                "'%s' (methodId: %s). Use repository '%s' for any follow-up lookups.\n\n",
                        resolved.requestedRepository(),
                        resolved.method().getRepository(),
                        resolved.method().getMethodId(),
                        resolved.method().getRepository()))
                .orElse("");
    }


    @Tool(description = """
            Find all API endpoints (REST controllers) in the codebase. \
            Returns endpoint paths, HTTP methods, controller classes, and handler methods. \
            Use this to discover available APIs.""")
    public String findAllApiEndpoints() {
        log.info("Finding all API endpoints");

        try {
            List<JavaMethod> endpoints = codeGraphService.findAllApiEndpoints();

            if (endpoints.isEmpty()) {
                return "No API endpoints found in the codebase.";
            }

            return String.format("Found %d API endpoint(s):\n\n", endpoints.size()) +
                    endpoints.stream()
                            .map(method -> String.format(
                                    "• %s %s\n  Handler: %s.%s\n  File: %s:%d-%d\n  Annotations: %s",
                                    method.getHttpMethod(),
                                    method.getApiPath(),
                                    method.getClassName(),
                                    method.getName(),
                                    method.getFilePath(),
                                    method.getStartLine(),
                                    method.getEndLine(),
                                    String.join(", ", method.getSpringAnnotations())
                            ))
                            .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find API endpoints", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find all Spring @Service classes in the codebase. \
            Returns service class names, packages, and locations. \
            Use this to discover business logic services.""")
    public String findAllServices() {
        log.info("Finding all Spring services");

        try {
            List<JavaClass> services = codeGraphService.findAllServices();

            if (services.isEmpty()) {
                return "No @Service classes found in the codebase.";
            }

            return String.format("Found %d service(s):\n\n", services.size()) +
                    services.stream()
                            .map(this::formatClass)
                            .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find services", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find all Spring @Controller and @RestController classes in the codebase. \
            Returns controller class names, packages, and locations. \
            Use this to discover API controllers.""")
    public String findAllControllers() {
        log.info("Finding all Spring controllers");

        try {
            List<JavaClass> controllers = codeGraphService.findAllControllers();

            if (controllers.isEmpty()) {
                return "No @Controller or @RestController classes found in the codebase.";
            }

            return String.format("Found %d controller(s):\n\n", controllers.size()) +
                    controllers.stream()
                            .map(this::formatClass)
                            .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find controllers", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find all Spring @Repository classes in the codebase. \
            Returns repository class names, packages, and locations. \
            Use this to discover data access layer components.""")
    public String findAllRepositories() {
        log.info("Finding all Spring repositories");

        try {
            List<JavaClass> repositories = codeGraphService.findAllRepositories();

            if (repositories.isEmpty()) {
                return "No @Repository classes found in the codebase.";
            }

            return String.format("Found %d repository/repositories:\n\n", repositories.size()) +
                    repositories.stream()
                            .map(this::formatClass)
                            .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            log.error("Failed to find repositories", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find a class by its unique ID (format: class:repository:fully.qualified.Name). \
            Returns class details, methods, fields, relationships. \
            Use this to get complete information about a class.""")
    public String findClassById(String classId) {
        log.info("Finding class by ID: {}", classId);

        try {
            Optional<JavaClass> classOpt = codeGraphService.findClassById(classId);

            if (classOpt.isEmpty()) {
                return String.format("Class not found: %s", classId);
            }

            JavaClass cls = classOpt.get();
            return formatClassDetailed(cls);
        } catch (Exception e) {
            log.error("Failed to find class", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    @Tool(description = """
            Find a method by its unique ID (format: method:repository:fully.qualified.Class.methodName(params)). \
            Returns method details, parameters, return type, relationships. \
            Use this to get complete information about a method.""")
    public String findMethodById(String methodId) {
        log.info("Finding method by ID: {}", methodId);

        try {
            Optional<JavaMethod> methodOpt = codeGraphService.findMethodById(methodId);

            if (methodOpt.isEmpty()) {
                return String.format("Method not found: %s", methodId);
            }

            JavaMethod method = methodOpt.get();
            return formatMethodDetailed(method);
        } catch (Exception e) {
            log.error("Failed to find method", e);
            return String.format("✗ Error: %s", e.getMessage());
        }
    }

    // ── Formatting helpers ───────────────────────────────────────────────────

    private String formatClass(JavaClass cls) {
        return String.format(
                "• %s (%s)\n  Package: %s\n  Repository: %s\n  Module: %s\n  File: %s:%d-%d\n  Annotations: %s",
                cls.getSimpleName(),
                cls.getType(),
                cls.getPackageName(),
                cls.getRepository(),
                cls.getModule(),
                cls.getFilePath(),
                cls.getStartLine(),
                cls.getEndLine(),
                cls.getSpringAnnotations().isEmpty() ? "none" : String.join(", ", cls.getSpringAnnotations())
        );
    }

    private String formatClassDetailed(JavaClass cls) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Class: %s (%s)\n", cls.getSimpleName(), cls.getType()));
        sb.append(String.format("Fully Qualified Name: %s\n", cls.getFullyQualifiedName()));
        sb.append(String.format("Package: %s\n", cls.getPackageName()));
        sb.append(String.format("Repository: %s\n", cls.getRepository()));
        sb.append(String.format("Module: %s\n", cls.getModule()));
        sb.append(String.format("File: %s:%d-%d\n", cls.getFilePath(), cls.getStartLine(), cls.getEndLine()));
        sb.append(String.format("Branch: %s\n", cls.getBranch()));
        sb.append(String.format("Commit: %s\n", cls.getCommit()));

        if (!cls.getSpringAnnotations().isEmpty()) {
            sb.append(String.format("Annotations: %s\n", String.join(", ", cls.getSpringAnnotations())));
        }

        if (!cls.getMethods().isEmpty()) {
            sb.append(String.format("\nMethods (%d):\n", cls.getMethods().size()));
            cls.getMethods().forEach(method ->
                    sb.append(String.format("  • %s\n", method.getSignature())));
        }

        if (!cls.getFields().isEmpty()) {
            sb.append(String.format("\nFields (%d):\n", cls.getFields().size()));
            cls.getFields().forEach(field ->
                    sb.append(String.format("  • %s %s\n", field.getType(), field.getName())));
        }

        return sb.toString();
    }

    private String formatMethod(JavaMethod method) {
        return String.format(
                "• %s.%s\n  Signature: %s\n  Repository: %s\n  File: %s:%d-%d",
                method.getClassName(),
                method.getName(),
                method.getSignature(),
                method.getRepository(),
                method.getFilePath(),
                method.getStartLine(),
                method.getEndLine()
        );
    }

    private String formatMethodDetailed(JavaMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Method: %s\n", method.getName()));
        sb.append(String.format("Signature: %s\n", method.getSignature()));
        sb.append(String.format("Fully Qualified: %s\n", method.getFullyQualifiedSignature()));
        sb.append(String.format("Class: %s\n", method.getClassName()));
        sb.append(String.format("Package: %s\n", method.getPackageName()));
        sb.append(String.format("Repository: %s\n", method.getRepository()));
        sb.append(String.format("Module: %s\n", method.getModule()));
        sb.append(String.format("File: %s:%d-%d\n", method.getFilePath(), method.getStartLine(), method.getEndLine()));
        sb.append(String.format("Return Type: %s\n", method.getReturnType()));

        if (!method.getParameterTypes().isEmpty()) {
            sb.append(String.format("Parameters: %s\n", String.join(", ", method.getParameterTypes())));
        }

        if (!method.getSpringAnnotations().isEmpty()) {
            sb.append(String.format("Annotations: %s\n", String.join(", ", method.getSpringAnnotations())));
        }

        if (method.getHttpMethod() != null) {
            sb.append(String.format("API: %s %s\n", method.getHttpMethod(), method.getApiPath()));
        }

        return sb.toString();
    }

    @Tool(description = """
            Establish TESTED_BY relationships between test methods and production methods. \
            This analyzes test classes and creates relationships based on naming conventions \
            and method call analysis. Run this after ingesting a repository to populate test coverage data.
            
            Parameters:
            - repository: Repository name (required)
            - module: Module name (required)
            
            Returns: Number of TESTED_BY relationships created.""")
    public String establishTestRelationships(String repository, String module) {
        log.info("MCP: Establishing test relationships for repo={}, module={}", repository, module);
        
        try {
            int relationshipsCreated = neo4jIngestionService.establishTestRelationships(repository, module);
            
            return String.format("""
                    ╔═══════════════════════════════════════════════════════════════
                    ║ Test Relationships Established
                    ╠═══════════════════════════════════════════════════════════════
                    ║ Repository: %s
                    ║ Module: %s
                    ║ Relationships created: %d
                    ╚═══════════════════════════════════════════════════════════════
                     
                    ✓ TESTED_BY relationships have been established between test methods
                      and production methods based on naming conventions and call analysis.
                    """, repository, module, relationshipsCreated);
             
        } catch (Exception e) {
            log.error("Failed to establish test relationships", e);
            return String.format("❌ Failed to establish test relationships: %s", e.getMessage());
        }
    }
}
