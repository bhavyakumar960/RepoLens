package org.example.springaimcp.graph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.example.springaimcp.graph.util.GraphNodeIdentity;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reconciles graph relationships after one or more repositories are ingested.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelationshipReconciliationService {

    /**
     * Batch size used when paginating through classes/methods for reconciliation.
     * Kept small so the hydrated Spring Data Neo4j object graph (which can fan out
     * across EXTENDS/IMPLEMENTS/USES/HAS_METHOD relationships) never grows large
     * enough to exhaust the heap for densely-connected modules.
     */
    private static final int BATCH_SIZE = 200;

    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;
    private final Neo4jClient neo4jClient;

    /**
     * Note: intentionally NOT wrapped in a single @Transactional. Each step below
     * runs its own (Spring Data managed) transactions per batch, so a failure or
     * OOM partway through does not roll back work already committed by earlier
     * steps/batches. This is safe because every step is idempotent - re-running
     * it after a partial failure simply finds fewer stubs left to fix.
     */
    public ReconciliationResult reconcileRelationships(String repository) {
        log.info("Starting relationship reconciliation for repository={}", repository);

        ReconciliationResult result = new ReconciliationResult();
        long startTime = System.currentTimeMillis();

        result.stubsCompleted = completeStubClasses(repository);
        result.extendsFixed = reconcileExtendsRelationships(repository);
        result.implementsFixed = reconcileImplementsRelationships(repository);
        result.usesFixed = reconcileUsesRelationships(repository);
        result.methodRelationsFixed = reconcileMethodTypeRelationships(repository);
        result.callsFixed = reconcileCallsRelationshipsGlobally();
        result.callStats = collectCallStatistics();
        result.callStats.reconciledCalls = result.callsFixed;
        result.totalTimeMs = System.currentTimeMillis() - startTime;

        log.info("Relationship reconciliation completed: {}", result);
        return result;
    }

    /**
     * Iterates classes for a repository in bounded pages instead of loading
     * the whole repository's class graph into memory at once.
     */
    private void forEachClassBatch(String repository, java.util.function.Consumer<List<JavaClass>> processor) {
        int skip = 0;
        List<JavaClass> page;
        do {
            page = classRepository.findByRepositoryPaginated(repository, skip, BATCH_SIZE);
            if (!page.isEmpty()) {
                processor.accept(page);
            }
            skip += BATCH_SIZE;
        } while (page.size() == BATCH_SIZE);
    }

    private void forEachMethodBatch(String repository, java.util.function.Consumer<List<JavaMethod>> processor) {
        int skip = 0;
        List<JavaMethod> page;
        do {
            page = methodRepository.findByRepositoryPaginated(repository, skip, BATCH_SIZE);
            if (!page.isEmpty()) {
                processor.accept(page);
            }
            skip += BATCH_SIZE;
        } while (page.size() == BATCH_SIZE);
    }

    @Transactional
    public ReconciliationResult reconcileAllRelationships() {
        ReconciliationResult result = new ReconciliationResult();
        long startTime = System.currentTimeMillis();
        result.callsFixed = reconcileCallsRelationshipsGlobally();
        result.callStats = collectCallStatistics();
        result.callStats.reconciledCalls = result.callsFixed;
        result.totalTimeMs = System.currentTimeMillis() - startTime;
        log.info("Global relationship reconciliation completed: {}", result);
        return result;
    }

    private int completeStubClasses(String repository) {
        AtomicInteger completed = new AtomicInteger();

        forEachClassBatch(repository, batch -> batch.stream()
                .filter(this::isStubClass)
                .forEach(stub -> classRepository.findByFullyQualifiedName(stub.getFullyQualifiedName(), 20).stream()
                        .filter(candidate -> repository.equals(candidate.getRepository()))
                        .filter(candidate -> !isStubClass(candidate))
                        .findFirst()
                        .ifPresent(fullClass -> {
                            mergeClassData(stub, fullClass);
                            classRepository.save(stub);
                            completed.incrementAndGet();
                        })));

        return completed.get();
    }

    private int reconcileExtendsRelationships(String repository) {
        AtomicInteger fixed = new AtomicInteger();

        forEachClassBatch(repository, batch -> batch.forEach(javaClass -> {
            if (javaClass.getExtendedClasses() == null || javaClass.getExtendedClasses().isEmpty()) {
                return;
            }
            Set<JavaClass> updated = new HashSet<>();
            boolean changed = false;
            for (JavaClass parentClass : javaClass.getExtendedClasses()) {
                if (!isStubClass(parentClass)) {
                    updated.add(parentClass);
                    continue;
                }
                Optional<JavaClass> fullClass = findFullClass(parentClass.getFullyQualifiedName(), repository);
                if (fullClass.isPresent()) {
                    updated.add(fullClass.get());
                    changed = true;
                } else {
                    updated.add(parentClass);
                }
            }
            if (changed) {
                javaClass.setExtendedClasses(updated);
                classRepository.save(javaClass);
                fixed.incrementAndGet();
            }
        }));

        return fixed.get();
    }

    private int reconcileImplementsRelationships(String repository) {
        AtomicInteger fixed = new AtomicInteger();

        forEachClassBatch(repository, batch -> batch.forEach(javaClass -> {
            if (javaClass.getImplementedInterfaces() == null || javaClass.getImplementedInterfaces().isEmpty()) {
                return;
            }
            Set<JavaClass> updated = new HashSet<>();
            boolean changed = false;
            for (JavaClass interfaceClass : javaClass.getImplementedInterfaces()) {
                if (!isStubClass(interfaceClass)) {
                    updated.add(interfaceClass);
                    continue;
                }
                Optional<JavaClass> fullClass = findFullClass(interfaceClass.getFullyQualifiedName(), repository);
                if (fullClass.isPresent()) {
                    updated.add(fullClass.get());
                    changed = true;
                } else {
                    updated.add(interfaceClass);
                }
            }
            if (changed) {
                javaClass.setImplementedInterfaces(updated);
                classRepository.save(javaClass);
                fixed.incrementAndGet();
            }
        }));

        return fixed.get();
    }

    private int reconcileUsesRelationships(String repository) {
        AtomicInteger fixed = new AtomicInteger();

        forEachClassBatch(repository, batch -> batch.stream()
                .filter(javaClass -> !isStubClass(javaClass))
                .forEach(javaClass -> {
                    Set<JavaClass> usedClasses = javaClass.getUsedClasses();
                    if (usedClasses == null) {
                        usedClasses = new HashSet<>();
                        javaClass.setUsedClasses(usedClasses);
                    }
                    Set<JavaClass> finalUsedClasses = usedClasses;
                    int initialSize = finalUsedClasses.size();
                    if (javaClass.getFields() != null) {
                        javaClass.getFields().forEach(field -> {
                            if (field.getFieldType() != null) {
                                finalUsedClasses.add(field.getFieldType());
                            }
                        });
                    }
                    if (javaClass.getMethods() != null) {
                        javaClass.getMethods().forEach(method -> {
                            if (method.getReturnClass() != null) {
                                finalUsedClasses.add(method.getReturnClass());
                            }
                            if (method.getParameterClasses() != null) {
                                finalUsedClasses.addAll(method.getParameterClasses());
                            }
                        });
                    }
                    if (finalUsedClasses.size() > initialSize) {
                        classRepository.save(javaClass);
                        fixed.incrementAndGet();
                    }
                }));

        return fixed.get();
    }

    private int reconcileCallsRelationshipsGlobally() {
        log.info("Reconciling CALLS relationships globally across repositories");
        AtomicInteger fixed = new AtomicInteger();

        // Paginate through stub methods in bounded batches (see findStubMethodsPaginated javadoc
        // for why SKIP-based paging is unsafe here). Batches that remain STUB after processing
        // (ambiguous/unresolved) are marked visited too, so they aren't refetched forever.
        List<String> visitedIds = new java.util.ArrayList<>();
        List<JavaMethod> batch;
        do {
            batch = methodRepository.findStubMethodsPaginated(visitedIds, BATCH_SIZE);
            for (JavaMethod stubMethod : batch) {
                visitedIds.add(stubMethod.getMethodId());
                reconcileSingleStubMethodCalls(stubMethod, fixed);
            }
        } while (!batch.isEmpty());

        log.info("CALLS reconciliation fixed {} relationship(s)", fixed.get());
        return fixed.get();
    }

    private void reconcileSingleStubMethodCalls(JavaMethod stubMethod, AtomicInteger fixed) {
        boolean declaredAsInterface = classRepository.findByFullyQualifiedName(stubMethod.getClassName(), 20).stream()
                .anyMatch(candidate -> "INTERFACE".equals(candidate.getType()) && !isStubClass(candidate));
        List<JavaMethod> interfaceMatches = declaredAsInterface ? findInterfaceImplementationMatches(stubMethod) : List.of();
        if (declaredAsInterface) {
            if (interfaceMatches.size() == 1) {
                fixed.addAndGet(rewireStubCalls(stubMethod.getMethodId(), interfaceMatches.get(0).getMethodId(), "INTERFACE"));
                cleanupStubMethodIfOrphan(stubMethod);
                return;
            }
            if (interfaceMatches.size() > 1) {
                fixed.addAndGet(markIncomingCallsAmbiguous(stubMethod.getMethodId(), "INTERFACE"));
                return;
            }
        }

        List<JavaMethod> exactMatches = methodRepository.findRealMethodsByResolutionKey(stubMethod.getResolutionKey()).stream()
                .filter(candidate -> !isStubMethod(candidate))
                .toList();

        if (exactMatches.size() == 1) {
            fixed.addAndGet(rewireStubCalls(stubMethod.getMethodId(), exactMatches.get(0).getMethodId(), null));
            cleanupStubMethodIfOrphan(stubMethod);
            return;
        }

        if (interfaceMatches.size() == 1) {
            fixed.addAndGet(rewireStubCalls(stubMethod.getMethodId(), interfaceMatches.get(0).getMethodId(), "INTERFACE"));
            cleanupStubMethodIfOrphan(stubMethod);
        } else if (exactMatches.size() > 1 || interfaceMatches.size() > 1) {
            fixed.addAndGet(markIncomingCallsAmbiguous(stubMethod.getMethodId(), null));
        } else if (hasUnresolvedParameterTypes(stubMethod)) {
            // Argument-type inference failed at ingestion time (e.g. "unknown" placeholder types),
            // so the exact resolutionKey can never match. Fall back to matching by
            // class + method name + argument count, which is invariant even when types weren't inferred.
            fixed.addAndGet(reconcileByArgumentCountFallback(stubMethod));
        }
    }

    private boolean hasUnresolvedParameterTypes(JavaMethod method) {
        return method.getParameterTypes() != null && method.getParameterTypes().stream().anyMatch("unknown"::equals);
    }

    private int reconcileByArgumentCountFallback(JavaMethod stubMethod) {
        if (stubMethod.getClassName() == null || stubMethod.getName() == null) {
            return 0;
        }
        int paramCount = stubMethod.getParameterTypes() == null ? 0 : stubMethod.getParameterTypes().size();
        List<JavaMethod> candidates = methodRepository.findByClassNameAndName(stubMethod.getClassName(), stubMethod.getName()).stream()
                .filter(candidate -> !isStubMethod(candidate))
                .filter(candidate -> candidate.getParameterTypes() != null && candidate.getParameterTypes().size() == paramCount)
                .toList();

        if (candidates.size() == 1) {
            int fixedCount = rewireStubCalls(stubMethod.getMethodId(), candidates.get(0).getMethodId(), "ARGUMENT_COUNT_FALLBACK");
            cleanupStubMethodIfOrphan(stubMethod);
            return fixedCount;
        }
        if (candidates.size() > 1) {
            return markIncomingCallsAmbiguous(stubMethod.getMethodId(), "ARGUMENT_COUNT_FALLBACK");
        }
        return 0;
    }

    private List<JavaMethod> findInterfaceImplementationMatches(JavaMethod stubMethod) {
        if (stubMethod.getClassName() == null) {
            return List.of();
        }
        return classRepository.findImplementationsByFullyQualifiedName(stubMethod.getClassName()).stream()
                .flatMap(impl -> methodRepository.findExactMethodInRepository(
                        impl.getRepository(),
                        impl.getFullyQualifiedName(),
                        stubMethod.getName(),
                        stubMethod.getParameterTypes()
                ).stream())
                .filter(candidate -> !isStubMethod(candidate))
                .toList();
    }

    private int rewireStubCalls(String stubMethodId, String realMethodId, String resolutionType) {
        return neo4jClient.query("""
                MATCH (stub:Method {methodId: $stubMethodId})<-[r:CALLS]-(source:Method)
                MATCH (real:Method {methodId: $realMethodId})
                MERGE (source)-[merged:CALLS]->(real)
                ON CREATE SET merged = properties(r)
                ON MATCH SET merged.invocationCount = coalesce(merged.invocationCount, 0) + coalesce(r.invocationCount, 0),
                             merged.callSites = reduce(acc = coalesce(merged.callSites, []), site IN coalesce(r.callSites, []) |
                                CASE WHEN site IN acc THEN acc ELSE acc + site END),
                             merged.line = CASE
                                 WHEN merged.line IS NULL OR (r.line IS NOT NULL AND r.line < merged.line) THEN r.line
                                 ELSE merged.line
                             END,
                             merged.column = CASE
                                 WHEN merged.column IS NULL THEN r.column
                                 ELSE merged.column
                             END,
                             merged.invocationName = coalesce(merged.invocationName, r.invocationName),
                             merged.argumentCount = coalesce(merged.argumentCount, r.argumentCount),
                             merged.declaredType = coalesce(merged.declaredType, r.declaredType)
                SET merged.relationshipKey = source.methodId + '->' + real.methodId,
                    merged.resolutionStatus = 'RESOLVED',
                    merged.targetResolutionKey = real.resolutionKey,
                    merged.resolutionType = coalesce($resolutionType, r.resolutionType, 'CROSS_REPOSITORY')
                WITH collect(r) AS oldRelationships
                FOREACH (oldRelationship IN oldRelationships | DELETE oldRelationship)
                RETURN size(oldRelationships) AS fixed
                """)
                .bind(stubMethodId).to("stubMethodId")
                .bind(realMethodId).to("realMethodId")
                .bind(resolutionType).to("resolutionType")
                .fetchAs(Integer.class)
                .one()
                .orElse(0);
    }

    private int markIncomingCallsAmbiguous(String stubMethodId, String resolutionType) {
        return neo4jClient.query("""
                MATCH (:Method {methodId: $stubMethodId})<-[r:CALLS]-(:Method)
                SET r.resolutionStatus = 'AMBIGUOUS',
                    r.resolutionType = CASE
                        WHEN $resolutionType IS NOT NULL THEN $resolutionType
                        WHEN coalesce(r.resolutionType, '') = '' THEN 'INTERFACE'
                        ELSE r.resolutionType
                    END
                RETURN count(r) AS updated
                """)
                .bind(stubMethodId).to("stubMethodId")
                .bind(resolutionType).to("resolutionType")
                .fetchAs(Integer.class)
                .one()
                .orElse(0);
    }

    private void cleanupStubMethodIfOrphan(JavaMethod stubMethod) {
        neo4jClient.query("""
                MATCH (stub:Method {methodId: $methodId})
                WHERE coalesce(stub.nodeStatus, 'REAL') = 'STUB'
                  AND NOT (:Method)-[:CALLS]->(stub)
                  AND NOT (stub)-[:CALLS]->(:Method)
                DETACH DELETE stub
                """)
                .bind(stubMethod.getMethodId()).to("methodId")
                .run();

        neo4jClient.query("""
                MATCH (stubClass:Class {classId: $classId})
                WHERE coalesce(stubClass.nodeStatus, 'REAL') = 'STUB'
                  AND NOT (stubClass)--()
                DELETE stubClass
                """)
                .bind(GraphNodeIdentity.classId(stubMethod.getRepository(), stubMethod.getClassName())).to("classId")
                .run();
    }

    private int reconcileMethodTypeRelationships(String repository) {
        AtomicInteger fixed = new AtomicInteger();

        forEachMethodBatch(repository, batch -> batch.forEach(method -> {
            boolean changed = false;
            if (method.getReturnType() != null && method.getReturnClass() == null && isNotPrimitiveOrBuiltin(method.getReturnType())) {
                findClassByType(method.getReturnType(), repository).ifPresent(returnClass -> {
                    method.setReturnClass(returnClass);
                });
                changed = method.getReturnClass() != null;
            }

            if (method.getParameterTypes() != null && !method.getParameterTypes().isEmpty()) {
                Set<JavaClass> paramClasses = method.getParameterClasses();
                if (paramClasses == null) {
                    paramClasses = new HashSet<>();
                    method.setParameterClasses(paramClasses);
                }
                int initialSize = paramClasses.size();
                for (String parameterType : method.getParameterTypes()) {
                    if (isNotPrimitiveOrBuiltin(parameterType)) {
                        findClassByType(parameterType, repository).ifPresent(paramClasses::add);
                    }
                }
                changed = changed || paramClasses.size() > initialSize;
            }

            if (changed) {
                methodRepository.save(method);
                fixed.incrementAndGet();
            }
        }));

        return fixed.get();
    }

    private CallStatistics collectCallStatistics() {
        return neo4jClient.query("""
                MATCH (source:Method)-[r:CALLS]->(target:Method)
                RETURN coalesce(sum(coalesce(r.invocationCount, 1)), 0) AS totalCallsDiscovered,
                       coalesce(sum(CASE WHEN r.resolutionStatus = 'RESOLVED' THEN coalesce(r.invocationCount, 1) ELSE 0 END), 0) AS resolvedCalls,
                       coalesce(sum(CASE WHEN r.resolutionStatus = 'STUB' THEN coalesce(r.invocationCount, 1) ELSE 0 END), 0) AS stubCalls,
                       coalesce(sum(CASE WHEN r.resolutionStatus = 'AMBIGUOUS' THEN coalesce(r.invocationCount, 1) ELSE 0 END), 0) AS ambiguousCalls,
                       coalesce(sum(CASE WHEN r.resolutionStatus = 'UNRESOLVED' THEN coalesce(r.invocationCount, 1) ELSE 0 END), 0) AS unresolvedCalls,
                       coalesce(sum(CASE WHEN source.repository <> target.repository THEN coalesce(r.invocationCount, 1) ELSE 0 END), 0) AS crossRepositoryCalls,
                       coalesce(sum(CASE WHEN source.methodId = target.methodId THEN coalesce(r.invocationCount, 1) ELSE 0 END), 0) AS selfCalls,
                       coalesce(count(DISTINCT CASE WHEN coalesce(target.nodeStatus, 'REAL') = 'STUB' THEN target.methodId END), 0) AS remainingStubs
                """)
                .fetch()
                .one()
                .map(this::mapCallStatistics)
                .orElse(new CallStatistics());
    }

    private CallStatistics mapCallStatistics(Map<String, Object> row) {
        CallStatistics statistics = new CallStatistics();
        statistics.totalCallsDiscovered = ((Number) row.getOrDefault("totalCallsDiscovered", 0)).intValue();
        statistics.resolvedCalls = ((Number) row.getOrDefault("resolvedCalls", 0)).intValue();
        statistics.stubCalls = ((Number) row.getOrDefault("stubCalls", 0)).intValue();
        statistics.ambiguousCalls = ((Number) row.getOrDefault("ambiguousCalls", 0)).intValue();
        statistics.unresolvedCalls = ((Number) row.getOrDefault("unresolvedCalls", 0)).intValue();
        statistics.crossRepositoryCalls = ((Number) row.getOrDefault("crossRepositoryCalls", 0)).intValue();
        statistics.selfCalls = ((Number) row.getOrDefault("selfCalls", 0)).intValue();
        statistics.remainingStubs = ((Number) row.getOrDefault("remainingStubs", 0)).intValue();
        return statistics;
    }

    private boolean isStubMethod(JavaMethod method) {
        return GraphNodeIdentity.STUB.equals(method.getNodeStatus())
                || method.getFilePath() == null
                || method.getPackageName() == null
                || method.getStartLine() == null
                || method.getStartLine() == 0;
    }

    private boolean isStubClass(JavaClass javaClass) {
        return GraphNodeIdentity.STUB.equals(javaClass.getNodeStatus())
                || javaClass.getFilePath() == null
                || javaClass.getModule() == null
                || javaClass.getPackageName() == null
                || javaClass.getMethods() == null
                || javaClass.getMethods().isEmpty();
    }

    private void mergeClassData(JavaClass stub, JavaClass fullClass) {
        if (stub.getFilePath() == null) stub.setFilePath(fullClass.getFilePath());
        if (stub.getModule() == null) stub.setModule(fullClass.getModule());
        if (stub.getPackageName() == null) stub.setPackageName(fullClass.getPackageName());
        if (stub.getType() == null) stub.setType(fullClass.getType());
        if (stub.getStartLine() == null) stub.setStartLine(fullClass.getStartLine());
        if (stub.getEndLine() == null) stub.setEndLine(fullClass.getEndLine());
        if (stub.getBranch() == null) stub.setBranch(fullClass.getBranch());
        if (stub.getCommit() == null) stub.setCommit(fullClass.getCommit());
        if (stub.getResolutionKey() == null) stub.setResolutionKey(fullClass.getResolutionKey());
        stub.setNodeStatus(GraphNodeIdentity.REAL);

        if (stub.getSpringAnnotations() == null || stub.getSpringAnnotations().isEmpty()) {
            stub.setSpringAnnotations(fullClass.getSpringAnnotations());
        }
        if (stub.getMethods() == null || stub.getMethods().isEmpty()) {
            stub.setMethods(fullClass.getMethods());
        }
        if (stub.getFields() == null || stub.getFields().isEmpty()) {
            stub.setFields(fullClass.getFields());
        }
    }

    private Optional<JavaClass> findFullClass(String fullyQualifiedName, String repository) {
        if (fullyQualifiedName == null) {
            return Optional.empty();
        }
        return classRepository.findByFullyQualifiedName(fullyQualifiedName, 20).stream()
                .filter(candidate -> repository.equals(candidate.getRepository()))
                .filter(candidate -> !isStubClass(candidate))
                .findFirst();
    }

    private Optional<JavaClass> findClassByType(String typeName, String repository) {
        List<JavaClass> simpleMatches = classRepository.findByRepositoryAndSimpleName(repository, normalizeType(typeName));
        if (!simpleMatches.isEmpty()) {
            return Optional.of(simpleMatches.get(0));
        }
        List<JavaClass> fqnMatches = classRepository.findByFullyQualifiedName(typeName, 5);
        return fqnMatches.stream().findFirst();
    }

    private boolean isNotPrimitiveOrBuiltin(String typeName) {
        if (typeName == null) {
            return false;
        }
        String clean = normalizeType(typeName);
        Set<String> primitives = Set.of(
                "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
                "String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Byte",
                "Short", "Character", "Number", "Comparable", "Iterable", "Cloneable",
                "List", "Map", "Set", "Collection", "Optional", "Stream", "Iterator",
                "ArrayList", "HashMap", "HashSet", "LinkedList", "TreeMap", "TreeSet",
                "Class", "Enum", "Throwable", "Exception", "RuntimeException", "Error"
        );
        return !(primitives.contains(clean) || clean.startsWith("java.") || clean.startsWith("javax.") || clean.startsWith("jakarta."));
    }

    private String normalizeType(String typeName) {
        String clean = typeName == null ? "" : typeName.replaceAll("<.*>", "").trim();
        if (clean.endsWith("[]")) {
            clean = clean.substring(0, clean.length() - 2);
        }
        int lastDot = clean.lastIndexOf('.');
        return lastDot >= 0 ? clean.substring(lastDot + 1) : clean;
    }

    public static class ReconciliationResult {
        public int stubsCompleted = 0;
        public int extendsFixed = 0;
        public int implementsFixed = 0;
        public int usesFixed = 0;
        public int callsFixed = 0;
        public int methodRelationsFixed = 0;
        public long totalTimeMs = 0;
        public CallStatistics callStats = new CallStatistics();

        public int getTotalFixed() {
            return stubsCompleted + extendsFixed + implementsFixed + usesFixed + callsFixed + methodRelationsFixed;
        }

        @Override
        public String toString() {
            return "ReconciliationResult{" +
                    "stubs=" + stubsCompleted +
                    ", extends=" + extendsFixed +
                    ", implements=" + implementsFixed +
                    ", uses=" + usesFixed +
                    ", calls=" + callsFixed +
                    ", methodRelations=" + methodRelationsFixed +
                    ", totalCalls=" + callStats.totalCallsDiscovered +
                    ", resolvedCalls=" + callStats.resolvedCalls +
                    ", stubCalls=" + callStats.stubCalls +
                    ", ambiguousCalls=" + callStats.ambiguousCalls +
                    ", unresolvedCalls=" + callStats.unresolvedCalls +
                    ", crossRepositoryCalls=" + callStats.crossRepositoryCalls +
                    ", selfCalls=" + callStats.selfCalls +
                    ", remainingStubs=" + callStats.remainingStubs +
                    ", time=" + totalTimeMs +
                    "ms}";
        }
    }

    public static class CallStatistics {
        public int totalCallsDiscovered = 0;
        public int resolvedCalls = 0;
        public int stubCalls = 0;
        public int reconciledCalls = 0;
        public int remainingStubs = 0;
        public int ambiguousCalls = 0;
        public int unresolvedCalls = 0;
        public int crossRepositoryCalls = 0;
        public int selfCalls = 0;
    }
}
