package org.example.springaimcp.graph.repository;

import org.example.springaimcp.graph.model.CodeRepository;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for CodeRepository entities in Neo4j.
 */
@Repository
public interface CodeRepositoryRepository extends Neo4jRepository<CodeRepository, Long> {

    Optional<CodeRepository> findByRepoId(String repoId);

    @Query("MATCH (r:Repository {repoId: $repoId}) " +
           "OPTIONAL MATCH (r)-[:CONTAINS*]->(n) " +
           "DETACH DELETE r, n")
    void deleteRepositoryAndChildren(String repoId);
}
