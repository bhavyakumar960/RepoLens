package org.example.springaimcp.graph.repository;

import org.example.springaimcp.graph.model.JavaClass;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for JavaClass entities in Neo4j.
 */
@Repository
public interface JavaClassRepository extends Neo4jRepository<JavaClass, Long> {

    Optional<JavaClass> findByClassId(String classId);

    @Query("MATCH (c:Class {repository: $repository}) " +
           "WHERE c.simpleName = $name OR c.fullyQualifiedName = $name " +
           "RETURN c LIMIT $limit")
    List<JavaClass> searchByNameInRepository(@Param("name") String name,
                                             @Param("repository") String repository,
                                             @Param("limit") int limit);

    List<JavaClass> findByRepository(String repository);

    @Query("MATCH (c:Class) " +
           "WHERE c.simpleName = $name OR c.fullyQualifiedName = $name " +
           "RETURN c LIMIT $limit")
    List<JavaClass> searchByName(@Param("name") String name, @Param("limit") int limit);

    @Query("MATCH (c:Class {repository: $repository}) " +
           "WHERE c.simpleName CONTAINS $name OR c.fullyQualifiedName CONTAINS $name " +
           "RETURN c LIMIT $limit")
    List<JavaClass> searchByPartialNameInRepository(@Param("name") String name,
                                                    @Param("repository") String repository,
                                                    @Param("limit") int limit);

    @Query("MATCH (c:Class) " +
           "WHERE c.simpleName CONTAINS $name OR c.fullyQualifiedName CONTAINS $name " +
           "RETURN c LIMIT $limit")
    List<JavaClass> searchByPartialName(@Param("name") String name, @Param("limit") int limit);

    @Query("MATCH (c:Class {classId: $classId})-[:IMPLEMENTS]->(i:Class) RETURN i")
    List<JavaClass> findImplementedInterfaces(String classId);

    @Query("MATCH (c:Class {classId: $classId})-[:EXTENDS]->(p:Class) RETURN p")
    Optional<JavaClass> findExtendedClass(String classId);

    @Query("MATCH (c:Class)-[:IMPLEMENTS|EXTENDS*]->(target:Class {classId: $classId}) RETURN c")
    List<JavaClass> findSubtypes(String classId);

    @Query("MATCH (c:Class) WHERE $annotation IN c.springAnnotations RETURN c")
    List<JavaClass> findBySpringAnnotation(String annotation);

    List<JavaClass> findByRepositoryAndModule(String repository, String module);

    @Query("MATCH (c:Class {repository: $repository, module: $module}) " +
           "RETURN c " +
           "ORDER BY c.classId " +
           "SKIP $skip LIMIT $limit")
    List<JavaClass> findByRepositoryAndModulePaginated(@Param("repository") String repository,
                                                        @Param("module") String module,
                                                        @Param("skip") int skip,
                                                        @Param("limit") int limit);

    @Query("MATCH (c:Class {repository: $repository, module: $module}) RETURN count(c)")
    long countByRepositoryAndModule(@Param("repository") String repository, @Param("module") String module);

    /**
     * Finds stub classes tagged with the given module but whose repository property
     * was never resolved (e.g. left as "unknown" at stub-creation time). These are
     * missed by findByRepositoryAndModule*(repository, module) because that filters
     * on an exact repository match, so they need to be located by module alone and
     * reconciled against the real class in the intended repository.
     */
    @Query("MATCH (c:Class {module: $module}) " +
           "WHERE coalesce(c.nodeStatus, 'REAL') = 'STUB' " +
           "RETURN c " +
           "ORDER BY c.classId " +
           "SKIP $skip LIMIT $limit")
    List<JavaClass> findStubClassesByModulePaginated(@Param("module") String module,
                                                      @Param("skip") int skip,
                                                      @Param("limit") int limit);

    @Query("MATCH (c:Class {repository: $repository, simpleName: $simpleName}) RETURN c")
    List<JavaClass> findByRepositoryAndSimpleName(@Param("repository") String repository, 
                                                   @Param("simpleName") String simpleName);

    /**
     * Cross-repository simple-name lookup, resolving all candidate repositories in a
     * single round trip instead of looping {@code findByRepositoryAndSimpleName} once
     * per target repository (which becomes O(repositories) round trips per class when
     * cross-repo linking auto-discovers many target repositories).
     */
    @Query("MATCH (c:Class {simpleName: $simpleName}) " +
           "WHERE c.repository IN $repositories " +
           "RETURN c")
    List<JavaClass> findBySimpleNameInRepositories(@Param("repositories") List<String> repositories,
                                                    @Param("simpleName") String simpleName);
    
    @Query("MATCH (c:Class) " +
           "WHERE c.filePath ENDS WITH $fileName " +
           "RETURN c LIMIT $limit")
    List<JavaClass> findByFileName(@Param("fileName") String fileName, @Param("limit") int limit);
    
    @Query("MATCH (c:Class) " +
           "WHERE c.fullyQualifiedName = $fqn " +
           "RETURN c LIMIT $limit")
    List<JavaClass> findByFullyQualifiedName(@Param("fqn") String fqn, @Param("limit") int limit);

    @Query("MATCH (c:Class) " +
           "WHERE c.fullyQualifiedName = $fqn " +
           "  AND c.repository IN $repositories " +
           "RETURN c")
    List<JavaClass> findByFullyQualifiedNameInRepositories(@Param("fqn") String fqn,
                                                           @Param("repositories") List<String> repositories);

    @Query("MATCH (impl:Class)-[:IMPLEMENTS|EXTENDS*1..5]->(iface:Class {fullyQualifiedName: $fullyQualifiedName}) " +
           "WHERE coalesce(impl.nodeStatus, 'REAL') <> 'STUB' " +
           "RETURN DISTINCT impl")
    List<JavaClass> findImplementationsByFullyQualifiedName(@Param("fullyQualifiedName") String fullyQualifiedName);
    
    @Query("MATCH (c:Class {repository: $repository}) RETURN count(c)")
    long countByRepository(@Param("repository") String repository);

    /**
     * Returns every distinct repository name present in the graph (excluding null/unknown
     * placeholders). Used to auto-discover cross-repository linkage candidates instead of
     * relying on a statically configured dependency map.
     */
    @Query("MATCH (c:Class) " +
           "WHERE c.repository IS NOT NULL AND c.repository <> 'unknown' " +
           "RETURN DISTINCT c.repository")
    List<String> findAllDistinctRepositories();
    
    @Query("MATCH (c:Class {repository: $repository}) " +
           "RETURN c " +
           "SKIP $skip LIMIT $limit")
    List<JavaClass> findByRepositoryPaginated(@Param("repository") String repository,
                                               @Param("skip") int skip,
                                               @Param("limit") int limit);
    

    @Query("MATCH (source:Class {classId: $sourceClassId}), (target:Class {classId: $targetClassId}) " +
           "MERGE (source)-[r:INJECTS]->(target) " +
           "RETURN count(r)")
    int createInjectsRelationship(@Param("sourceClassId") String sourceClassId,
                                    @Param("targetClassId") String targetClassId);
    
    @Query("MATCH (source:Class {classId: $sourceClassId})-[r:INJECTS]->(target:Class {classId: $targetClassId}) " +
           "RETURN count(r) > 0")
    boolean verifyInjectsRelationship(@Param("sourceClassId") String sourceClassId,
                                       @Param("targetClassId") String targetClassId);
}
