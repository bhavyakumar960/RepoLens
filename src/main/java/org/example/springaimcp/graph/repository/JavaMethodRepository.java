package org.example.springaimcp.graph.repository;

import org.example.springaimcp.graph.model.JavaMethod;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for JavaMethod entities in Neo4j.
 */
@Repository
public interface JavaMethodRepository extends Neo4jRepository<JavaMethod, Long> {

    Optional<JavaMethod> findByMethodId(String methodId);

    List<JavaMethod> findByRepository(String repository);

    List<JavaMethod> findByRepositoryAndClassNameAndName(String repository, String className, String name);

    /**
     * Cross-repository lookup by class + method name, resolving all candidate target
     * repositories in a single round trip. Used by cross-repo CALLS resolution instead
     * of looping {@code findByRepositoryAndClassNameAndName} once per target repository,
     * which becomes O(repositories) round trips per invocation once target repositories
     * are auto-discovered (potentially every ingested repository).
     */
    @Query("MATCH (m:Method {className: $className, name: $name}) " +
           "WHERE m.repository IN $repositories " +
           "RETURN m")
    List<JavaMethod> findByClassNameAndNameInRepositories(@Param("repositories") List<String> repositories,
                                                          @Param("className") String className,
                                                          @Param("name") String name);

    /**
     * Fallback cross-repository lookup by method name + simple class-name suffix
     * (handles cases where the declared receiver type couldn't be resolved to a
     * fully-qualified name). Also resolves all candidate repositories in one query.
     */
    @Query("MATCH (m:Method {name: $name}) " +
           "WHERE m.repository IN $repositories AND m.className ENDS WITH $classSuffix " +
           "RETURN m")
    List<JavaMethod> findByNameAndClassNameEndingInRepositories(@Param("repositories") List<String> repositories,
                                                                @Param("name") String name,
                                                                @Param("classSuffix") String classSuffix);

    /**
     * Cross-repository lookup by class/method name, ignoring repository. Used as a
     * fallback when a caller supplies the wrong repository in a methodId so we can
     * still resolve the method and tell the caller which repository it actually
     * lives in.
     */
    List<JavaMethod> findByClassNameAndName(String className, String name);

    List<JavaMethod> findByRepositoryAndName(String repository, String name);

    @Query("MATCH (m:Method {repository: $repository, className: $className, name: $name}) " +
           "WHERE m.parameterTypes = $parameterTypes " +
           "RETURN m")
    List<JavaMethod> findExactMethodInRepository(@Param("repository") String repository,
                                                 @Param("className") String className,
                                                 @Param("name") String name,
                                                 @Param("parameterTypes") List<String> parameterTypes);

    @Query("MATCH (m:Method {className: $className, name: $name}) " +
           "WHERE m.repository IN $repositories " +
           "  AND m.parameterTypes = $parameterTypes " +
           "  AND coalesce(m.nodeStatus, 'REAL') <> 'STUB' " +
           "RETURN m")
    List<JavaMethod> findExactMethodAcrossRepositories(@Param("repositories") List<String> repositories,
                                                       @Param("className") String className,
                                                       @Param("name") String name,
                                                       @Param("parameterTypes") List<String> parameterTypes);

    @Query("MATCH (m:Method {resolutionKey: $resolutionKey}) RETURN m")
    List<JavaMethod> findByResolutionKey(@Param("resolutionKey") String resolutionKey);

    @Query("MATCH (m:Method {resolutionKey: $resolutionKey}) " +
           "WHERE coalesce(m.nodeStatus, 'REAL') <> 'STUB' " +
           "RETURN m")
    List<JavaMethod> findRealMethodsByResolutionKey(@Param("resolutionKey") String resolutionKey);

    @Query("MATCH (m:Method) WHERE coalesce(m.nodeStatus, 'REAL') = 'STUB' RETURN m")
    List<JavaMethod> findStubMethods();

    @Query("MATCH (m:Method) WHERE coalesce(m.nodeStatus, 'REAL') = 'STUB' RETURN count(m)")
    long countStubMethods();

    /**
     * Paginated variant of findStubMethods(), excluding methodIds already visited in
     * this reconciliation run. Iterating the full stub method set in one unbounded
     * query hydrates each Method's relationships (CALLS, returnClass,
     * parameterClasses, etc.) simultaneously, which can exhaust heap for
     * densely-connected modules. We can't page with SKIP because processing a batch
     * mutates/deletes rows matching the same WHERE clause (resolved or orphaned
     * stubs are updated/removed), which would shift subsequent SKIP offsets and
     * cause stubs to be silently missed. Excluding already-visited ids instead keeps
     * each page correct regardless of concurrent mutation, and unresolved stubs
     * (still STUB after processing) are naturally skipped on the next page too.
     */
    @Query("MATCH (m:Method) WHERE coalesce(m.nodeStatus, 'REAL') = 'STUB' " +
           "  AND NOT m.methodId IN $excludedIds " +
           "RETURN m ORDER BY m.methodId LIMIT $limit")
    List<JavaMethod> findStubMethodsPaginated(@Param("excludedIds") List<String> excludedIds, @Param("limit") int limit);

