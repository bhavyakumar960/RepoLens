package org.example.springaimcp.graph.repository;

import org.example.springaimcp.graph.model.JavaFile;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for JavaFile entities in Neo4j.
 */
@Repository
public interface JavaFileRepository extends Neo4jRepository<JavaFile, Long> {

    Optional<JavaFile> findByFileId(String fileId);

    List<JavaFile> findByRepository(String repository);

    @Query("MATCH (f:File {repository: $repository, path: $path}) RETURN f")
    Optional<JavaFile> findByRepositoryAndPath(String repository, String path);

    @Query("MATCH (f:File {fileId: $fileId}) " +
           "OPTIONAL MATCH (f)-[:DECLARES]->(c:Class)-[:HAS_METHOD]->(m:Method) " +
           "RETURN f, collect(c), collect(m)")
    Optional<JavaFile> findByFileIdWithDetails(String fileId);

    @Query("MATCH (f:File) " +
           "WHERE ($repository IS NULL OR f.repository = $repository) " +
           "  AND ($module IS NULL OR f.module = $module) " +
           "RETURN f.path " +
           "ORDER BY f.path " +
           "SKIP $skip LIMIT $limit")
    List<String> findFilePathsPaginated(String repository, String module, int skip, int limit);

    @Query("MATCH (f:File) " +
           "WHERE ($repository IS NULL OR f.repository = $repository) " +
           "  AND ($module IS NULL OR f.module = $module) " +
           "RETURN count(f)")
    long countByRepositoryAndModule(String repository, String module);
}
