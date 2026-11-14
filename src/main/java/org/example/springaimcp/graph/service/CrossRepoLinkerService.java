package org.example.springaimcp.graph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.config.RepositoryDependencyConfig;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.model.JavaField;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for establishing cross-repository relationships following strict AST-based resolution.
 * 
 * <p>Architecture (based on temp3 instructions):
 * <ol>
 *   <li><b>Phase 1 - INJECTS:</b> Identify dependency injection at class level (@Autowired, constructor, setter)</li>
 *   <li><b>Phase 2 - CALLS:</b> Identify actual method invocations using AST analysis</li>
 *   <li><b>Phase 3 - Resolution:</b> Resolve cross-repository method calls using FQN + signature</li>
 * </ol>
 * 
 * <p>Key Principles (from temp3):
 * <ul>
 *   <li>Injection identifies the dependency (INJECTS relationship)</li>
 *   <li>AST usage identifies the actual invocation (CALLS relationship)</li>
 *   <li>Do NOT create CALLS for every method in injected class - only actual invocations</li>
 *   <li>Support interface-based injection</li>
 *   <li>Use repository-aware IDs for cross-repo resolution</li>
 *   <li>Idempotent operations (avoid duplicates)</li>
 * </ul>
 * 
 * <p>Usage:
 * <pre>
 * // Recommended flow:
 * // 1. Ingest all repositories
 * // 2. Run cross-repo linking:
 * linkerService.linkCrossRepoRelationships("axis-orchestrator", null);
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossRepoLinkerService {
    
    private static final int BATCH_SIZE = 50; // Process classes in batches to avoid memory issues
    
    private final JavaMethodRepository methodRepository;
    private final JavaClassRepository classRepository;
    private final RepositoryDependencyConfig dependencyConfig;
    private final org.springframework.data.neo4j.core.Neo4jClient neo4jClient;
    
    // Map of repository names to their root paths (set during ingestion)
    private final Map<String, String> repositoryRootPaths = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Register a repository root path for file resolution during linking.
     * Should be called after repository ingestion.
     * 
     * @param repository The repository name
     * @param rootPath The absolute path to the repository root directory
     */
    public void registerRepositoryPath(String repository, String rootPath) {
        repositoryRootPaths.put(repository, rootPath);
        log.info("Registered repository path: {} → {}", repository, rootPath);
    }
    
    /**
     * Auto-discover candidate target repositories for cross-repo linking by inspecting
     * every distinct repository already present in the graph, rather than requiring a
     * statically configured dependency map. All other ingested repositories are treated
     * as potential dependencies; unresolvable/external references are naturally filtered
     * out later by {@link RepositoryDependencyConfig#isExternalPackage(String)}.
     */
    private List<String> discoverTargetRepositories(String sourceRepository) {
        List<String> allRepositories = classRepository.findAllDistinctRepositories();
        List<String> discovered = allRepositories.stream()
                .filter(repo -> repo != null && !repo.isBlank() && !repo.equals(sourceRepository))
                .distinct()
                .toList();
        log.info("Auto-discovered {} candidate repositories for source '{}': {}",
                discovered.size(), sourceRepository, discovered);
        return discovered;
    }
    
    /**
     * Link cross-repository relationships following temp3 instructions.
     * 
     * Three-phase approach:
     * 1. Build INJECTS relationships (class-level dependency injection)
     * 2. Identify actual method invocations using AST analysis
     * 3. Create CALLS relationships only for actual invocations
     * 
     * @param sourceRepository The repository containing the calling code
     * @param targetRepository The dependency repository (null = all configured dependencies)
     * @return Statistics about relationships created
     */
    /**
     * Note: intentionally NOT wrapped in a single {@code @Transactional}, matching
     * {@link RelationshipReconciliationService}'s design. Spring Data Neo4j repository
     * methods and {@code Neo4jClient} queries each run in their own implicit
     * transaction; wrapping the whole multi-phase, multi-batch, multi-target-repo scan
     * in one outer transaction would keep a single transaction open for the entire
     * (potentially very large, auto-discovered multi-repo) operation and risk
     * exhausting memory/locks, exactly the failure mode batching is meant to avoid.
     * Every write here is idempotent ({@code MERGE}), so partial progress from a
     * failure partway through is safe to simply re-run.
     */
    public LinkingStats linkCrossRepoRelationships(String sourceRepository, String targetRepository) {
        log.info("=== Starting cross-repo linking: source={}, target={} ===", 
                sourceRepository, targetRepository != null ? targetRepository : "all");
        
        if (!dependencyConfig.isEnabled()) {
            log.info("Cross-repo linking is disabled in configuration");
            return new LinkingStats(0, 0, 0, 0, "Disabled in configuration");
        }
        
        // Get target repositories: honor an explicit target or a manually configured
        // dependency map when present, otherwise auto-discover every other repository
        // already ingested into the graph so no static configuration is required.
        List<String> targetRepos;
        if (targetRepository != null) {
            targetRepos = List.of(targetRepository);
        } else if (dependencyConfig.hasDependencies(sourceRepository)) {
            targetRepos = dependencyConfig.getDependencies(sourceRepository);
        } else {
            targetRepos = discoverTargetRepositories(sourceRepository);
        }
        
        if (targetRepos.isEmpty()) {
            String message = "No other repositories found to link against for repository: " + sourceRepository;
            log.warn(message);
            return new LinkingStats(0, 0, 0, 0, message);
        }
        
        log.info("Target repositories: {}", targetRepos);
        
        // Parsed-file cache scoped to this single linking run only (one source repository).
        // Bounded by the number of distinct source files touched while scanning this repo's
        // classes/methods, and released for GC as soon as this method returns — it does NOT
        // accumulate across repositories when linkAllRepositories() calls this in a loop.
        Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache = new HashMap<>();
        
        // Phase 1: Establish INJECTS relationships at class level
        log.info("=== PHASE 1: Building INJECTS relationships ===");
        int injectsCreated = buildInjectsRelationships(sourceRepository, targetRepos, parseCache);
        log.info("Phase 1 complete: {} INJECTS relationships created", injectsCreated);
        
        // Phase 2 & 3: Analyze method invocations and create CALLS relationships
        log.info("=== PHASE 2 & 3: Analyzing method invocations and creating CALLS ===");
        CallsResult callsResult = buildCallsRelationships(sourceRepository, targetRepos, parseCache);
        log.info("Phase 2 & 3 complete: {} methods scanned, {} CALLS relationships created", 
                callsResult.methodsScanned, callsResult.callsCreated);
        
        LinkingStats stats = new LinkingStats(
                callsResult.methodsScanned, 
                injectsCreated, 
                callsResult.callsCreated, 
                targetRepos.size(), 
                null);
        log.info("=== Cross-repo linking complete: {} ===", stats);
        return stats;
    }
    
    /**
     * Phase 1: Build INJECTS relationships at class level with batch processing.
     * Detects Spring dependency injection through:
     * - @Autowired fields
     * - Constructor injection (with @Autowired or implicit single constructor)
     * - @Resource fields
     */
    private int buildInjectsRelationships(String sourceRepository, List<String> targetRepos,
                                           Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache) {
        int relationshipsCreated = 0;
        
        // Preference-ordered candidate repository list: source repo first (intra-repo
        // dependencies preferred), then all target repos. Resolution below queries this
        // whole list in a single Neo4j round trip rather than looping repo-by-repo.
        List<String> candidateRepos = new ArrayList<>();
        candidateRepos.add(sourceRepository);
        targetRepos.stream().filter(r -> !candidateRepos.contains(r)).forEach(candidateRepos::add);
        
        // Count total classes for progress tracking
        long totalClasses = classRepository.countByRepository(sourceRepository);
        log.info("Found {} classes in source repository for INJECTS analysis", totalClasses);
        
        if (totalClasses == 0) {
            log.warn("No classes found in repository: {}", sourceRepository);
            return 0;
        }
        
        // Process classes in batches
        int batchNumber = 0;
        int processedClasses = 0;
        
        while (processedClasses < totalClasses) {
            int skip = batchNumber * BATCH_SIZE;
            List<JavaClass> batchClasses = classRepository.findByRepositoryPaginated(
                    sourceRepository, skip, BATCH_SIZE);
            
            if (batchClasses.isEmpty()) {
                break; // No more classes to process
            }
            
            log.info("INJECTS: Processing batch {}: classes {}-{} of {}",
                    batchNumber + 1, skip + 1, skip + batchClasses.size(), totalClasses);
            
            for (JavaClass sourceClass : batchClasses) {
                processedClasses++;
                
                try {
                    // Reload the class to ensure all relationships are loaded (especially HAS_FIELD)
                    Optional<JavaClass> reloadedOpt = classRepository.findByClassId(sourceClass.getClassId());
                    if (reloadedOpt.isEmpty()) {
                        log.warn("Could not reload class: {}", sourceClass.getClassId());
                        continue;
                    }
                    
                    sourceClass = reloadedOpt.get();
                    
                    // Collect all injected types in this class
                    Set<String> injectedTypes = new HashSet<>();
                    
                    // 1. Check @Autowired and @Resource fields
                    Set<JavaField> fields = sourceClass.getFields();
                    
                    if (fields == null) {
                        log.warn("Class {} has NULL fields collection!", sourceClass.getFullyQualifiedName());
                        continue;
                    }
                    
                    if (fields.isEmpty()) {
                        log.info("Class {} has 0 fields (empty fields collection)", sourceClass.getFullyQualifiedName());
                    } else {
                        log.info("Checking class: {} with {} fields", sourceClass.getFullyQualifiedName(), fields.size());
                    }
                    
                    for (JavaField field : fields) {
                        log.debug("  Field: {} type: {} annotations: {}", 
                                field.getName(), field.getType(), field.getSpringAnnotations());
                        
                        if (isInjectedField(field)) {
                            String fieldType = field.getType();
                            log.info("  -> Injected field detected: {} of type {}", field.getName(), fieldType);
                            
                            if (fieldType != null && !fieldType.isEmpty()) {
                                if (isPrimitiveOrStandardLib(fieldType)) {
                                    log.trace("  -> Filtered out (primitive/stdlib): {}", fieldType);
                                } else {
                                    injectedTypes.add(fieldType);
                                    log.info("  -> Added injected type: {} from field {} in class {}",
                                            fieldType, field.getName(), sourceClass.getSimpleName());
                                }
                            } else {
                                log.warn("  -> Field {} has null or empty type in class {}", 
                                        field.getName(), sourceClass.getFullyQualifiedName());
                            }
                        }
                    }
                    
                    // 2. Check constructor injection
                    Set<String> constructorInjectedTypes = extractConstructorInjectedTypes(sourceClass, parseCache);
                    if (!constructorInjectedTypes.isEmpty()) {
                        log.info("Found {} constructor-injected types in {}", 
                                constructorInjectedTypes.size(), sourceClass.getSimpleName());
                    }
                    injectedTypes.addAll(constructorInjectedTypes);
                    
                    if (injectedTypes.isEmpty()) {
                        log.trace("No injected types found in class: {}", sourceClass.getFullyQualifiedName());
                    } else {
                        log.info("Total injected types in {}: {}", sourceClass.getSimpleName(), injectedTypes);
                    }
                    
                    // 3. For each injected type, create INJECTS relationship. Resolution queries
                    // all candidate repositories (source + targets) in one round trip instead of
                    // looping repo-by-repo, which previously became O(repositories) queries per
                    // injected type once target repositories were auto-discovered.
                    for (String injectedTypeName : injectedTypes) {
                        Optional<JavaClass> targetClass = findClassAcrossRepositories(injectedTypeName, candidateRepos);
                        
                        if (targetClass.isPresent()) {
                            JavaClass resolvedClass = targetClass.get();
                            String actualRepo = resolvedClass.getRepository();
                            boolean isSameRepo = actualRepo.equals(sourceRepository);
                            
                            try {
                                // Use Neo4jClient for direct Cypher execution with explicit write
                                String cypher = "MATCH (source:Class {classId: $sourceClassId}), " +
                                               "(target:Class {classId: $targetClassId}) " +
                                               "MERGE (source)-[r:INJECTS]->(target) " +
                                               "RETURN count(r) as count";
                                
                                neo4jClient.query(cypher)
                                        .bind(sourceClass.getClassId()).to("sourceClassId")
                                        .bind(resolvedClass.getClassId()).to("targetClassId")
                                        .fetchAs(Long.class)
                                        .one();
                                
                                relationshipsCreated++;
                                log.debug("INJECTS ({}): {} ──> {} [FQN: {}] (repo: {})",
                                        isSameRepo ? "same-repo" : "cross-repo",
                                        sourceClass.getSimpleName(),
                                        resolvedClass.getSimpleName(),
                                        resolvedClass.getFullyQualifiedName(),
                                        actualRepo);
                            } catch (Exception e) {
                                log.error("Failed to create INJECTS relationship {} -> {}: {}",
                                        sourceClass.getClassId(), resolvedClass.getClassId(), e.getMessage(), e);
                            }
                        } else {
                            log.debug("Could not resolve injected type: '{}' for class {}. Searched: {}",
                                    injectedTypeName, sourceClass.getSimpleName(), candidateRepos);
                        }
                    }
                    
                } catch (Exception e) {
                    log.error("Error processing class {} for INJECTS: {}",
                            sourceClass.getFullyQualifiedName(), e.getMessage(), e);
                }
            }
            
            log.info("INJECTS: Batch {} complete: {}/{} classes processed, {} relationships created so far",
                    batchNumber + 1, processedClasses, totalClasses, relationshipsCreated);
            
            batchNumber++;
        }
        
        return relationshipsCreated;
    }
    
    /**
     * Phases 2 & 3: Analyze method invocations and create CALLS relationships with batch processing.
     * Only creates CALLS when an actual method invocation exists in the AST.
     */
    private CallsResult buildCallsRelationships(String sourceRepository, List<String> targetRepos,
                                                 Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache) {
        int methodsScanned = 0;
        int callsCreated = 0;
        
        // Get repository root path for parsing
        String rootPath = repositoryRootPaths.get(sourceRepository);
        if (rootPath == null) {
            log.warn("Repository root path not registered for: {}. Cannot analyze method calls.", sourceRepository);
            return new CallsResult(0, 0);
        }
        
        // Count total classes for progress tracking
        long totalClasses = classRepository.countByRepository(sourceRepository);

        if (totalClasses == 0) {
            log.warn("No classes found in repository: {}", sourceRepository);
            return new CallsResult(0, 0);
        }
        
        // Process classes in batches
        int batchNumber = 0;
        int processedClasses = 0;
        
        while (processedClasses < totalClasses) {
            int skip = batchNumber * BATCH_SIZE;
            List<JavaClass> batchClasses = classRepository.findByRepositoryPaginated(
                    sourceRepository, skip, BATCH_SIZE);
            
            if (batchClasses.isEmpty()) {
                break; // No more classes to process
            }
            
            log.info("CALLS: Processing batch {}: classes {}-{} of {}",
                    batchNumber + 1, skip + 1, skip + batchClasses.size(), totalClasses);
            
            for (JavaClass sourceClass : batchClasses) {
                processedClasses++;
                
                try {
                    // Build a map of field/variable names to their injected types
                    Map<String, InjectedDependency> injectedFieldMap = buildInjectedFieldMap(sourceClass, parseCache);
                    
                    if (injectedFieldMap.isEmpty()) {
                        log.trace("No injected dependencies in class: {}", sourceClass.getFullyQualifiedName());
                        continue;
                    }
                    
                    // For each method in the class
                    for (JavaMethod method : sourceClass.getMethods()) {
                        methodsScanned++;
                        
                        // Parse method body and find actual method invocations
                        Set<MethodInvocation> invocations = extractMethodInvocations(method, injectedFieldMap, parseCache);
                        
                        // For each invocation, resolve against ALL target repositories in a single
                        // query (see resolveMethodInvocation) instead of looping repo-by-repo, which
                        // previously became O(repositories) queries per invocation once target
                        // repositories were auto-discovered (potentially every ingested repository).
                        for (MethodInvocation invocation : invocations) {
                            Optional<JavaMethod> targetMethod = resolveMethodInvocation(invocation, targetRepos);
                            
                            if (targetMethod.isPresent()) {
                                neo4jClient.query("""
                                        MATCH (source:Method {methodId: $sourceMethodId}), (target:Method {methodId: $targetMethodId})
                                        MERGE (source)-[:CALLS]->(target)
                                        """)
                                        .bind(method.getMethodId()).to("sourceMethodId")
                                        .bind(targetMethod.get().getMethodId()).to("targetMethodId")
                                        .run();
                                callsCreated++;

                                log.debug("CALLS: {}::{}() ──> {}::{}() [via field: {}]",
                                        method.getClassName(),
                                        method.getName(),
                                        targetMethod.get().getClassName(),
                                        targetMethod.get().getName(),
                                        invocation.receiverFieldName);
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    log.error("Error processing class {} for CALLS: {}",
                            sourceClass.getFullyQualifiedName(), e.getMessage(), e);
                }
            }
            
            log.info("CALLS: Batch {} complete: {}/{} classes processed, {} methods scanned, {} relationships created so far",
                    batchNumber + 1, processedClasses, totalClasses, methodsScanned, callsCreated);
            
            batchNumber++;
        }
        
        return new CallsResult(methodsScanned, callsCreated);
    }
    
    /**
     * Check if a field is injected (has injection annotations or is a class type).
     * A field is considered injected if:
     * 1. It has explicit injection annotations (@Autowired, @Inject, @Resource), OR
     * 2. It's a non-primitive, non-standard-lib type (likely constructor-injected)
     */
    private boolean isInjectedField(JavaField field) {
        Set<String> annotations = field.getSpringAnnotations();
        
        // Check for explicit injection annotations
        boolean hasInjectionAnnotation = annotations.contains("Autowired") ||
                                         annotations.contains("Inject") ||
                                         annotations.contains("Resource");
        
        if (hasInjectionAnnotation) {
            log.trace("Field {} has injection annotation: {}", field.getName(), annotations);
            return true;
        }
        
        // For fields without annotations, check if the type is a Class (not primitive/stdlib)
        // This catches constructor-injected fields which don't have @Autowired on the field itself
        String fieldType = field.getType();
        if (fieldType != null && !fieldType.isEmpty()) {
            // If it's not a primitive or standard library class, it's likely injected
            boolean isCustomClass = !isPrimitiveOrStandardLib(fieldType);
            
            if (isCustomClass) {
                log.info("Field {} of type {} is a custom class (likely injected via constructor)", 
                        field.getName(), fieldType);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Extract constructor-injected types by parsing the source file.
     * Per temp3: Use constructor parameter's declared type, not variable name.
     */
    private Set<String> extractConstructorInjectedTypes(
            JavaClass javaClass,
            Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache) {
        Set<String> types = new HashSet<>();
        
        Optional<com.github.javaparser.ast.CompilationUnit> cuOpt =
                loadCompilationUnit(javaClass.getRepository(), javaClass.getFilePath(), parseCache);
        if (cuOpt.isEmpty()) {
            return types;
        }
        com.github.javaparser.ast.CompilationUnit cu = cuOpt.get();
        
        try {
            // Find constructors with injection annotations or single constructor (implicit injection)
            List<com.github.javaparser.ast.body.ConstructorDeclaration> constructors = 
                    cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class);
            
            for (com.github.javaparser.ast.body.ConstructorDeclaration constructor : constructors) {
                boolean hasInjectionAnnotation = constructor.getAnnotations().stream()
                        .anyMatch(ann -> ann.getNameAsString().equals("Autowired") ||
                                       ann.getNameAsString().equals("Inject"));
                
                // Per temp3: Use constructor parameter's declared type, not variable name
                if (hasInjectionAnnotation || constructors.size() == 1) {
                    constructor.getParameters().forEach(param -> {
                        String paramType = param.getType().asString();
                        if (!isPrimitiveOrStandardLib(paramType)) {
                            types.add(paramType);
                            log.trace("Found constructor-injected type: {} in class {}",
                                    paramType, javaClass.getFullyQualifiedName());
                        }
                    });
                }
            }
            
        } catch (Exception e) {
            log.error("Could not analyze constructor injection for class {}: {}",
                    javaClass.getFullyQualifiedName(), e.getMessage());
        }
        
        return types;
    }
    
    /**
     * Build a map of field/variable names to their injected types for a class.
     */
    private Map<String, InjectedDependency> buildInjectedFieldMap(
            JavaClass javaClass,
            Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache) {
        Map<String, InjectedDependency> fieldMap = new HashMap<>();
        
        // 1. Add @Autowired/@Resource fields
        for (JavaField field : javaClass.getFields()) {
            if (isInjectedField(field)) {
                fieldMap.put(field.getName(), new InjectedDependency(
                        field.getName(),
                        field.getType(),
                        "FIELD"));
            }
        }
        
        // 2. Add constructor-injected fields, reusing the cached parsed AST for this file
        loadCompilationUnit(javaClass.getRepository(), javaClass.getFilePath(), parseCache).ifPresent(cu -> {
            try {
                List<com.github.javaparser.ast.body.ConstructorDeclaration> constructors =
                        cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class);
                
                constructors.forEach(constructor -> {
                    boolean hasInjectionAnnotation = constructor.getAnnotations().stream()
                            .anyMatch(ann -> ann.getNameAsString().equals("Autowired") ||
                                           ann.getNameAsString().equals("Inject"));
                    
                    if (hasInjectionAnnotation || constructors.size() == 1) {
                        constructor.getParameters().forEach(param -> {
                            String paramName = param.getNameAsString();
                            String paramType = param.getType().asString();
                            
                            // Find field assignment: this.fieldName = paramName;
                            constructor.getBody().findAll(com.github.javaparser.ast.expr.AssignExpr.class).forEach(assign -> {
                                if (assign.getValue().isNameExpr() &&
                                    assign.getValue().asNameExpr().getNameAsString().equals(paramName)) {
                                    
                                    String fieldName = assign.getTarget().toString();
                                    if (fieldName.startsWith("this.")) {
                                        fieldName = fieldName.substring(5);
                                    }
                                    
                                    fieldMap.put(fieldName, new InjectedDependency(
                                            fieldName,
                                            paramType,
                                            "CONSTRUCTOR"));
                                }
                            });
                        });
                    }
                });
            } catch (Exception e) {
                log.debug("Could not analyze constructor injection map for class {}: {}",
                        javaClass.getFullyQualifiedName(), e.getMessage());
            }
        });
        
        return fieldMap;
    }
    
    /**
     * Extract actual method invocations from a method body using AST.
     * Per temp3: Only report actual invocations, not potential ones.
     */
    private Set<MethodInvocation> extractMethodInvocations(
            JavaMethod method,
            Map<String, InjectedDependency> injectedFieldMap,
            Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache) {
        
        Set<MethodInvocation> invocations = new HashSet<>();
        
        if (method.getFilePath() == null || method.getClassName() == null) {
            return invocations;
        }
        
        Optional<com.github.javaparser.ast.CompilationUnit> cuOpt =
                loadCompilationUnit(method.getRepository(), method.getFilePath(), parseCache);
        if (cuOpt.isEmpty()) {
            return invocations;
        }
        
        try {
            com.github.javaparser.ast.CompilationUnit cu = cuOpt.get();
            
            // Find the method in AST
            cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                    .filter(md -> md.getNameAsString().equals(method.getName()) &&
                                  md.getRange().isPresent() &&
                                  md.getRange().get().begin.line == method.getStartLine())
                    .findFirst()
                    .ifPresent(methodDecl -> {
                        if (methodDecl.getBody().isPresent()) {
                            // Find all method call expressions
                            methodDecl.getBody().get()
                                    .findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)
                                    .forEach(callExpr -> {
                                        // Per temp3: Use AST to identify receiver, called method, arguments
                                        if (callExpr.getScope().isPresent()) {
                                            String scope = callExpr.getScope().get().toString();
                                            
                                            // Remove "this." prefix
                                            if (scope.startsWith("this.")) {
                                                scope = scope.substring(5);
                                            }
                                            
                                            // Check if scope matches an injected field
                                            if (injectedFieldMap.containsKey(scope)) {
                                                InjectedDependency dep = injectedFieldMap.get(scope);
                                                String calledMethodName = callExpr.getNameAsString();
                                                
                                                // Extract parameter types for overload resolution
                                                List<String> paramTypes = extractParameterTypes(callExpr);
                                                
                                                invocations.add(new MethodInvocation(
                                                        scope,                    // receiverFieldName
                                                        dep.typeName,            // receiverType
                                                        calledMethodName,        // methodName
                                                        paramTypes,              // parameterTypes
                                                        callExpr.getArguments().size()  // paramCount
                                                ));
                                                
                                                log.trace("Found invocation: {}.{}({}) on injected field type {}",
                                                        scope, calledMethodName, paramTypes, dep.typeName);
                                            }
                                        }
                                    });
                        }
                    });
            
        } catch (Exception e) {
            log.info("Could not parse method invocations for {}::{} - {}",
                    method.getClassName(), method.getName(), e.getMessage());
        }
        
        return invocations;
    }
    
    /**
     * Extract parameter types from a method call expression for overload resolution.
     */
    private List<String> extractParameterTypes(com.github.javaparser.ast.expr.MethodCallExpr callExpr) {
        List<String> types = new ArrayList<>();
        
        try {
            // Try to resolve parameter types
            var resolved = callExpr.resolve();
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                types.add(resolved.getParam(i).describeType());
            }
        } catch (Exception e) {
            // If resolution fails, use best-effort type inference
            callExpr.getArguments().forEach(arg -> {
                types.add(arg.toString()); // Fallback to string representation
            });
        }
        
        return types;
    }
    
    /**
     * Resolve a method invocation to an actual target method, searching every candidate
     * target repository in a single Neo4j round trip (rather than looping repo-by-repo,
     * which previously became O(repositories) queries per invocation once target
     * repositories were auto-discovered). Overload resolution prefers an exact
     * parameter-type match when types are available, falling back to arity-only
     * matching (picking the first candidate) when they aren't.
     */
    private Optional<JavaMethod> resolveMethodInvocation(MethodInvocation invocation, List<String> targetRepositories) {
        if (targetRepositories.isEmpty()) {
            return Optional.empty();
        }
        
        // Try to find method by: class name + method name, across all target repos at once
        List<JavaMethod> candidates = methodRepository.findByClassNameAndNameInRepositories(
                targetRepositories, invocation.receiverType, invocation.methodName);
        
        Optional<JavaMethod> resolved = pickBestOverload(candidates, invocation, targetRepositories);
        if (resolved.isPresent()) {
            return resolved;
        }
        
        // Fallback: try simple class name (suffix) matching, e.g. receiverType resolved
        // to a bare "SomeClass" instead of its fully-qualified form
        String simpleClassName = invocation.receiverType.contains(".")
                ? invocation.receiverType.substring(invocation.receiverType.lastIndexOf('.') + 1)
                : invocation.receiverType;
        
        List<JavaMethod> fallbackCandidates = methodRepository.findByNameAndClassNameEndingInRepositories(
                targetRepositories, invocation.methodName, simpleClassName);
        
        return pickBestOverload(fallbackCandidates, invocation, targetRepositories);
    }
    
    /**
     * Pick the best-matching overload from a set of candidate methods: filters by
     * parameter arity first, then narrows to an exact parameter-type match when the
     * invocation's parameter types are known (handles overloaded methods sharing the
     * same arity), then prefers a candidate from the earliest-listed repository.
     */
    private Optional<JavaMethod> pickBestOverload(List<JavaMethod> candidates, MethodInvocation invocation,
                                                   List<String> repositoryPreferenceOrder) {
        List<JavaMethod> arityMatches = candidates.stream()
                .filter(m -> m.getParameterTypes() != null && m.getParameterTypes().size() == invocation.paramCount)
                .toList();
        
        if (arityMatches.isEmpty()) {
            return Optional.empty();
        }
        
        if (!invocation.parameterTypes.isEmpty()) {
            List<JavaMethod> typeMatches = arityMatches.stream()
                    .filter(m -> parameterTypesMatch(m.getParameterTypes(), invocation.parameterTypes))
                    .toList();
            if (!typeMatches.isEmpty()) {
                arityMatches = typeMatches;
            }
        }
        
        for (String repo : repositoryPreferenceOrder) {
            for (JavaMethod candidate : arityMatches) {
                if (repo.equals(candidate.getRepository())) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.of(arityMatches.get(0));
    }
    
    /**
     * Compares declared parameter types against an invocation's inferred parameter
     * types by simple (unqualified) name, since resolved/declared types may differ in
     * qualification (e.g. "String" vs "java.lang.String").
     */
    private boolean parameterTypesMatch(List<String> declaredTypes, List<String> invokedTypes) {
        if (declaredTypes.size() != invokedTypes.size()) {
            return false;
        }
        for (int i = 0; i < declaredTypes.size(); i++) {
            if (!simpleTypeName(declaredTypes.get(i)).equalsIgnoreCase(simpleTypeName(invokedTypes.get(i)))) {
                return false;
            }
        }
        return true;
    }
    
    private String simpleTypeName(String type) {
        if (type == null) {
            return "";
        }
        String normalized = type.replace("[]", "").trim();
        int idx = normalized.lastIndexOf('.');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }
    
    /**
     * Find a class by fully qualified name or simple name across a preference-ordered
     * list of candidate repositories, resolving all of them in a single query per
     * strategy instead of looping repo-by-repo (which previously became O(repositories)
     * queries per injected type once target repositories were auto-discovered).
     */
    private Optional<JavaClass> findClassAcrossRepositories(String typeName, List<String> candidateRepositories) {
        if (candidateRepositories.isEmpty()) {
            return Optional.empty();
        }
        
        // Strategy 1: exact FQN match, if the type name is qualified
        if (typeName.contains(".")) {
            List<JavaClass> exactMatches = classRepository.findByFullyQualifiedNameInRepositories(
                    typeName, candidateRepositories);
            Optional<JavaClass> match = pickPreferredClass(exactMatches, candidateRepositories);
            if (match.isPresent()) {
                return match;
            }
        }
        
        // Strategy 2: simple name match (handles both "SomeClass" and "com.axis.SomeClass")
        String simpleName = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
        List<JavaClass> simpleNameMatches = classRepository.findBySimpleNameInRepositories(
                candidateRepositories, simpleName);
        Optional<JavaClass> match = pickPreferredClass(simpleNameMatches, candidateRepositories);
        if (match.isPresent()) {
            return match;
        }
        
        // Strategy 3: for unqualified names only, fall back to a global search in case
        // the dependency comes from a transitive/unlisted repository
        if (!typeName.contains(".")) {
            List<JavaClass> globalMatches = classRepository.searchByName(simpleName, 10);
            if (!globalMatches.isEmpty()) {
                Optional<JavaClass> preferred = pickPreferredClass(globalMatches, candidateRepositories);
                if (preferred.isPresent()) {
                    return preferred;
                }
                JavaClass anyMatch = globalMatches.get(0);
                log.debug("Type '{}' not found in candidate repositories {}, but found in '{}' (FQN: {}); " +
                        "this may be a transitive dependency.",
                        simpleName, candidateRepositories, anyMatch.getRepository(), anyMatch.getFullyQualifiedName());
                return Optional.of(anyMatch);
            }
        }
        
        return Optional.empty();
    }
    
    private Optional<JavaClass> pickPreferredClass(List<JavaClass> candidates, List<String> repositoryPreferenceOrder) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        for (String repo : repositoryPreferenceOrder) {
            for (JavaClass candidate : candidates) {
                if (repo.equals(candidate.getRepository())) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.of(candidates.get(0));
    }
    
    /**
     * Check if a type is a primitive or standard library class (should not create INJECTS for these).
     * Returns true if the type is:
     * - A primitive type
     * - Part of java.* or javax.* packages
     * - Common Java collections or utilities
     * - Spring framework internal classes
     */
    private boolean isPrimitiveOrStandardLib(String type) {
        if (type == null || type.isEmpty()) {
            return true;
        }
        
        // Primitives
        if (type.matches("(int|long|short|byte|float|double|boolean|char|void)")) {
            return true;
        }
        
        // Standard Java/javax packages
        if (type.startsWith("java.") || type.startsWith("javax.")) {
            return true;
        }
        
        // Common Spring framework internal classes (not business logic)
        if (type.startsWith("org.springframework.context.ApplicationContext") ||
            type.startsWith("org.springframework.core.env.Environment") ||
            type.equals("Environment") ||
            type.equals("ApplicationContext")) {
            return true;
        }
        
        // Common simple types (without package)
        if (type.equals("String") || 
            type.equals("Object") ||
            type.equals("List") ||
            type.equals("Set") ||
            type.equals("Map") ||
            type.equals("Collection") ||
            type.equals("Optional")) {
            return true;
        }
        
        // Generic types (e.g., List<String>, Map<K,V>)
        if (type.contains("<") && type.contains(">")) {
            // Extract the base type
            String baseType = type.substring(0, type.indexOf("<"));
            return isPrimitiveOrStandardLib(baseType);
        }
        
        return false;
    }
    
    /**
     * Load and cache a parsed {@link com.github.javaparser.ast.CompilationUnit} for a
     * repository-relative file path, resolving the absolute path from the registered
     * repository root (see {@link #registerRepositoryPath}) instead of shelling out to
     * the {@code find} command per lookup. Results (including failures) are cached per
     * call to avoid re-parsing the same file multiple times across the INJECTS and
     * CALLS phases, and across the multiple methods of a single class.
     */
    private Optional<com.github.javaparser.ast.CompilationUnit> loadCompilationUnit(
            String repository, String relativeFilePath,
            Map<String, Optional<com.github.javaparser.ast.CompilationUnit>> parseCache) {
        if (relativeFilePath == null || relativeFilePath.isBlank()) {
            return Optional.empty();
        }
        String rootPath = repositoryRootPaths.get(repository);
        if (rootPath == null) {
            return Optional.empty();
        }
        
        String cacheKey = repository + "::" + relativeFilePath;
        return parseCache.computeIfAbsent(cacheKey, key -> {
            try {
                java.io.File sourceFile = new java.io.File(rootPath, relativeFilePath);
                if (!sourceFile.exists()) {
                    log.debug("Source file not found on disk: {} (repo={})", sourceFile, repository);
                    return Optional.empty();
                }
                com.github.javaparser.StaticJavaParser.getConfiguration().setLanguageLevel(
                        com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17);
                return Optional.of(com.github.javaparser.StaticJavaParser.parse(sourceFile));
            } catch (Exception e) {
                log.debug("Could not parse file {} (repo={}): {}", relativeFilePath, repository, e.getMessage());
                return Optional.empty();
            }
        });
    }
    
    /**
     * Re-link every repository already ingested into the graph against every other
     * discovered repository, so cross-repo relationships stay correct in both
     * directions: this must be re-run whenever a new repository is ingested, because
     * a previously-ingested repository A that depends on a newly-ingested repository B
     * only gets its INJECTS/CALLS edges to B created if A is re-scanned as a source
     * (simply re-scanning the newly-ingested repository B is not enough). All writes
     * are idempotent ({@code MERGE}), so re-running this for repositories that have
     * no new relationships to discover is safe and cheap.
     */
    public Map<String, LinkingStats> linkAllRepositories() {
        log.info("Starting bulk cross-repo linking for all repositories discovered in the graph");
        
        List<String> allRepositories = classRepository.findAllDistinctRepositories();
        Map<String, LinkingStats> results = new HashMap<>();
        
        for (String sourceRepo : allRepositories) {
            try {
                LinkingStats stats = linkCrossRepoRelationships(sourceRepo, null);
                results.put(sourceRepo, stats);
            } catch (Exception e) {
                log.error("Failed to link repository: {}", sourceRepo, e);
                results.put(sourceRepo, new LinkingStats(0, 0, 0, 0, "Error: " + e.getMessage()));
            }
        }
        
        log.info("Bulk linking complete: {} repositories processed", results.size());
        return results;
    }
    
    // ── Result classes ──────────────────────────────────────────────────────
    
    /**
     * Statistics about cross-repo linking operation.
     * 
     * @param methodsScanned Number of methods analyzed
     * @param injectsCreated Number of INJECTS relationships created
     * @param callsCreated Number of CALLS relationships created
     * @param targetRepositories Number of target repositories linked
     * @param errorMessage Error message if operation failed
     */
    public record LinkingStats(
            int methodsScanned,
            int injectsCreated,
            int callsCreated,
            int targetRepositories,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
        
        @Override
        public String toString() {
            if (errorMessage != null) {
                return String.format("ERROR: %s", errorMessage);
            }
            return String.format("methods=%d, INJECTS=%d, CALLS=%d, targets=%d",
                    methodsScanned, injectsCreated, callsCreated, targetRepositories);
        }
    }
    
    private record CallsResult(int methodsScanned, int callsCreated) {}
    
    /**
     * Represents an injected dependency (field or constructor param).
     */
    private record InjectedDependency(
            String fieldName,
            String typeName,      // Fully qualified class name or simple name
            String injectionType  // FIELD or CONSTRUCTOR
    ) {}
    
    /**
     * Represents a method invocation on an injected field.
     * Per temp3: Contains receiver, method name, parameter types for resolution.
     */
    private record MethodInvocation(
            String receiverFieldName,  // The field being called (e.g., "userService")
            String receiverType,       // The type of the field (e.g., "UserService" or "com.axis.UserService")
            String methodName,         // The method being called (e.g., "findById")
            List<String> parameterTypes, // Parameter types for overload resolution
            int paramCount
    ) {}
}