    @Query("MATCH (m:Method {repository: $repository}) " +
           "WHERE m.name = $name OR m.fullyQualifiedSignature CONTAINS $name " +
           "RETURN m LIMIT $limit")
    List<JavaMethod> searchByNameInRepository(@Param("name") String name,
                                              @Param("repository") String repository,
                                              @Param("limit") int limit);

    @Query("MATCH (m:Method) " +
           "WHERE m.name = $name OR m.fullyQualifiedSignature CONTAINS $name " +
           "RETURN m LIMIT $limit")
    List<JavaMethod> searchByName(@Param("name") String name, @Param("limit") int limit);

    @Query("MATCH (m:Method {repository: $repository}) " +
           "WHERE m.name CONTAINS $name OR m.fullyQualifiedSignature CONTAINS $name " +
           "RETURN m LIMIT $limit")
    List<JavaMethod> searchByPartialNameInRepository(@Param("name") String name,
                                                     @Param("repository") String repository,
                                                     @Param("limit") int limit);

    @Query("MATCH (m:Method) " +
           "WHERE m.name CONTAINS $name OR m.fullyQualifiedSignature CONTAINS $name " +
           "RETURN m LIMIT $limit")
    List<JavaMethod> searchByPartialName(@Param("name") String name, @Param("limit") int limit);

    @Query("MATCH (m:Method {methodId: $methodId})-[:CALLS]->(called:Method) RETURN called")
    List<JavaMethod> findCalledMethods(String methodId);

    @Query("MATCH (source:Method {methodId: $methodId})-[r:CALLS]->(target:Method) " +
           "RETURN target.methodId AS targetMethodId, " +
           "       target.resolutionKey AS targetResolutionKey, " +
           "       r.invocationName AS invocationName, " +
           "       r.argumentCount AS argumentCount, " +
           "       r.line AS line, " +
           "       r.column AS column, " +
           "       r.resolutionStatus AS resolutionStatus, " +
           "       r.resolutionType AS resolutionType, " +
           "       r.invocationCount AS invocationCount, " +
           "       r.callSites AS callSites, " +
           "       r.declaredType AS declaredType " +
           "ORDER BY target.methodId")
    List<Map<String, Object>> findCallRelationshipDetails(@Param("methodId") String methodId);

    @Query("MATCH (caller:Method)-[:CALLS]->(m:Method {methodId: $methodId}) RETURN caller")
    List<JavaMethod> findCallers(String methodId);

    @Query("MATCH (m:Method) WHERE $annotation IN m.springAnnotations RETURN m")
    List<JavaMethod> findBySpringAnnotation(String annotation);

    @Query("MATCH (m:Method) WHERE m.httpMethod IS NOT NULL AND m.apiPath IS NOT NULL RETURN m")
    List<JavaMethod> findAllApiEndpoints();

    @Query("MATCH (m:Method {httpMethod: $httpMethod}) RETURN m")
    List<JavaMethod> findApiEndpointsByHttpMethod(String httpMethod);

    @Query("MATCH path = (start:Method {methodId: $startMethodId})-[:CALLS*1..5]->(end:Method) " +
           "RETURN path")
    List<Object> findCallChain(String startMethodId);

    @Query("MATCH (test:Method)-[:TESTS]->(m:Method {methodId: $methodId}) RETURN test")
    List<JavaMethod> findTestsForMethod(String methodId);
    
    /**
     * Create a TESTED_BY relationship from test method to tested method.
     * Direction: (testMethod)-[:TESTED_BY]->(testedMethod)
     */
    @Query("MATCH (test:Method {methodId: $testMethodId}), " +
           "(tested:Method {methodId: $testedMethodId}) " +
           "MERGE (test)-[:TESTED_BY]->(tested)")
    void createTestedByRelationship(@Param("testMethodId") String testMethodId, 
                                     @Param("testedMethodId") String testedMethodId);
    
    /**
     * Count total methods in a repository.
     */
    @Query("MATCH (m:Method {repository: $repository}) RETURN count(m)")
    long countByRepository(@Param("repository") String repository);
    
    /**
     * Find methods in a repository with pagination.
     */
    @Query("MATCH (m:Method {repository: $repository}) " +
           "RETURN m " +
           "SKIP $skip LIMIT $limit")
    List<JavaMethod> findByRepositoryPaginated(@Param("repository") String repository,
                                               @Param("skip") int skip,
                                               @Param("limit") int limit);
}
