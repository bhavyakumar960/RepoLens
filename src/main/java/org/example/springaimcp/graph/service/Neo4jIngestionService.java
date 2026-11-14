package org.example.springaimcp.graph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.config.RepositoryDependencyConfig;
import org.example.springaimcp.graph.model.*;
import org.example.springaimcp.graph.model.Module;
import org.example.springaimcp.graph.repository.*;
import org.example.springaimcp.graph.util.GraphNodeIdentity;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for ingesting Java code into Neo4j graph database.
 * Follows the design principles from temp2:
 * - Neo4j stores structure + relationships
 * - No raw source code in Neo4j (only in Qdrant)
 * - Stable unique IDs for all entities
 * - Captures important relationships (CALLS, IMPLEMENTS, EXTENDS, USES, etc.)
 * - Incremental indexing with idempotency
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Neo4jIngestionService {

    private final CodeRepositoryRepository repositoryRepository;
    private final JavaFileRepository fileRepository;
    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;
    private final JavaPackageRepository packageRepository;
    private final ModuleRepository moduleRepository;
    private final RepositoryDependencyConfig dependencyConfig;
    private final Neo4jClient neo4jClient;

    private static final Set<String> SPRING_ANNOTATIONS = Set.of(
            "RestController", "Controller", "Service", "Repository", "Component",
            "Configuration", "Bean", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestMapping"
    );

    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", 
            "TestTemplate", "TestMethodOrder", "DisplayName"
    );

    private static final String RESOLUTION_RESOLVED = "RESOLVED";
    private static final String RESOLUTION_STUB = "STUB";
    private static final String RESOLUTION_AMBIGUOUS = "AMBIGUOUS";
    private static final String RESOLUTION_UNRESOLVED = "UNRESOLVED";

    private static final String TYPE_SAME_CLASS = "SAME_CLASS";
    private static final String TYPE_INHERITED = "INHERITED";
    private static final String TYPE_FIELD = "FIELD";
    private static final String TYPE_CONSTRUCTOR_INJECTION = "CONSTRUCTOR_INJECTION";
    private static final String TYPE_INTERFACE = "INTERFACE";
    private static final String TYPE_STATIC = "STATIC";
    private static final String TYPE_CROSS_REPOSITORY = "CROSS_REPOSITORY";

    /** Cache of JavaParser instances keyed by source root, one solver per module. */
    private final Map<Path, JavaParser> parserCache = new ConcurrentHashMap<>();

    /** 
     * Locks for synchronizing access to shared hierarchy nodes (Repository/Module/Package).
     * Prevents deadlocks during concurrent ingestion by ensuring ordered access.
     */
    private final Map<String, Object> hierarchyLocks = new ConcurrentHashMap<>();
    
    /**
     * Locks for synchronizing class creation/updates to prevent concurrent saves
     * of the same class from different threads.
     */
    private final Map<String, Object> classLocks = new ConcurrentHashMap<>();

    private Object getHierarchyLock(String repoName, String moduleName, String packageName) {
        String key = repoName + ":" + moduleName + ":" + packageName;
        return hierarchyLocks.computeIfAbsent(key, k -> new Object());
    }
    
    private Object getClassLock(String classId) {
        return classLocks.computeIfAbsent(classId, k -> new Object());
    }

    private JavaParser parserFor(Path sourceRoot) {
        return parserCache.computeIfAbsent(sourceRoot, root -> {
            CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                    new ReflectionTypeSolver(),
                    new JavaParserTypeSolver(root));
            ParserConfiguration config = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                    .setSymbolResolver(new JavaSymbolSolver(typeSolver));
            return new JavaParser(config);
        });
    }

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 100;

    /**
     * Ingest a Java file into the Neo4j graph database.
     * Retries up to {@value MAX_RETRIES} times on transient Neo4j deadlock errors.
     */
    public IngestionStats ingestJavaFile(Path sourcePath, String repository, String module,
                                         String branch, String commitSha) {
        // Skip test files - they cause JavaParser issues and don't add much value for code intelligence
        if (sourcePath.toString().contains("/test/") || sourcePath.toString().contains("\\test\\")) {
            log.debug("Skipping test file: {}", sourcePath);
            return new IngestionStats(0, 0, 0, 0, true);
        }
        
        log.info("Starting Neo4j ingestion: file={}, repo={}, module={}, branch={}, commit={}",
                sourcePath, repository, module, branch, commitSha);

        TransientDataAccessResourceException lastDeadlock = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doIngestJavaFile(sourcePath, repository, module, branch, commitSha);
            } catch (TransientDataAccessResourceException e) {
                lastDeadlock = e;
                log.warn("Neo4j deadlock on attempt {}/{}: file={}", attempt, MAX_RETRIES, sourcePath);
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during deadlock retry", ie);
                    }
                }
            } catch (RuntimeException e) {
                // Unwrap and check if root cause is a deadlock
                if (e.getCause() instanceof TransientDataAccessResourceException tde) {
                    lastDeadlock = tde;
                    log.warn("Neo4j deadlock (wrapped) on attempt {}/{}: file={}", attempt, MAX_RETRIES, sourcePath);
                    if (attempt < MAX_RETRIES) {
                        try {
                            Thread.sleep(RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during deadlock retry", ie);
                        }
                        continue;
                    }
                }
                throw e;
            }
        }
        throw new RuntimeException("Neo4j ingestion failed after " + MAX_RETRIES
                + " retries (deadlock): " + sourcePath, lastDeadlock);
    }

    private IngestionStats doIngestJavaFile(Path sourcePath, String repository, String module,
                                             String branch, String commitSha) {

        try {
            // Compute file hash for incremental indexing
            String fileHash = computeSha256(Files.readAllBytes(sourcePath));

            // Check if file has changed
            String relativeFilePath = extractRelativePath(sourcePath, repository);
            String fileId = generateFileId(repository, relativeFilePath);
            Optional<JavaFile> existingFile = fileRepository.findByFileId(fileId);

            if (existingFile.isPresent() && fileHash.equals(existingFile.get().getFileHash())) {
                log.info("File unchanged, skipping: {}", sourcePath);
                return new IngestionStats(0, 0, 0, 0, true);
            }

            // Parse the Java file using a symbol-solver-aware parser for this source root
            CompilationUnit cu;
            try {
                Path sourceRoot = detectSourceRoot(sourcePath);
                JavaParser parser = parserFor(sourceRoot);
                var parseAttempt = parser.parse(sourcePath.toFile());
                
                Optional<CompilationUnit> parseResult = parseAttempt.getResult();
                
                // If parsing fails with symbol solver, try without it as fallback
                if (parseResult.isEmpty() || !parseAttempt.isSuccessful()) {
                    log.debug("Parse failed with symbol solver for {}, trying without symbol resolver", sourcePath);
                    
                    // Fallback: parse without symbol solver
                    JavaParser simpleParser = new JavaParser();
                    parseAttempt = simpleParser.parse(sourcePath.toFile());
                    parseResult = parseAttempt.getResult();
                    
                    if (parseResult.isEmpty() || !parseAttempt.isSuccessful()) {
                        log.warn("Parse failed for {}: JavaParser internal error", sourcePath);
                        return new IngestionStats(0, 0, 0, 0, true);
                    }
                    
                    log.debug("Successfully parsed without symbol solver: {}", sourcePath);
                }
                
                cu = parseResult.get();
            } catch (AssertionError | Exception e) {
                // JavaParser can throw AssertionError for malformed code or internal bugs
                log.warn("JavaParser error for {}: {} - {}", sourcePath, e.getClass().getSimpleName(), e.getMessage());
                return new IngestionStats(0, 0, 0, 0, true);
            }
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("(default)");

            IngestionStats stats = new IngestionStats(0, 0, 0, 0, false);

            // Create or update JavaFile node
            JavaFile javaFile = existingFile.orElse(new JavaFile());
            javaFile.setFileId(fileId);
            javaFile.setPath(relativeFilePath);
            javaFile.setRepository(repository);
            javaFile.setModule(module);
            javaFile.setPackageName(packageName);
            javaFile.setFileHash(fileHash);
            javaFile.setBranch(branch);
            javaFile.setCommit(commitSha);
            javaFile.setLastModifiedTimestamp(System.currentTimeMillis());

            // Process all type declarations (classes, interfaces, enums)
            Set<JavaClass> declaredClasses = new HashSet<>();
            for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
                JavaClass javaClass = processTypeDeclaration(typeDecl, cu, sourcePath,
                        repository, module, packageName, relativeFilePath, branch, commitSha);
                if (javaClass != null) {
                    javaClass = classRepository.save(javaClass);
                    declaredClasses.add(javaClass);
                    stats.incrementClasses();
                    stats.addMethods(javaClass.getMethods().size());
                    stats.addFields(javaClass.getFields().size());
                }
            }

            javaFile.setClasses(declaredClasses);
            fileRepository.save(javaFile);
            stats.incrementFiles();

            // Ensure Repository → Module → Package → File hierarchy
            ensureHierarchy(repository, module, packageName, branch, commitSha, javaFile);

            // Establish test relationships immediately after ingestion (if this is a test file)
            // TODO: Re-enable when JavaParser stability improves or we switch to a different parser
            // int testRelationships = establishTestRelationshipsForFile(javaFile, repository);
            int testRelationships = 0; // Temporarily disabled due to JavaParser issues
            stats.setTestRelationships(testRelationships);

            log.info("Neo4j ingestion complete: file={}, stats={}", sourcePath, stats);
            return stats;

        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("Source file not found: " + sourcePath, e);
        } catch (Exception e) {
            log.error("Failed to ingest Java file into Neo4j: {}", sourcePath, e);
            throw new RuntimeException("Neo4j ingestion failed for: " + sourcePath, e);
        }
    }

    /**
     * Ingest an entire repository directory recursively.
     */
    @Transactional
    public BatchIngestionStats ingestRepository(Path rootPath, String repository, String module,
                                                String branch, String commitSha) {
        log.info("Starting batch Neo4j ingestion: root={}, repo={}, module={}", rootPath, repository, module);

        BatchIngestionStats batchStats = new BatchIngestionStats();

        try {
            Files.walk(rootPath)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/build/"))
                    .forEach(javaFile -> {
                        try {
                            IngestionStats stats = ingestJavaFile(javaFile, repository, module, branch, commitSha);
                            batchStats.add(stats);
                        } catch (Exception e) {
                            log.error("Failed to ingest file: {}", javaFile, e);
                            batchStats.incrementErrors();
                        }
                    });

        } catch (Exception e) {
            log.error("Failed to walk repository tree: {}", rootPath, e);
            throw new RuntimeException("Repository ingestion failed", e);
        }

        log.info("Batch Neo4j ingestion complete: root={}, stats={}", rootPath, batchStats);
        return batchStats;
    }

    /**
     * Process a type declaration (class, interface, enum, record).
     */
    private JavaClass processTypeDeclaration(TypeDeclaration<?> typeDecl, CompilationUnit cu,
                                             Path sourcePath, String repository, String module,
                                             String packageName, String filePath, String branch,
                                             String commitSha) {
        String simpleName = typeDecl.getNameAsString();
        String fullyQualifiedName = packageName.equals("(default)")
                ? simpleName
                : packageName + "." + simpleName;
        String classId = generateClassId(repository, fullyQualifiedName);

        // Find or create JavaClass
        Optional<JavaClass> existing = classRepository.findByClassId(classId);
        JavaClass javaClass = existing.orElse(new JavaClass());
        javaClass.setClassId(classId);
        javaClass.setFullyQualifiedName(fullyQualifiedName);
        javaClass.setSimpleName(simpleName);
        javaClass.setRepository(repository);
        javaClass.setModule(module);
        javaClass.setPackageName(packageName);
        javaClass.setFilePath(filePath);
        javaClass.setBranch(branch);
        javaClass.setCommit(commitSha);
        javaClass.setNodeStatus(GraphNodeIdentity.REAL);
        javaClass.setResolutionKey(fullyQualifiedName);

        // Determine type
        if (typeDecl instanceof ClassOrInterfaceDeclaration classOrInterface) {
            if (classOrInterface.isInterface()) {
                javaClass.setType("INTERFACE");
            } else if (classOrInterface.isAbstract()) {
                javaClass.setType("ABSTRACT_CLASS");
            } else {
                javaClass.setType("CLASS");
            }

            // Process inheritance and implementation
            processInheritance(classOrInterface, javaClass, repository, packageName, cu);

        } else if (typeDecl instanceof EnumDeclaration) {
            javaClass.setType("ENUM");
        } else if (typeDecl instanceof RecordDeclaration) {
            javaClass.setType("RECORD");
        }

        // Extract Spring annotations
        Set<String> springAnnotations = extractSpringAnnotations(typeDecl);
        javaClass.setSpringAnnotations(springAnnotations);

        // Get line numbers
        javaClass.setStartLine(typeDecl.getBegin().map(pos -> pos.line).orElse(0));
        javaClass.setEndLine(typeDecl.getEnd().map(pos -> pos.line).orElse(0));

        // Process fields
        Set<JavaField> fields = new HashSet<>();
        for (FieldDeclaration field : typeDecl.getFields()) {
            fields.addAll(processFieldDeclaration(field, cu, javaClass, repository, module,
                    packageName, fullyQualifiedName, filePath, branch, commitSha));
        }
        javaClass.setFields(fields);

        // Process methods - first create all method nodes so self/circular calls resolve deterministically
        List<MethodWorkItem> methodWorkItems = new ArrayList<>();
        Set<JavaMethod> methods = new HashSet<>();
        for (MethodDeclaration method : typeDecl.getMethods()) {
            JavaMethod javaMethod = processMethodDeclaration(method, javaClass, cu,
                    repository, module, packageName, fullyQualifiedName, filePath, branch, commitSha);
            if (javaMethod != null) {
                methods.add(javaMethod);
                methodWorkItems.add(new MethodWorkItem(javaMethod, method));
            }
        }

        // Process constructors
        for (ConstructorDeclaration ctor : typeDecl.getConstructors()) {
            JavaMethod ctorMethod = processConstructorDeclaration(ctor, javaClass, cu,
                    repository, module, packageName, fullyQualifiedName, filePath, branch, commitSha);
            if (ctorMethod != null) {
                methods.add(ctorMethod);
                methodWorkItems.add(new MethodWorkItem(ctorMethod, ctor));
            }
        }

        javaClass.setMethods(methods);

        // Populate USES relationships from field types + method signatures
        Set<JavaClass> usedClasses = collectUsedClasses(typeDecl, cu, javaClass, repository, packageName);
        javaClass.setUsedClasses(usedClasses);

        ClassCallContext callContext = buildClassCallContext(typeDecl, cu, javaClass, repository, module, packageName);
        methodWorkItems.forEach(workItem -> persistMethodCalls(workItem, callContext));

        return javaClass;
    }

    /**
     * Process inheritance relationships (EXTENDS, IMPLEMENTS).
     */
    private void processInheritance(ClassOrInterfaceDeclaration classDecl, JavaClass javaClass,
                                    String repository, String packageName, CompilationUnit cu) {
        // Process extended classes
        for (ClassOrInterfaceType extendedType : classDecl.getExtendedTypes()) {
            try {
                String extendedName = normalizeClassOrInterfaceType(extendedType, cu, packageName);
                String extendedClassId = generateClassId(repository, extendedName);

                // Synchronize to prevent concurrent creation of the same base class
                synchronized (getClassLock(extendedClassId)) {
                    Optional<JavaClass> existing = classRepository.findByClassId(extendedClassId);
                    JavaClass extendedClass;
                    if (existing.isPresent()) {
                        extendedClass = existing.get();
                    } else {
                        JavaClass newClass = new JavaClass();
                        newClass.setClassId(extendedClassId);
                        newClass.setFullyQualifiedName(extendedName);
                        newClass.setSimpleName(GraphNodeIdentity.simpleName(extendedName));
                        newClass.setRepository(repository);
                        newClass.setNodeStatus(GraphNodeIdentity.STUB);
                        newClass.setResolutionKey(extendedName);
                        extendedClass = classRepository.save(newClass);
                    }
                    javaClass.getExtendedClasses().add(extendedClass);
                }
            } catch (Exception e) {
                log.debug("Could not resolve extended type: {}", extendedType.getNameAsString(), e);
            }
        }

        // Process implemented interfaces
        for (ClassOrInterfaceType implementedType : classDecl.getImplementedTypes()) {
            try {
                String implementedName = normalizeClassOrInterfaceType(implementedType, cu, packageName);
                String implementedClassId = generateClassId(repository, implementedName);

                // Synchronize to prevent concurrent creation of the same interface
                synchronized (getClassLock(implementedClassId)) {
                    Optional<JavaClass> existing = classRepository.findByClassId(implementedClassId);
                    JavaClass implementedInterface;
                    if (existing.isPresent()) {
                        implementedInterface = existing.get();
                    } else {
                        JavaClass newClass = new JavaClass();
                        newClass.setClassId(implementedClassId);
                        newClass.setFullyQualifiedName(implementedName);
                        newClass.setSimpleName(GraphNodeIdentity.simpleName(implementedName));
                        newClass.setRepository(repository);
                        newClass.setType("INTERFACE");
                        newClass.setNodeStatus(GraphNodeIdentity.STUB);
                        newClass.setResolutionKey(implementedName);
                        implementedInterface = classRepository.save(newClass);
                    }
                    javaClass.getImplementedInterfaces().add(implementedInterface);
                }
            } catch (Exception e) {
                log.debug("Could not resolve implemented interface: {}", implementedType.getNameAsString(), e);
            }
        }
    }

    private static final Set<String> PRIMITIVES_AND_BUILTINS = Set.of(
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
            "String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Byte",
            "Short", "Character", "Number", "Comparable", "Iterable", "Cloneable",
            "List", "Map", "Set", "Collection", "Optional", "Stream", "Iterator",
            "ArrayList", "HashMap", "HashSet", "LinkedList", "TreeMap", "TreeSet",
            "var", "Class", "Enum", "Throwable", "Exception", "RuntimeException", "Error"
    );

    /**
     * Collects USES relationships: classes referenced in field types, method params, and return types.
     * Delegates resolution/stub-creation to {@link #findOrCreateClassRef}.
     */
    private Set<JavaClass> collectUsedClasses(TypeDeclaration<?> typeDecl, CompilationUnit cu,
                                               JavaClass owningClass, String repository, String packageName) {
        Set<String> referencedTypes = new HashSet<>();

        typeDecl.getFields().forEach(f ->
                referencedTypes.add(stripGenerics(f.getElementType().asString())));
        typeDecl.getMethods().forEach(m -> {
            referencedTypes.add(stripGenerics(m.getType().asString()));
            m.getParameters().forEach(p -> referencedTypes.add(stripGenerics(p.getType().asString())));
        });
        typeDecl.getConstructors().forEach(c ->
                c.getParameters().forEach(p -> referencedTypes.add(stripGenerics(p.getType().asString()))));

        Set<JavaClass> usedClasses = new HashSet<>();
        for (String typeName : referencedTypes) {
            if (typeName.equals(owningClass.getSimpleName())) continue;
            findOrCreateClassRef(typeName, cu, packageName, repository)
                    .filter(c -> !c.getFullyQualifiedName().equals(owningClass.getFullyQualifiedName()))
                    .ifPresent(usedClasses::add);
        }
        return usedClasses;
    }

    /** Strips generic type parameters, e.g. {@code List<UserDto>} → {@code List}. */
    private static String stripGenerics(String typeName) {
        int idx = typeName.indexOf('<');
        return idx >= 0 ? typeName.substring(0, idx).trim() : typeName.trim();
    }

    /**
     * Process a field declaration.
     */
    private Set<JavaField> processFieldDeclaration(FieldDeclaration field, CompilationUnit cu, JavaClass owningClass,
                                                    String repository, String module,
                                                    String packageName, String className,
                                                    String filePath, String branch, String commitSha) {
        Set<JavaField> fields = new HashSet<>();
        String simpleTypeName = normalizeDeclaredType(field.getElementType(), cu, packageName);

        for (VariableDeclarator variable : field.getVariables()) {
            JavaField javaField = new JavaField();
            javaField.setFieldId(generateFieldId(repository, className, variable.getNameAsString()));
            javaField.setName(variable.getNameAsString());
            javaField.setType(simpleTypeName);
            javaField.setLineNumber(variable.getBegin().map(pos -> pos.line).orElse(0));
            javaField.setRepository(repository);
            javaField.setModule(module);
            javaField.setPackageName(packageName);
            javaField.setClassName(className);
            javaField.setFilePath(filePath);
            javaField.setBranch(branch);
            javaField.setCommit(commitSha);
            javaField.setSpringAnnotations(extractSpringAnnotations(field));

            // HAS_TYPE relationship — resolve field type to a JavaClass node
            findOrCreateClassRef(simpleTypeName, cu, packageName, repository)
                    .ifPresent(javaField::setFieldType);

            fields.add(javaField);
        }

        return fields;
    }

    /**
     * Process a method declaration.
     */
    private JavaMethod processMethodDeclaration(MethodDeclaration method, JavaClass owningClass,
                                                CompilationUnit cu, String repository, String module,
                                                String packageName, String className, String filePath,
                                                String branch, String commitSha) {
        String methodName = method.getNameAsString();
        List<String> parameterTypes = method.getParameters().stream()
                .map(param -> normalizeDeclaredType(param.getType(), cu, packageName))
                .collect(Collectors.toList());
        String signature = method.getSignature().asString();
        String fullyQualifiedSignature = GraphNodeIdentity.methodResolutionKey(className, methodName, parameterTypes);
        String methodId = GraphNodeIdentity.methodId(repository, className, methodName, parameterTypes);

        // Find or create JavaMethod
        Optional<JavaMethod> existing = methodRepository.findByMethodId(methodId);
        JavaMethod javaMethod = existing.orElse(new JavaMethod());
        javaMethod.setMethodId(methodId);
        javaMethod.setName(methodName);
        javaMethod.setSignature(signature);
        javaMethod.setFullyQualifiedSignature(fullyQualifiedSignature);
        javaMethod.setReturnType(method.getType().asString());
        javaMethod.setRepository(repository);
        javaMethod.setModule(module);
        javaMethod.setPackageName(packageName);
        javaMethod.setClassName(className);
        javaMethod.setFilePath(filePath);
        javaMethod.setBranch(branch);
        javaMethod.setCommit(commitSha);
        javaMethod.setNodeStatus(GraphNodeIdentity.REAL);
        javaMethod.setResolutionKey(fullyQualifiedSignature);

        // Get line numbers
        javaMethod.setStartLine(method.getBegin().map(pos -> pos.line).orElse(0));
        javaMethod.setEndLine(method.getEnd().map(pos -> pos.line).orElse(0));

        // Extract parameters
        javaMethod.setParameterTypes(parameterTypes);

        // Extract Spring annotations and API metadata
        Set<String> springAnnotations = extractSpringAnnotations(method);
        javaMethod.setSpringAnnotations(springAnnotations);

        ApiInfo apiInfo = extractApiInfo(method);
        if (apiInfo != null) {
            javaMethod.setHttpMethod(apiInfo.httpMethod());
            javaMethod.setApiPath(apiInfo.path());
        }

        // ACCEPTS relationships — parameter types → JavaClass nodes
        Set<JavaClass> paramClasses = new HashSet<>();
        method.getParameters().forEach(p ->
                findOrCreateClassRef(normalizeDeclaredType(p.getType(), cu, packageName), cu, packageName, repository)
                        .ifPresent(paramClasses::add));
        javaMethod.setParameterClasses(paramClasses);

        // RETURNS relationship — return type → JavaClass node
        findOrCreateClassRef(normalizeDeclaredType(method.getType(), cu, packageName), cu, packageName, repository)
                .ifPresent(javaMethod::setReturnClass);

        return methodRepository.save(javaMethod);
    }

    /**
     * Process a constructor declaration.
     */
    private JavaMethod processConstructorDeclaration(ConstructorDeclaration ctor, JavaClass owningClass,
                                                     CompilationUnit cu, String repository, String module,
                                                     String packageName, String className, String filePath,
                                                     String branch, String commitSha) {
        String constructorName = ctor.getNameAsString();
        List<String> parameterTypes = ctor.getParameters().stream()
                .map(param -> normalizeDeclaredType(param.getType(), cu, packageName))
                .collect(Collectors.toList());
        String signature = ctor.getSignature().asString();
        String fullyQualifiedSignature = GraphNodeIdentity.methodResolutionKey(className, constructorName, parameterTypes);
        String methodId = GraphNodeIdentity.methodId(repository, className, constructorName, parameterTypes);

        // Find or create JavaMethod for constructor
        Optional<JavaMethod> existing = methodRepository.findByMethodId(methodId);
        JavaMethod javaMethod = existing.orElse(new JavaMethod());
        javaMethod.setMethodId(methodId);
        javaMethod.setName(constructorName);
        javaMethod.setSignature(signature);
        javaMethod.setFullyQualifiedSignature(fullyQualifiedSignature);
        javaMethod.setReturnType("void");
        javaMethod.setRepository(repository);
        javaMethod.setModule(module);
        javaMethod.setPackageName(packageName);
        javaMethod.setClassName(className);
        javaMethod.setFilePath(filePath);
        javaMethod.setBranch(branch);
        javaMethod.setCommit(commitSha);
        javaMethod.setNodeStatus(GraphNodeIdentity.REAL);
        javaMethod.setResolutionKey(fullyQualifiedSignature);

        // Get line numbers
        javaMethod.setStartLine(ctor.getBegin().map(pos -> pos.line).orElse(0));
        javaMethod.setEndLine(ctor.getEnd().map(pos -> pos.line).orElse(0));

        // Extract parameters
        javaMethod.setParameterTypes(parameterTypes);

        // Extract Spring annotations
        Set<String> springAnnotations = extractSpringAnnotations(ctor);
        javaMethod.setSpringAnnotations(springAnnotations);

        // ACCEPTS relationships — parameter types → JavaClass nodes
        Set<JavaClass> paramClasses = new HashSet<>();
        ctor.getParameters().forEach(p ->
                findOrCreateClassRef(normalizeDeclaredType(p.getType(), cu, packageName), cu, packageName, repository)
                        .ifPresent(paramClasses::add));
        javaMethod.setParameterClasses(paramClasses);

        return methodRepository.save(javaMethod);
    }

    private ClassCallContext buildClassCallContext(TypeDeclaration<?> typeDecl, CompilationUnit cu,
                                                   JavaClass owningClass, String repository,
                                                   String module, String packageName) {
        Map<String, String> importMap = buildImportMap(cu);
        Map<String, JavaMethod> sameClassMethods = owningClass.getMethods().stream()
                .collect(Collectors.toMap(JavaMethod::getResolutionKey, method -> method, (left, right) -> left));

        Map<String, DeclaredDependency> fieldTypes = new HashMap<>();
        for (FieldDeclaration fieldDeclaration : typeDecl.getFields()) {
            String normalizedType = normalizeDeclaredType(fieldDeclaration.getElementType(), cu, packageName);
            for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                fieldTypes.putIfAbsent(variable.getNameAsString(),
                        new DeclaredDependency(variable.getNameAsString(), normalizedType, TYPE_FIELD));
            }
        }

        for (ConstructorDeclaration constructor : typeDecl.getConstructors()) {
            boolean injectableConstructor = constructor.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .anyMatch(name -> "Autowired".equals(name) || "Inject".equals(name))
                    || typeDecl.getConstructors().size() == 1;
            if (!injectableConstructor) {
                continue;
            }
            Map<String, String> parameterTypes = constructor.getParameters().stream()
                    .collect(Collectors.toMap(
                            parameter -> parameter.getNameAsString(),
                            parameter -> normalizeDeclaredType(parameter.getType(), cu, packageName),
                            (left, right) -> left
                    ));
            constructor.getBody().findAll(AssignExpr.class).forEach(assignExpr -> {
                String assignedField = extractAssignedFieldName(assignExpr.getTarget());
                if (assignedField == null || !assignExpr.getValue().isNameExpr()) {
                    return;
                }
                String parameterName = assignExpr.getValue().asNameExpr().getNameAsString();
                String parameterType = parameterTypes.get(parameterName);
                if (parameterType != null) {
                    fieldTypes.put(assignedField,
                            new DeclaredDependency(assignedField, parameterType, TYPE_CONSTRUCTOR_INJECTION));
                }
            });
        }

        List<String> searchRepositories = new ArrayList<>();
        searchRepositories.add(repository);
        if (dependencyConfig.isEnabled()) {
            // Honor an explicit dependency-map override when configured, otherwise
            // auto-discover every other repository already ingested into the graph
            // so cross-repo type resolution works without static configuration.
            List<String> candidateRepos = dependencyConfig.hasDependencies(repository)
                    ? dependencyConfig.getDependencies(repository)
                    : classRepository.findAllDistinctRepositories();
            candidateRepos.stream()
                    .filter(dep -> dep != null && !dep.isBlank() && !searchRepositories.contains(dep))
                    .forEach(searchRepositories::add);
        }

        List<String> superTypes = new ArrayList<>();
        if (typeDecl instanceof ClassOrInterfaceDeclaration classOrInterfaceDeclaration) {
            classOrInterfaceDeclaration.getExtendedTypes().forEach(type ->
                    superTypes.add(normalizeClassOrInterfaceType(type, cu, packageName)));
        }

        return new ClassCallContext(
                owningClass,
                repository,
                module,
                packageName,
                cu,
                importMap,
                fieldTypes,
                sameClassMethods,
                searchRepositories,
                superTypes
        );
    }

    private void persistMethodCalls(MethodWorkItem workItem, ClassCallContext context) {
        JavaMethod sourceMethod = workItem.method();
        deleteCallsForMethod(sourceMethod.getMethodId());

        Optional<BlockStmt> bodyOpt = getBody(workItem.callableDeclaration());
        if (bodyOpt.isEmpty()) {
            return;
        }

        Map<String, CallEdgeAccumulator> edges = new LinkedHashMap<>();
        Map<String, String> visibleTypes = buildVisibleTypeMap(workItem.callableDeclaration(), context);

        bodyOpt.get().findAll(MethodCallExpr.class).forEach(callExpr -> resolveMethodCall(callExpr, workItem, context, visibleTypes)
                .ifPresent(callTarget -> {
                    if (RESOLUTION_UNRESOLVED.equals(callTarget.resolutionStatus()) && callTarget.targetMethodId() == null) {
                        log.debug("Leaving invocation unresolved without fabricated target: {} at {}:{}",
                                callExpr.getNameAsString(),
                                callExpr.getBegin().map(pos -> pos.line).orElse(0),
                                callExpr.getBegin().map(pos -> pos.column).orElse(0));
                        return;
                    }
                    String accumulatorKey = callTarget.targetMethodId();
                    CallEdgeAccumulator accumulator = edges.computeIfAbsent(accumulatorKey,
                            ignored -> new CallEdgeAccumulator(sourceMethod.getMethodId(), callTarget));
                    accumulator.addSite(
                            callExpr.getBegin().map(pos -> pos.line).orElse(0),
                            callExpr.getBegin().map(pos -> pos.column).orElse(0)
                    );
                }));

        edges.values().forEach(this::upsertCallRelationship);
    }

    private Map<String, String> buildVisibleTypeMap(CallableDeclaration<?> callableDeclaration, ClassCallContext context) {
        Map<String, String> visibleTypes = new HashMap<>();
        callableDeclaration.getParameters().forEach(parameter ->
                visibleTypes.put(parameter.getNameAsString(), normalizeDeclaredType(parameter.getType(), context.compilationUnit(), context.packageName())));
        Optional<BlockStmt> bodyOpt = getBody(callableDeclaration);
        if (bodyOpt.isPresent()) {
            bodyOpt.get().findAll(VariableDeclarator.class).forEach(variableDeclarator ->
                    visibleTypes.put(variableDeclarator.getNameAsString(),
                            normalizeDeclaredType(variableDeclarator.getType(), context.compilationUnit(), context.packageName())));
        }
        return visibleTypes;
    }

    private Optional<ResolvedCallTarget> resolveMethodCall(MethodCallExpr callExpr, MethodWorkItem source,
                                                           ClassCallContext context, Map<String, String> visibleTypes) {
        List<String> parameterTypes = resolveInvocationParameterTypes(callExpr, context, visibleTypes);
        String methodName = callExpr.getNameAsString();
        int argumentCount = callExpr.getArguments().size();

        if (callExpr.getScope().isEmpty() || callExpr.getScope().get().isThisExpr()) {
            JavaMethod sameClassMethod = context.sameClassMethods().get(
                    GraphNodeIdentity.methodResolutionKey(context.owningClass().getFullyQualifiedName(), methodName, parameterTypes));
            if (sameClassMethod != null) {
                return Optional.of(resolvedTarget(sameClassMethod, TYPE_SAME_CLASS, methodName, argumentCount,
                        context.owningClass().getFullyQualifiedName()));
            }
            Optional<JavaMethod> sameClassRepositoryMethod = findExactMethod(
                    context.repository(), context.owningClass().getFullyQualifiedName(), methodName, parameterTypes);
            if (sameClassRepositoryMethod.isPresent()) {
                return Optional.of(resolvedTarget(sameClassRepositoryMethod.get(), TYPE_SAME_CLASS, methodName,
                        argumentCount, context.owningClass().getFullyQualifiedName()));
            }
            for (String superType : context.superTypes()) {
                Optional<JavaMethod> inherited = resolveWithinHierarchy(superType, methodName, parameterTypes,
                        context.searchRepositories(), new HashSet<>());
                if (inherited.isPresent()) {
                    return Optional.of(resolvedTarget(inherited.get(), TYPE_INHERITED, methodName, argumentCount, superType));
                }
            }
            return findAcrossRepositories(context.searchRepositories(),
                            context.owningClass().getFullyQualifiedName(), methodName, parameterTypes)
                    .map(method -> resolvedTarget(method, method.getRepository().equals(context.repository())
                                    ? TYPE_SAME_CLASS
                                    : TYPE_CROSS_REPOSITORY,
                            methodName, argumentCount, context.owningClass().getFullyQualifiedName()))
                    .or(() -> createStubTarget(context, context.owningClass().getFullyQualifiedName(), methodName,
                            parameterTypes, TYPE_SAME_CLASS, methodName, argumentCount,
                            context.owningClass().getFullyQualifiedName(), GraphNodeIdentity.UNKNOWN_REPOSITORY));
        }

        Expression scope = callExpr.getScope().get();
        if (scope.isSuperExpr()) {
            for (String superType : context.superTypes()) {
                Optional<JavaMethod> inherited = resolveWithinHierarchy(superType, methodName, parameterTypes, context.searchRepositories(), new HashSet<>());
                if (inherited.isPresent()) {
                    return Optional.of(resolvedTarget(inherited.get(), TYPE_INHERITED, methodName, argumentCount, superType));
                }
            }
            return context.superTypes().stream().findFirst()
                    .flatMap(superType -> createStubTarget(context, superType, methodName, parameterTypes,
                            TYPE_INHERITED, methodName, argumentCount, superType, GraphNodeIdentity.UNKNOWN_REPOSITORY));
        }

        String scopeName = scope.toString().startsWith("this.") ? scope.toString().substring(5) : scope.toString();
        DeclaredDependency dependency = context.fieldTypes().get(scopeName);
        if (dependency != null) {
            return resolveDependencyCall(context, dependency, methodName, parameterTypes, argumentCount);
        }

        String staticType = tryResolveStaticScope(scope, context, visibleTypes);
        if (staticType != null) {
            Optional<JavaMethod> staticMethod = findAcrossRepositories(context.searchRepositories(), staticType, methodName, parameterTypes);
            if (staticMethod.isPresent()) {
                JavaMethod method = staticMethod.get();
                return Optional.of(resolvedTarget(method,
                        method.getRepository().equals(context.repository()) ? TYPE_STATIC : TYPE_CROSS_REPOSITORY,
                        methodName, argumentCount, staticType));
            }
            return createStubTarget(context, staticType, methodName, parameterTypes,
                    TYPE_STATIC, methodName, argumentCount, staticType, GraphNodeIdentity.UNKNOWN_REPOSITORY);
        }

        String receiverType = tryResolveReceiverType(scope, context, visibleTypes);
        if (receiverType != null) {
            Optional<JavaMethod> targetMethod = findAcrossRepositories(context.searchRepositories(), receiverType, methodName, parameterTypes);
            if (targetMethod.isPresent()) {
                JavaMethod method = targetMethod.get();
                return Optional.of(resolvedTarget(method,
                        method.getRepository().equals(context.repository()) ? TYPE_FIELD : TYPE_CROSS_REPOSITORY,
                        methodName, argumentCount, receiverType));
            }
            return createStubTarget(context, receiverType, methodName, parameterTypes,
                    TYPE_CROSS_REPOSITORY, methodName, argumentCount, receiverType, GraphNodeIdentity.UNKNOWN_REPOSITORY);
        }

        try {
            var resolved = callExpr.resolve();
            String declaringType = resolved.declaringType().getQualifiedName();
            List<String> resolvedParameterTypes = new ArrayList<>();
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                resolvedParameterTypes.add(normalizeResolvedType(resolved.getParam(i).describeType()));
            }
            Optional<JavaMethod> targetMethod = findAcrossRepositories(context.searchRepositories(), declaringType, methodName, resolvedParameterTypes);
            if (targetMethod.isPresent()) {
                return Optional.of(resolvedTarget(targetMethod.get(),
                        targetMethod.get().getRepository().equals(context.repository()) ? TYPE_FIELD : TYPE_CROSS_REPOSITORY,
                        methodName, argumentCount, declaringType));
            }
            return createStubTarget(context, declaringType, methodName, resolvedParameterTypes,
                    TYPE_CROSS_REPOSITORY, methodName, argumentCount, declaringType, GraphNodeIdentity.UNKNOWN_REPOSITORY);
        } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
            log.debug("Unresolved method invocation: {}", callExpr, e);
        } catch (Exception e) {
            log.debug("Failed resolving method invocation: {}", callExpr, e);
        }

        return Optional.of(new ResolvedCallTarget(
                null,
                null,
                methodName,
                argumentCount,
                RESOLUTION_UNRESOLVED,
                TYPE_CROSS_REPOSITORY,
                null
        ));
    }

    private Optional<BlockStmt> getBody(CallableDeclaration<?> callableDeclaration) {
        if (callableDeclaration instanceof MethodDeclaration methodDeclaration) {
            return methodDeclaration.getBody();
        }
        if (callableDeclaration instanceof ConstructorDeclaration constructorDeclaration) {
            return Optional.of(constructorDeclaration.getBody());
        }
        return Optional.empty();
    }

    private Optional<ResolvedCallTarget> resolveDependencyCall(ClassCallContext context,
                                                               DeclaredDependency dependency,
                                                               String methodName,
                                                               List<String> parameterTypes,
                                                               int argumentCount) {
        List<JavaClass> interfaceCandidates = classRepository.findByFullyQualifiedNameInRepositories(
                dependency.typeName(), context.searchRepositories());
        boolean declaredAsInterface = interfaceCandidates.stream().anyMatch(candidate -> "INTERFACE".equals(candidate.getType()));
        if (declaredAsInterface) {
            List<JavaMethod> implementationMethods = classRepository.findImplementationsByFullyQualifiedName(dependency.typeName()).stream()
                    .filter(candidate -> context.searchRepositories().contains(candidate.getRepository()))
                    .flatMap(candidate -> methodRepository.findExactMethodInRepository(
                            candidate.getRepository(),
                            candidate.getFullyQualifiedName(),
                            methodName,
                            parameterTypes
                    ).stream())
                    .filter(candidate -> GraphNodeIdentity.REAL.equals(candidate.getNodeStatus()) || candidate.getNodeStatus() == null)
                    .toList();
            if (implementationMethods.size() > 1) {
                return createStubTarget(context, dependency.typeName(), methodName, parameterTypes,
                        TYPE_INTERFACE, methodName, argumentCount, dependency.typeName(), GraphNodeIdentity.UNKNOWN_REPOSITORY)
                        .map(target -> target.withStatus(RESOLUTION_AMBIGUOUS, TYPE_INTERFACE, dependency.typeName()));
            }
            return createStubTarget(context, dependency.typeName(), methodName, parameterTypes,
                    TYPE_INTERFACE, methodName, argumentCount, dependency.typeName(), GraphNodeIdentity.UNKNOWN_REPOSITORY);
        }

        Optional<JavaMethod> directMethod = findAcrossRepositories(context.searchRepositories(),
                dependency.typeName(), methodName, parameterTypes);
        if (directMethod.isPresent()) {
            return Optional.of(resolvedTarget(directMethod.get(), dependency.resolutionType(), methodName, argumentCount, dependency.typeName()));
        }

        return createStubTarget(context, dependency.typeName(), methodName, parameterTypes,
                dependency.resolutionType(), methodName, argumentCount, dependency.typeName(), GraphNodeIdentity.UNKNOWN_REPOSITORY);
    }

    private ResolvedCallTarget resolvedTarget(JavaMethod method, String resolutionType,
                                              String invocationName, int argumentCount, String declaredType) {
        return new ResolvedCallTarget(
                method.getMethodId(),
                method.getResolutionKey(),
                invocationName,
                argumentCount,
                GraphNodeIdentity.STUB.equals(method.getNodeStatus()) ? RESOLUTION_STUB : RESOLUTION_RESOLVED,
                resolutionType,
                declaredType
        );
    }

    private Optional<ResolvedCallTarget> createStubTarget(ClassCallContext context,
                                                          String className,
                                                          String methodName,
                                                          List<String> parameterTypes,
                                                          String resolutionType,
                                                          String invocationName,
                                                          int argumentCount,
                                                          String declaredType,
                                                          String repositoryHint) {
        if (className == null || className.isBlank() || dependencyConfig.isExternalPackage(className)) {
            return Optional.of(new ResolvedCallTarget(
                    null,
                    null,
                    invocationName,
                    argumentCount,
                    RESOLUTION_UNRESOLVED,
                    resolutionType,
                    declaredType
            ));
        }

        String stubRepository = repositoryHint == null || repositoryHint.isBlank()
                ? GraphNodeIdentity.UNKNOWN_REPOSITORY
                : repositoryHint;
        String resolutionKey = GraphNodeIdentity.methodResolutionKey(className, methodName, parameterTypes);
        String stubMethodId = GraphNodeIdentity.methodId(stubRepository, className, methodName, parameterTypes);
        String classId = GraphNodeIdentity.classId(stubRepository, className);
        String packageName = GraphNodeIdentity.packageName(className);
        String signature = methodName + "(" + GraphNodeIdentity.joinParameterTypes(parameterTypes) + ")";

        Map<String, Object> stubParams = new HashMap<>();
        stubParams.put("classId", classId);
        stubParams.put("className", className);
        stubParams.put("simpleName", GraphNodeIdentity.simpleName(className));
        stubParams.put("repository", stubRepository);
        stubParams.put("module", context.module());
        stubParams.put("packageName", packageName);
        stubParams.put("methodId", stubMethodId);
        stubParams.put("methodName", methodName);
        stubParams.put("signature", signature);
        stubParams.put("resolutionKey", resolutionKey);
        stubParams.put("parameterTypes", parameterTypes);

        neo4jClient.query("""
                MERGE (stubClass:Class {classId: $classId})
                ON CREATE SET stubClass.fullyQualifiedName = $className,
                              stubClass.simpleName = $simpleName,
                              stubClass.repository = $repository,
                              stubClass.module = $module,
                              stubClass.packageName = $packageName,
                              stubClass.nodeStatus = 'STUB',
                              stubClass.resolutionKey = $className
                ON MATCH SET stubClass.fullyQualifiedName = coalesce(stubClass.fullyQualifiedName, $className),
                             stubClass.simpleName = coalesce(stubClass.simpleName, $simpleName),
                             stubClass.repository = coalesce(stubClass.repository, $repository),
                             stubClass.module = coalesce(stubClass.module, $module),
                             stubClass.packageName = coalesce(stubClass.packageName, $packageName),
                             stubClass.resolutionKey = coalesce(stubClass.resolutionKey, $className),
                             stubClass.nodeStatus = CASE WHEN coalesce(stubClass.nodeStatus, 'REAL') = 'REAL' THEN 'REAL' ELSE 'STUB' END
                MERGE (stubMethod:Method {methodId: $methodId})
                ON CREATE SET stubMethod.name = $methodName,
                              stubMethod.signature = $signature,
                              stubMethod.fullyQualifiedSignature = $resolutionKey,
                              stubMethod.parameterTypes = $parameterTypes,
                              stubMethod.repository = $repository,
                              stubMethod.module = $module,
                              stubMethod.packageName = $packageName,
                              stubMethod.className = $className,
                              stubMethod.nodeStatus = 'STUB',
                              stubMethod.resolutionKey = $resolutionKey
                ON MATCH SET stubMethod.name = coalesce(stubMethod.name, $methodName),
                             stubMethod.signature = coalesce(stubMethod.signature, $signature),
                             stubMethod.fullyQualifiedSignature = coalesce(stubMethod.fullyQualifiedSignature, $resolutionKey),
                             stubMethod.parameterTypes = CASE
                                 WHEN stubMethod.parameterTypes IS NULL OR size(stubMethod.parameterTypes) = 0 THEN $parameterTypes
                                 ELSE stubMethod.parameterTypes
                             END,
                             stubMethod.repository = coalesce(stubMethod.repository, $repository),
                             stubMethod.module = coalesce(stubMethod.module, $module),
                             stubMethod.packageName = coalesce(stubMethod.packageName, $packageName),
                             stubMethod.className = coalesce(stubMethod.className, $className),
                             stubMethod.resolutionKey = coalesce(stubMethod.resolutionKey, $resolutionKey),
                             stubMethod.nodeStatus = CASE WHEN coalesce(stubMethod.nodeStatus, 'REAL') = 'REAL' THEN 'REAL' ELSE 'STUB' END
                MERGE (stubClass)-[:HAS_METHOD]->(stubMethod)
                """)
                .bindAll(stubParams)
                .run();

        return Optional.of(new ResolvedCallTarget(
                stubMethodId,
                resolutionKey,
                invocationName,
                argumentCount,
                RESOLUTION_STUB,
                resolutionType,
                declaredType
        ));
    }

    private String tryResolveStaticScope(Expression scope, ClassCallContext context, Map<String, String> visibleTypes) {
        String scopeText = scope.toString();
        if (visibleTypes.containsKey(scopeText) || context.fieldTypes().containsKey(scopeText)) {
            return null;
        }
        if (scope.isNameExpr() || scope.isFieldAccessExpr()) {
            if (scopeText.contains(".")) {
                return normalizeTypeName(scopeText, context.importMap(), context.packageName());
            }
            if (!scopeText.isEmpty() && Character.isUpperCase(scopeText.charAt(0))) {
                return normalizeTypeName(scopeText, context.importMap(), context.packageName());
            }
        }
        return null;
    }

    private String tryResolveReceiverType(Expression scope, ClassCallContext context, Map<String, String> visibleTypes) {
        try {
            return normalizeResolvedType(scope.calculateResolvedType().describe());
        } catch (Exception ignored) {
            return visibleTypes.get(scope.toString());
        }
    }

    private Optional<JavaMethod> resolveWithinHierarchy(String className, String methodName,
                                                        List<String> parameterTypes, List<String> repositories,
                                                        Set<String> visitedClasses) {
        if (className == null || !visitedClasses.add(className)) {
            return Optional.empty();
        }
        Optional<JavaMethod> exact = findAcrossRepositories(repositories, className, methodName, parameterTypes);
        if (exact.isPresent()) {
            return exact;
        }
        for (JavaClass candidate : classRepository.findByFullyQualifiedName(className, 20)) {
            if (!repositories.contains(candidate.getRepository()) || candidate.getExtendedClasses() == null) {
                continue;
            }
            for (JavaClass parent : candidate.getExtendedClasses()) {
                Optional<JavaMethod> inherited = resolveWithinHierarchy(parent.getFullyQualifiedName(), methodName,
                        parameterTypes, repositories, visitedClasses);
                if (inherited.isPresent()) {
                    return inherited;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<JavaMethod> findAcrossRepositories(List<String> repositories, String className,
                                                        String methodName, List<String> parameterTypes) {
        return methodRepository.findExactMethodAcrossRepositories(repositories, className, methodName, parameterTypes)
                .stream()
                .filter(method -> !GraphNodeIdentity.STUB.equals(method.getNodeStatus()))
                .findFirst();
    }

    private Optional<JavaMethod> findExactMethod(String repository, String className,
                                                 String methodName, List<String> parameterTypes) {
        return methodRepository.findExactMethodInRepository(repository, className, methodName, parameterTypes)
                .stream()
                .filter(method -> !GraphNodeIdentity.STUB.equals(method.getNodeStatus()))
                .findFirst();
    }

    private List<String> resolveInvocationParameterTypes(MethodCallExpr callExpr, ClassCallContext context,
                                                         Map<String, String> visibleTypes) {
        try {
            var resolved = callExpr.resolve();
            List<String> parameterTypes = new ArrayList<>();
            for (int i = 0; i < resolved.getNumberOfParams(); i++) {
                parameterTypes.add(normalizeResolvedType(resolved.getParam(i).describeType()));
            }
            return parameterTypes;
        } catch (Exception ignored) {
        }

        List<String> parameterTypes = new ArrayList<>();
        callExpr.getArguments().forEach(argument -> parameterTypes.add(inferArgumentType(argument, context, visibleTypes)));
        return parameterTypes;
    }

    private String inferArgumentType(Expression argument, ClassCallContext context, Map<String, String> visibleTypes) {
        try {
            return normalizeResolvedType(argument.calculateResolvedType().describe());
        } catch (Exception ignored) {
        }

        if (argument.isStringLiteralExpr()) return "java.lang.String";
        if (argument.isBooleanLiteralExpr()) return "boolean";
        if (argument.isCharLiteralExpr()) return "char";
        if (argument.isIntegerLiteralExpr()) return "int";
        if (argument.isLongLiteralExpr()) return "long";
        if (argument.isDoubleLiteralExpr()) return "double";
        if (argument.isObjectCreationExpr()) {
            return normalizeTypeName(argument.asObjectCreationExpr().getType().asString(), context.importMap(), context.packageName());
        }
        if (argument.isNameExpr()) {
            String visibleType = visibleTypes.get(argument.asNameExpr().getNameAsString());
            if (visibleType != null) {
                return visibleType;
            }
        }
        if (argument.isFieldAccessExpr()) {
            String fieldName = argument.asFieldAccessExpr().getNameAsString();
            DeclaredDependency dependency = context.fieldTypes().get(fieldName);
            if (dependency != null) {
                return dependency.typeName();
            }
        }
        return "unknown";
    }

    private void deleteCallsForMethod(String sourceMethodId) {
        neo4jClient.query("MATCH (:Method {methodId: $sourceMethodId})-[r:CALLS]->(:Method) DELETE r")
                .bind(sourceMethodId).to("sourceMethodId")
                .run();
    }

    private void upsertCallRelationship(CallEdgeAccumulator edge) {
        Map<String, Object> callParams = new HashMap<>();
        callParams.put("sourceMethodId", edge.sourceMethodId());
        callParams.put("targetMethodId", edge.target().targetMethodId());
        callParams.put("relationshipKey", edge.sourceMethodId() + "->" + edge.target().targetMethodId());
        callParams.put("invocationName", edge.target().invocationName());
        callParams.put("argumentCount", edge.target().argumentCount());
        callParams.put("line", edge.firstLine());
        callParams.put("column", edge.firstColumn());
        callParams.put("resolutionStatus", edge.target().resolutionStatus());
        callParams.put("resolutionType", edge.target().resolutionType());
        callParams.put("invocationCount", edge.invocationCount());
        callParams.put("callSites", edge.callSites());
        callParams.put("declaredType", edge.target().declaredType() == null ? "" : edge.target().declaredType());

        neo4jClient.query("""
                MATCH (source:Method {methodId: $sourceMethodId}), (target:Method {methodId: $targetMethodId})
                MERGE (source)-[r:CALLS]->(target)
                SET r.relationshipKey = $relationshipKey,
                    r.invocationName = $invocationName,
                    r.argumentCount = $argumentCount,
                    r.line = $line,
                    r.column = $column,
                    r.resolutionStatus = $resolutionStatus,
                    r.resolutionType = $resolutionType,
                    r.invocationCount = $invocationCount,
                    r.callSites = $callSites,
                    r.declaredType = $declaredType
                """)
                .bindAll(callParams)
                .run();
    }

    private String extractAssignedFieldName(Expression expression) {
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                return fieldAccessExpr.getNameAsString();
            }
        }
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString();
        }
        return null;
    }

    private String normalizeClassOrInterfaceType(ClassOrInterfaceType type, CompilationUnit cu, String packageName) {
        try {
            return normalizeResolvedType(type.resolve().describe());
        } catch (Exception ignored) {
            return normalizeTypeName(type.asString(), buildImportMap(cu), packageName);
        }
    }

    private String normalizeDeclaredType(Type type, CompilationUnit cu, String packageName) {
        try {
            return normalizeResolvedType(type.resolve().describe());
        } catch (Exception ignored) {
            return normalizeTypeName(type.asString(), buildImportMap(cu), packageName);
        }
    }

    private String normalizeResolvedType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "unknown";
        }
        String normalized = typeName.trim();
        if (normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - 3) + "[]";
        }
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart) + normalized.substring(normalized.lastIndexOf('>') + 1);
        }
        return normalized;
    }

    private String normalizeTypeName(String typeName, Map<String, String> importMap, String packageName) {
        if (typeName == null || typeName.isBlank()) {
            return "unknown";
        }
        String trimmed = typeName.trim();
        String arraySuffix = "";
        while (trimmed.endsWith("[]")) {
            arraySuffix += "[]";
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        if (trimmed.endsWith("...")) {
            arraySuffix += "[]";
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart);
        }
        if (trimmed.isBlank() || PRIMITIVES_AND_BUILTINS.contains(trimmed) || isJavaLangClass(trimmed)) {
            return trimmed + arraySuffix;
        }
        if (trimmed.contains(".")) {
            return trimmed + arraySuffix;
        }
        String resolved = importMap.get(trimmed);
        if (resolved != null) {
            return resolved + arraySuffix;
        }
        return ((packageName == null || "(default)".equals(packageName)) ? trimmed : packageName + "." + trimmed) + arraySuffix;
    }

    /** Derives the source root (e.g. src/main/java) from a Java file path. */
    private Path detectSourceRoot(Path filePath) {
        String p = filePath.toString();
        for (String marker : List.of("/src/main/java/", "/src/test/java/", "/src/main/kotlin/")) {
            int idx = p.indexOf(marker);
            if (idx >= 0) return Path.of(p.substring(0, idx + marker.length() - 1));
        }
        return filePath.getParent();
    }

    /**
     * Check if a method has a test annotation.
     */
    private boolean isTestMethod(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(name -> TEST_ANNOTATIONS.contains(name) || 
                                  name.equals("org.junit.Test") || 
                                  name.equals("org.junit.jupiter.api.Test"));
    }

    /**
     * Establish TESTED_BY relationships for a single file immediately after ingestion.
     * This is more incremental and consistent with the hybrid ingestion pattern.
     * Only processes test files to avoid overhead on production code.
     */
    @Transactional
    public int establishTestRelationshipsForFile(JavaFile javaFile, String repository) {
        log.info("establishTestRelationshipsForFile called: file={}, classes={}", 
                javaFile.getPath(), javaFile.getClasses().size());
        
        // Skip if not a test file
        if (!javaFile.getPath().contains("/test/") && 
            !javaFile.getPath().contains("\\test\\")) {
            log.debug("Skipping non-test file: {}", javaFile.getPath());
            return 0;
        }

        int relationshipsCreated = 0;

        for (JavaClass testClass : javaFile.getClasses()) {
            String testClassName = testClass.getSimpleName();
            
            log.info("Examining class: {}, methods: {}", testClassName, testClass.getMethods().size());
            
            // Skip if not a test class
            if (!testClassName.endsWith("Test") && 
                !testClassName.endsWith("Tests") && 
                !testClassName.endsWith("IT")) {
                log.debug("Skipping non-test class: {}", testClassName);
                continue;
            }

            // Infer the tested class name
            String testedClassName = testClassName
                    .replaceAll("Tests?$", "")  // Remove Test or Tests suffix
                    .replaceAll("IT$", "");      // Remove IT suffix (integration tests)
            
            log.info("Test class: {}, inferred tested class: {}", testClassName, testedClassName);
            
            if (testedClassName.isEmpty() || testedClassName.equals(testClassName)) {
                log.warn("Cannot infer tested class from: {}", testClassName);
                continue; // Can't infer tested class
            }
            
            // Find the tested class anywhere in the repository (not just same package)
            String testPackage = testClass.getPackageName();
            
            log.info("Searching for tested class: name={}, testPackage={}", 
                    testedClassName, testPackage);
            
            List<JavaClass> candidateClasses = classRepository
                    .findByRepositoryAndSimpleName(repository, testedClassName)
                    .stream()
                    .filter(c -> c.getFilePath() != null && !c.getFilePath().contains("/test/") && !c.getFilePath().contains("\\test\\"))
                    .toList();
            
            log.info("Found {} candidate tested classes for {}", candidateClasses.size(), testedClassName);
            
            if (candidateClasses.isEmpty()) {
                log.debug("No tested class found for test class: {}", testClassName);
                continue;
            }
            
            // Prefer classes in similar packages, but accept any if only one candidate
            JavaClass testedClass;
            if (candidateClasses.size() == 1) {
                testedClass = candidateClasses.get(0);
            } else {
                // Multiple candidates - prefer same or similar package
                String testedPackage = testPackage
                        .replaceAll("\\.tests?\\.", ".")  // middle: com.example.test.service → com.example.service
                        .replaceAll("\\.tests?$", "");     // end: com.example.test → com.example
                
                testedClass = candidateClasses.stream()
                        .filter(c -> c.getPackageName() != null && 
                                    (c.getPackageName().equals(testedPackage) || 
                                     c.getPackageName().equals(testPackage)))
                        .findFirst()
                        .orElse(candidateClasses.get(0)); // fallback to first if no package match
            }
            
            log.info("Processing test class: {}, testedClass: {}, testMethods: {}", 
                    testClassName, testedClass.getFullyQualifiedName(), testClass.getMethods().size());
            
            // Process each test method
            for (JavaMethod testMethod : testClass.getMethods()) {
                if (!isTestMethodByName(testMethod)) {
                    log.debug("Skipping non-test method: {}", testMethod.getName());
                    continue;
                }
                
                log.info("Found test method: {}", testMethod.getMethodId());
                
                // Try to infer which method is being tested
                Set<JavaMethod> testedMethods = inferTestedMethods(testMethod, testedClass);
                
                if (!testedMethods.isEmpty()) {
                    for (JavaMethod testedMethod : testedMethods) {
                        // Create relationship using Cypher query to ensure it's persisted
                        log.info("Creating TESTED_BY: test={}, tested={}", 
                                testMethod.getMethodId(), testedMethod.getMethodId());
                        try {
                            methodRepository.createTestedByRelationship(testMethod.getMethodId(), testedMethod.getMethodId());
                            relationshipsCreated++;
                            log.info("Created TESTED_BY: {} <- {}", 
                                    testedMethod.getFullyQualifiedSignature(), 
                                    testMethod.getFullyQualifiedSignature());
                        } catch (Exception e) {
                            log.error("Failed to create TESTED_BY relationship: test={}, tested={}", 
                                    testMethod.getMethodId(), testedMethod.getMethodId(), e);
                        }
                    }
                } else {
                    log.debug("No tested methods inferred for test method: {}", testMethod.getMethodId());
                }
            }
        }
        
        if (relationshipsCreated > 0) {
            log.info("Established {} TESTED_BY relationships for file: {}", 
                    relationshipsCreated, javaFile.getPath());
        }
        return relationshipsCreated;
    }

    /**
     * Establish TESTED_BY relationships for all test methods in a repository.
     * This is called after all files have been ingested to ensure all methods exist.
     */
    @Transactional
    public int establishTestRelationships(String repository, String module) {
        log.info("Establishing TESTED_BY relationships for repository={}, module={}", repository, module);
        
        int relationshipsCreated = 0;
        
        // Find all test classes (classes ending with Test or Tests)
        List<JavaClass> testClasses = classRepository.findByRepositoryAndModule(repository, module)
                .stream()
                .filter(c -> c.getSimpleName().endsWith("Test") || 
                            c.getSimpleName().endsWith("Tests") ||
                            c.getFilePath().contains("/test/"))
                .toList();
        
        for (JavaClass testClass : testClasses) {
            String testClassName = testClass.getSimpleName();
            
            // Infer the tested class name
            // e.g., UserServiceTest -> UserService, UserServiceTests -> UserService
            String testedClassName = testClassName
                    .replaceAll("Tests?$", "")  // Remove Test or Tests suffix
                    .replaceAll("IT$", "");      // Remove IT suffix (integration tests)
            
            if (testedClassName.isEmpty() || testedClassName.equals(testClassName)) {
                continue; // Can't infer tested class
            }
            
            // Find the tested class in the same package or related packages
            String testPackage = testClass.getPackageName();
            
            // Look for tested class anywhere in repository (not just same package)
            List<JavaClass> candidateClasses = classRepository
                    .findByRepositoryAndSimpleName(repository, testedClassName)
                    .stream()
                    .filter(c -> c.getFilePath() != null && !c.getFilePath().contains("/test/") && !c.getFilePath().contains("\\test\\"))
                    .toList();
            
            if (candidateClasses.isEmpty()) {
                log.debug("No tested class found for test class: {}", testClassName);
                continue;
            }
            
            // Prefer classes in similar packages, but accept any if only one candidate
            JavaClass testedClass;
            if (candidateClasses.size() == 1) {
                testedClass = candidateClasses.get(0);
            } else {
                // Multiple candidates - prefer same or similar package
                String testedPackage = testPackage
                        .replaceAll("\\.tests?\\.", ".")  // middle: com.example.test.service → com.example.service
                        .replaceAll("\\.tests?$", "");     // end: com.example.test → com.example
                
                testedClass = candidateClasses.stream()
                        .filter(c -> c.getPackageName() != null && 
                                    (c.getPackageName().equals(testedPackage) || 
                                     c.getPackageName().equals(testPackage)))
                        .findFirst()
                        .orElse(candidateClasses.get(0)); // fallback to first if no package match
            }
            
            // Process each test method
            for (JavaMethod testMethod : testClass.getMethods()) {
                if (!isTestMethodByName(testMethod)) {
                    continue;
                }
                
                // Try to infer which method is being tested
                Set<JavaMethod> testedMethods = inferTestedMethods(testMethod, testedClass);
                
                if (!testedMethods.isEmpty()) {
                    // Add TESTED_BY relationship (from test method to tested method)
                    for (JavaMethod testedMethod : testedMethods) {
                        // Create relationship using Cypher query to ensure it's persisted
                        methodRepository.createTestedByRelationship(testMethod.getMethodId(), testedMethod.getMethodId());
                        relationshipsCreated++;
                        log.debug("Created TESTED_BY: {} <- {}", 
                                testedMethod.getFullyQualifiedSignature(), 
                                testMethod.getFullyQualifiedSignature());
                    }
                }
            }
        }
        
        log.info("Established {} TESTED_BY relationships for repository={}, module={}", 
                relationshipsCreated, repository, module);
        return relationshipsCreated;
    }
    
    /**
     * Check if a method is a test method based on its name and annotations.
     */
    private boolean isTestMethodByName(JavaMethod method) {
        String methodName = method.getName();
        // Common test method naming patterns
        return methodName.startsWith("test") || 
               methodName.startsWith("should") ||
               methodName.endsWith("Test") ||
               methodName.contains("_should_") ||
               method.getSpringAnnotations().stream()
                       .anyMatch(a -> a.contains("Test"));
    }
    
    /**
     * Infer which production methods are being tested by a test method.
     * Uses naming conventions and method call analysis.
     */
    private Set<JavaMethod> inferTestedMethods(JavaMethod testMethod, JavaClass testedClass) {
        Set<JavaMethod> testedMethods = new HashSet<>();
        String testMethodName = testMethod.getName();
        
        // Strategy 1: Direct name matching
        // test_methodName_scenario -> methodName
        // testMethodName -> methodName
        // shouldMethodName -> methodName
        String inferredMethodName = extractTestedMethodName(testMethodName);
        
        if (inferredMethodName != null) {
            // Find methods in tested class with matching name
            testedMethods.addAll(testedClass.getMethods().stream()
                    .filter(m -> m.getName().equalsIgnoreCase(inferredMethodName))
                    .toList());
        }
        
        // Strategy 2: If test method calls methods from the tested class, 
        // those are likely the tested methods
        testedMethods.addAll(methodRepository.findCalledMethods(testMethod.getMethodId()).stream()
                .filter(m -> m.getClassName().equals(testedClass.getFullyQualifiedName()))
                .toList());
        
        // If we still haven't found anything and the test class only tests one method,
        // assume all test methods test all public methods of the tested class
        if (testedMethods.isEmpty() && testedClass.getMethods().size() <= 3) {
            testedMethods.addAll(testedClass.getMethods().stream()
                    .filter(m -> !m.getName().startsWith("get") && 
                                !m.getName().startsWith("set") &&
                                !m.getName().equals("toString") &&
                                !m.getName().equals("hashCode") &&
                                !m.getName().equals("equals"))
                    .toList());
        }
        
        return testedMethods;
    }
    
    /**
     * Extract the tested method name from a test method name.
     * Examples:
     *   testAuthenticate -> authenticate
     *   test_authenticate_withValidCredentials -> authenticate
     *   shouldAuthenticateUser -> authenticate (or authenticateUser)
     *   authenticate_shouldReturnToken -> authenticate
     */
    private String extractTestedMethodName(String testMethodName) {
        // Pattern: test_methodName_...
        if (testMethodName.startsWith("test_")) {
            String remainder = testMethodName.substring(5);
            int underscoreIdx = remainder.indexOf('_');
            return underscoreIdx > 0 ? remainder.substring(0, underscoreIdx) : remainder;
        }
        
        // Pattern: testMethodName or testMethodNameScenario
        if (testMethodName.startsWith("test")) {
            String remainder = testMethodName.substring(4);
            if (!remainder.isEmpty()) {
                // Convert first char to lowercase
                return Character.toLowerCase(remainder.charAt(0)) + remainder.substring(1);
            }
        }
        
        // Pattern: shouldMethodName or should_methodName
        if (testMethodName.startsWith("should_")) {
            String remainder = testMethodName.substring(7);
            int underscoreIdx = remainder.indexOf('_');
            return underscoreIdx > 0 ? remainder.substring(0, underscoreIdx) : remainder;
        }
        
        if (testMethodName.startsWith("should")) {
            String remainder = testMethodName.substring(6);
            if (!remainder.isEmpty()) {
                // Try to extract verb: shouldAuthenticateUser -> authenticate
                // Convert to lowercase and look for verb patterns
                String lowercase = remainder.toLowerCase();
                // Simple heuristic: take first word
                int idx = 1;
                while (idx < remainder.length() && Character.isLowerCase(remainder.charAt(idx))) {
                    idx++;
                }
                return Character.toLowerCase(remainder.charAt(0)) + remainder.substring(1, idx);
            }
        }
        
        // Pattern: methodName_should...
        int shouldIdx = testMethodName.indexOf("_should");
        if (shouldIdx > 0) {
            return testMethodName.substring(0, shouldIdx);
        }
        
        return null;
    }

    /**
     * Extract Spring annotations from a node.
     */
    private Set<String> extractSpringAnnotations(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .filter(SPRING_ANNOTATIONS::contains)
                .map(name -> "@" + name)
                .collect(Collectors.toSet());
    }

    /**
     * Extract API information from Spring REST annotations.
     */
    private ApiInfo extractApiInfo(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();

            String httpMethod = switch (name) {
                case "GetMapping" -> "GET";
                case "PostMapping" -> "POST";
                case "PutMapping" -> "PUT";
                case "DeleteMapping" -> "DELETE";
                case "PatchMapping" -> "PATCH";
                case "RequestMapping" -> extractRequestMappingMethod(annotation);
                default -> null;
            };

            if (httpMethod != null) {
                String path = extractAnnotationValue(annotation);
                return new ApiInfo(httpMethod, path);
            }
        }

        return null;
    }

    /**
     * Extract HTTP method from @RequestMapping annotation.
     */
    private String extractRequestMappingMethod(AnnotationExpr annotation) {
        // Simplified - in production, parse the method attribute
        return "GET"; // Default
    }

    /**
     * Extract value from annotation (simplified).
     */
    private String extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            String value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
            return value.replaceAll("^\"|\"$", "");
        }
        return "";
    }

    private boolean isJavaLangClass(String simpleName) {
        Set<String> javaLangClasses = Set.of("String", "Integer", "Long", "Boolean", "Double",
                "Float", "Character", "Byte", "Short", "Object", "Class", "System");
        return javaLangClasses.contains(simpleName);
    }

    private String extractRelativePath(Path absolutePath, String repository) {
        String pathStr = absolutePath.toString();
        // Try to find src/main/java or src/test/java
        int srcIndex = pathStr.indexOf("src/main/java");
        if (srcIndex < 0) {
            srcIndex = pathStr.indexOf("src/test/java");
        }
        if (srcIndex >= 0) {
            return pathStr.substring(srcIndex);
        }
        return absolutePath.getFileName().toString();
    }

    /**
     * Normalize a configured/walked ingestion root back to the true repository root,
     * i.e. the directory that directly contains "src/main/java" or "src/test/java".
     * Stored file paths (see {@link #extractRelativePath}) are always relative to this
     * true root. If the given root already points inside (or at) a src/main/java tree
     * (e.g. auto-ingest configured with a deep sub-package as its root), strip that
     * suffix so paths aren't resolved twice against the same segment.
     */
    private Path resolveRepoRoot(Path rootPath) {
        String pathStr = rootPath.toString().replace('\\', '/');
        for (String marker : new String[]{"/src/main/java", "/src/test/java"}) {
            int idx = pathStr.indexOf(marker);
            if (idx >= 0) {
                return Path.of(pathStr.substring(0, idx));
            }
        }
        return rootPath;
    }

    // ── ID Generation (stable, unique identifiers) ───────────────────────────

    private String generateFileId(String repository, String filePath) {
        return "file:" + repository + ":" + filePath;
    }

    private String generateClassId(String repository, String fullyQualifiedName) {
        return GraphNodeIdentity.classId(repository, fullyQualifiedName);
    }

    private String generateFieldId(String repository, String className, String fieldName) {
        return GraphNodeIdentity.fieldId(repository, className, fieldName);
    }

    private String generatePackageId(String repository, String packageName) {
        return "package:" + repository + ":" + packageName;
    }

    private String generateModuleId(String repository, String moduleName) {
        return "module:" + repository + ":" + moduleName;
    }

    private String generateRepoId(String repository) {
        return "repo:" + repository;
    }

    // ── Hierarchy helpers (Repository → Module → Package → File) ─────────────

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        if (cu == null) return Map.of();
        return cu.getImports().stream()
                .filter(imp -> !imp.isAsterisk() && !imp.isStatic())
                .collect(Collectors.toMap(
                        imp -> imp.getName().getIdentifier(),
                        imp -> imp.getNameAsString(),
                        (a, b) -> a));
    }

    /**
     * Resolves a simple or qualified type name to a JavaClass node (find or create stub).
     * Returns empty for primitives, void, and java/javax builtins.
     */
    private Optional<JavaClass> findOrCreateClassRef(String typeName, CompilationUnit cu,
                                                      String packageName, String repository) {
        String fqn = normalizeTypeName(typeName, buildImportMap(cu), packageName);
        String stripped = stripGenerics(fqn);
        if (stripped.isBlank() || stripped.equals("void") || stripped.equals("?")
                || PRIMITIVES_AND_BUILTINS.contains(stripped) || dependencyConfig.isExternalPackage(stripped)) {
            return Optional.empty();
        }
        try {
            String classId = generateClassId(repository, stripped);
            
            // Synchronize to prevent concurrent creation of the same class stub
            synchronized (getClassLock(classId)) {
                Optional<JavaClass> existing = classRepository.findByClassId(classId);
                if (existing.isPresent()) return existing;
                JavaClass stub = new JavaClass();
                stub.setClassId(classId);
                stub.setFullyQualifiedName(stripped);
                stub.setSimpleName(GraphNodeIdentity.simpleName(stripped));
                stub.setRepository(repository);
                stub.setPackageName(GraphNodeIdentity.packageName(stripped));
                stub.setNodeStatus(GraphNodeIdentity.STUB);
                stub.setResolutionKey(stripped);
                return Optional.of(classRepository.save(stub));
            }
        } catch (Exception e) {
            log.debug("Could not resolve/create class ref: {}", stripped, e);
            return Optional.empty();
        }
    }

    private void ensureHierarchy(String repoName, String moduleName, String packageName,
                                  String branch, String commitSha, JavaFile javaFile) {
        // Synchronize on hierarchy to prevent concurrent access deadlocks
        synchronized (getHierarchyLock(repoName, moduleName, packageName)) {
            try {
                // CodeRepository
                String repoId = generateRepoId(repoName);
                CodeRepository repo = repositoryRepository.findByRepoId(repoId).orElseGet(() -> {
                    CodeRepository r = new CodeRepository();
                    r.setRepoId(repoId);
                    r.setName(repoName);
                    r.setDefaultBranch(branch);
                    return r;
                });
                repo.setCurrentCommit(commitSha);
                repo.setLastIndexedTimestamp(System.currentTimeMillis());

                // Module
                String moduleId = generateModuleId(repoName, moduleName);
                Module mod = moduleRepository.findByModuleId(moduleId).orElseGet(() -> {
                    Module m = new Module();
                    m.setModuleId(moduleId);
                    m.setName(moduleName);
                    m.setRepository(repoName);
                    return m;
                });
                mod = moduleRepository.save(mod);

                // JavaPackage
                String pkgId = generatePackageId(repoName, packageName);
                JavaPackage pkg = packageRepository.findByPackageId(pkgId).orElseGet(() -> {
                    JavaPackage p = new JavaPackage();
                    p.setPackageId(pkgId);
                    p.setFullyQualifiedName(packageName);
                    p.setRepository(repoName);
                    p.setModule(moduleName);
                    return p;
                });
                pkg.getFiles().add(javaFile);
                pkg = packageRepository.save(pkg);

                mod.getPackages().add(pkg);
                mod = moduleRepository.save(mod);

                repo.getModules().add(mod);
                repositoryRepository.save(repo);
            } catch (Exception e) {
                log.warn("Could not update repository hierarchy for {}/{}/{}: {}", repoName, moduleName, packageName, e.getMessage());
            }
        }
    }

    private String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Result classes ──────────────────────────────────────────────────────

    public static class IngestionStats {
        private int filesProcessed;
        private int classesCreated;
        private int methodsCreated;
        private int fieldsCreated;
        private int testRelationships;
        private final boolean skipped;

        public IngestionStats(int filesProcessed, int classesCreated, int methodsCreated,
                              int fieldsCreated, boolean skipped) {
            this.filesProcessed = filesProcessed;
            this.classesCreated = classesCreated;
            this.methodsCreated = methodsCreated;
            this.fieldsCreated = fieldsCreated;
            this.testRelationships = 0;
            this.skipped = skipped;
        }

        public void incrementFiles() { filesProcessed++; }
        public void incrementClasses() { classesCreated++; }
        public void addMethods(int count) { methodsCreated += count; }
        public void addFields(int count) { fieldsCreated += count; }
        public void setTestRelationships(int count) { testRelationships = count; }

        public int filesProcessed() { return filesProcessed; }
        public int classesCreated() { return classesCreated; }
        public int methodsCreated() { return methodsCreated; }
        public int fieldsCreated() { return fieldsCreated; }
        public int testRelationships() { return testRelationships; }
        public boolean skipped() { return skipped; }

        @Override
        public String toString() {
            if (skipped) {
                return "skipped (unchanged)";
            }
            return String.format("files=%d, classes=%d, methods=%d, fields=%d, testRelationships=%d",
                    filesProcessed, classesCreated, methodsCreated, fieldsCreated, testRelationships);
        }
    }

    public static class BatchIngestionStats {
        private int totalFiles = 0;
        private int totalClasses = 0;
        private int totalMethods = 0;
        private int totalFields = 0;
        private int skippedFiles = 0;
        private int errors = 0;
        private int testRelationships = 0;

        public void add(IngestionStats stats) {
            if (stats.skipped()) {
                skippedFiles++;
            } else {
                totalFiles += stats.filesProcessed();
                totalClasses += stats.classesCreated();
                totalMethods += stats.methodsCreated();
                totalFields += stats.fieldsCreated();
                testRelationships += stats.testRelationships();
            }
        }

        public void incrementErrors() {
            errors++;
        }

        public void setTestRelationships(int testRelationships) {
            this.testRelationships = testRelationships;
        }

        @Override
        public String toString() {
            return String.format("files=%d, classes=%d, methods=%d, fields=%d, skipped=%d, errors=%d, testRelationships=%d",
                    totalFiles, totalClasses, totalMethods, totalFields, skippedFiles, errors, testRelationships);
        }

        public int getTotalFiles() {
            return totalFiles;
        }

        public int getTotalClasses() {
            return totalClasses;
        }

        public int getTotalMethods() {
            return totalMethods;
        }

        public int getTotalFields() {
            return totalFields;
        }

        public int getSkippedFiles() {
            return skippedFiles;
        }

        public int getErrors() {
            return errors;
        }

        public int getTestRelationships() {
            return testRelationships;
        }
    }

    private record MethodWorkItem(JavaMethod method, CallableDeclaration<?> callableDeclaration) {
    }

    private record ClassCallContext(JavaClass owningClass,
                                    String repository,
                                    String module,
                                    String packageName,
                                    CompilationUnit compilationUnit,
                                    Map<String, String> importMap,
                                    Map<String, DeclaredDependency> fieldTypes,
                                    Map<String, JavaMethod> sameClassMethods,
                                    List<String> searchRepositories,
                                    List<String> superTypes) {
    }

    private record DeclaredDependency(String fieldName, String typeName, String resolutionType) {
    }

    private record ResolvedCallTarget(String targetMethodId,
                                      String targetResolutionKey,
                                      String invocationName,
                                      int argumentCount,
                                      String resolutionStatus,
                                      String resolutionType,
                                      String declaredType) {
        private ResolvedCallTarget withStatus(String status, String type, String declaredType) {
            return new ResolvedCallTarget(
                    targetMethodId,
                    targetResolutionKey,
                    invocationName,
                    argumentCount,
                    status,
                    type,
                    declaredType
            );
        }
    }

    private static final class CallEdgeAccumulator {
        private final String sourceMethodId;
        private final ResolvedCallTarget target;
        private final List<Integer> callSites = new ArrayList<>();
        private int firstLine = 0;
        private int firstColumn = 0;
        private int invocationCount = 0;

        private CallEdgeAccumulator(String sourceMethodId, ResolvedCallTarget target) {
            this.sourceMethodId = sourceMethodId;
            this.target = target;
        }

        private void addSite(int line, int column) {
            invocationCount++;
            if (firstLine == 0 || (line > 0 && line < firstLine) || (line == firstLine && column < firstColumn)) {
                firstLine = line;
                firstColumn = column;
            }
            if (line > 0 && !callSites.contains(line)) {
                callSites.add(line);
                callSites.sort(Integer::compareTo);
            }
        }

        private String sourceMethodId() {
            return sourceMethodId;
        }

        private ResolvedCallTarget target() {
            return target;
        }

        private int firstLine() {
            return firstLine;
        }

        private int firstColumn() {
            return firstColumn;
        }

        private int invocationCount() {
            return invocationCount;
        }

        private List<Integer> callSites() {
            return callSites;
        }
    }

    private record ApiInfo(String httpMethod, String path) {
    }

    /**
     * Delete all Neo4j nodes associated with a file.
     * Used for rollback when Qdrant ingestion fails.
     */
    @Transactional
    public DeletionStats deleteFile(Path sourcePath, String repository, String module) {
        log.info("Deleting Neo4j nodes for file: {}, repo={}, module={}", sourcePath, repository, module);
        
        String filePath = extractRelativePath(sourcePath, repository);
        
        // Find all classes in this file
        List<JavaClass> classes = classRepository.findAll().stream()
                .filter(c -> c.getRepository().equals(repository) 
                          && c.getModule().equals(module) 
                          && c.getFilePath().equals(filePath))
                .toList();
        
        int deletedClasses = 0;
        int deletedMethods = 0;
        int deletedFields = 0;
        
        for (JavaClass javaClass : classes) {
            // Delete all methods in the class
            List<JavaMethod> methods = methodRepository.findAll().stream()
                    .filter(m -> m.getClassName().equals(javaClass.getFullyQualifiedName()))
                    .toList();
            
            for (JavaMethod method : methods) {
                methodRepository.delete(method);
                deletedMethods++;
            }
            
            // Count fields (they're deleted cascade with class)
            if (javaClass.getFields() != null) {
                deletedFields += javaClass.getFields().size();
            }
            
            // Delete the class
            classRepository.delete(javaClass);
            deletedClasses++;
        }
        
        // Delete the file node
        List<JavaFile> files = fileRepository.findAll().stream()
                .filter(f -> f.getRepository().equals(repository)
                          && f.getModule().equals(module)
                          && f.getPath().equals(filePath))
                .toList();
        
        for (JavaFile file : files) {
            fileRepository.delete(file);
        }
        
        log.info("Deleted Neo4j nodes: classes={}, methods={}, fields={}, file={}",
                deletedClasses, deletedMethods, deletedFields, sourcePath);
        
        return new DeletionStats(deletedClasses, deletedMethods, deletedFields);
    }

    /**
     * List all files indexed in Neo4j for a given repository and module.
     * Uses pagination to avoid loading all files into memory at once.
     */
    public List<String> listAllFiles(String repository, String module) {
        List<String> allFiles = new ArrayList<>();
        int batchSize = 1000;
        int skip = 0;
        
        while (true) {
            List<String> batch = fileRepository.findFilePathsPaginated(repository, module, skip, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            allFiles.addAll(batch);
            skip += batchSize;
            
            // Log progress for large repositories
            if (skip % 10000 == 0) {
                log.info("Fetched {} file paths from Neo4j...", skip);
            }
        }
        
        log.info("Total files fetched from Neo4j: {}", allFiles.size());
        return allFiles;
    }

    /**
     * Reconcile deleted files - remove Neo4j nodes for files that no longer exist on disk.
     * Processes files in batches to avoid heap space issues.
     */
    @Transactional
    public ReconciliationStats reconcileDeletedFiles(Path rootPath, String repository, String module) {
        log.info("Starting Neo4j reconciliation: root={}, repo={}, module={}", rootPath, repository, module);

        // Stored file paths are repo-root-relative (e.g. "src/main/java/..."), as produced by
        // extractRelativePath(). If rootPath itself points inside a src/main/java (or src/test/java)
        // tree (e.g. auto-ingest was configured with a deep sub-package as its root), resolving the
        // stored path directly against rootPath would duplicate the "src/main/java/..." segment.
        // Normalize rootPath back to the true repository root before resolving.
        Path repoRoot = resolveRepoRoot(rootPath);
        if (!repoRoot.equals(rootPath)) {
            log.debug("Normalized reconciliation root from {} to {}", rootPath, repoRoot);
        }

        int deletedFiles = 0;
        int deletedNodes = 0;
        int processedFiles = 0;
        int batchSize = 1000;
        int skip = 0;
        
        // Get total count for progress tracking
        long totalFiles = fileRepository.countByRepositoryAndModule(repository, module);
        log.info("Total files in Neo4j to check: {}", totalFiles);
        
        while (true) {
            // Fetch batch of file paths
            List<String> neo4jFilesBatch = fileRepository.findFilePathsPaginated(repository, module, skip, batchSize);
            
            if (neo4jFilesBatch.isEmpty()) {
                break;
            }
            
            // Process each file in the batch
            for (String filePath : neo4jFilesBatch) {
                processedFiles++;
                
                // Construct absolute path
                Path absolutePath = repoRoot.resolve(filePath);
                
                if (!Files.exists(absolutePath)) {
                    log.info("File no longer exists on disk, deleting from Neo4j: {}", filePath);
                    
                    // Delete the file from Neo4j
                    DeletionStats stats = deleteFile(absolutePath, repository, module);
                    deletedFiles++;
                    deletedNodes += stats.totalNodes();
                }
                
                // Log progress every 1000 files
                if (processedFiles % 1000 == 0) {
                    log.info("Reconciliation progress: {}/{} files checked, {} deleted", 
                            processedFiles, totalFiles, deletedFiles);
                }
            }
            
            skip += batchSize;
        }
        
        log.info("Neo4j reconciliation complete: deletedFiles={}, deletedNodes={}, totalChecked={}", 
                deletedFiles, deletedNodes, processedFiles);
        return new ReconciliationStats(deletedFiles, deletedNodes);
    }

    // ── Result classes ──────────────────────────────────────────────────────

    public record DeletionStats(int deletedClasses, int deletedMethods, int deletedFields) {
        public int totalNodes() {
            return deletedClasses + deletedMethods + deletedFields;
        }
    }

    public record ReconciliationStats(int deletedFiles, int deletedNodes) {
    }
}
